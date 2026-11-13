package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.gravaai.services.recording.CosmicSession.CosmicAudioRole;
import dev.gravaai.services.recording.MicGate;
import dev.gravaai.services.recording.cosmic.CosmicRecording.AudioTrack;
import dev.gravaai.services.recording.cosmic.CosmicRecording.PauseState;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * One {@code parec} process per requested audio device, writing raw PCM into the recording's temp
 * files, ported from the audio half of {@code electron/ipc/recording/cosmic.ts}.
 *
 * <p>Audio starts before any video; each track remembers when its first sample was captured so the
 * mux can align it. Paused audio is dropped, matching the video, which stops emitting frames.
 */
final class CosmicAudioTracks {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	private static final int AUDIO_RATE = 48_000;
	// parec writes s16le stereo at 48 kHz: 192 bytes per millisecond.
	private static final double AUDIO_BYTES_PER_MS = (AUDIO_RATE * 2 * 2) / 1000.0;
	private static final int AUDIO_CHANNELS = 2;
	private static final int LATENCY_MSEC = 20;
	/** How long start waits for the first chunk of every track; a silent device keeps waiting. */
	private static final long FIRST_DATA_TIMEOUT_MS = 1500;

	private final RecordingProcesses processes;
	private final RecordingEnvironment environment;
	private final PulseAudioSources pulseSources;
	private final CosmicProcessIo processIo;

	CosmicAudioTracks(
			RecordingProcesses processes,
			RecordingEnvironment environment,
			PulseAudioSources pulseSources,
			CosmicProcessIo processIo) {
		this.processes = processes;
		this.environment = environment;
		this.pulseSources = pulseSources;
		this.processIo = processIo;
	}

	/** The audio the recorder was asked to capture. */
	record Options(
			boolean capturesSystemAudio,
			boolean capturesMicrophone,
			@Nullable String microphoneLabel) {
	}

	/** Starts one parec per requested device and waits briefly for each to produce data. */
	List<AudioTrack> start(Path recordingsDir, long timestamp, Options options, PauseState pauseState,
			@Nullable MicGate micGate) {
		List<AudioTrack> audio = new ArrayList<>();
		if (options.capturesSystemAudio()) {
			pulseSources.unmuteDefaultMonitor();
			audio.add(startTrack(recordingsDir, timestamp, PulseAudioSources.DEFAULT_MONITOR,
					CosmicAudioRole.SYSTEM, pauseState, null));
		}
		if (options.capturesMicrophone()) {
			String device = pulseSources.resolveMicrophone(options.microphoneLabel());
			audio.add(startTrack(recordingsDir, timestamp, device, CosmicAudioRole.MIC, pauseState,
					micGate));
		}
		CompletableFuture<Void> allFirstData = CompletableFuture.allOf(
				audio.stream().map(AudioTrack::firstData).toArray(CompletableFuture[]::new));
		try {
			allFirstData.get(FIRST_DATA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException error) {
			// A silent device may not produce data yet; the track keeps waiting in the background.
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException error) {
			// firstData only completes normally.
		}
		return List.copyOf(audio);
	}

	private AudioTrack startTrack(
			Path recordingsDir,
			long timestamp,
			String device,
			CosmicAudioRole role,
			PauseState pauseState,
			@Nullable MicGate micGate) {
		Path file = recordingsDir.resolve(".recording-" + timestamp + "." + role.wire() + ".pcm");
		OutputStream output;
		try {
			output = Files.newOutputStream(file);
		} catch (IOException error) {
			throw new IllegalStateException("Could not create " + file, error);
		}
		SpawnedProcess process;
		try {
			process = processes.spawn(
					List.of(
							environment.command("parec").toString(),
							"--device=" + device,
							"--raw",
							"--format=s16le",
							"--rate=" + AUDIO_RATE,
							"--channels=" + AUDIO_CHANNELS,
							"--latency-msec=" + LATENCY_MSEC),
					new SpawnOptions(false, true, false));
		} catch (IOException error) {
			CosmicProcessIo.closeQuietly(output);
			throw new IllegalStateException("Could not start parec for " + device, error);
		}
		AudioTrack track = new AudioTrack(process, role, file, output);
		Thread.ofPlatform().daemon().name("gravaai-audio-" + role.wire())
				.start(() -> drainAudio(process, track, pauseState, micGate));
		processIo.onExit(process).thenRun(track::completeWithoutData);
		LOG.log(System.Logger.Level.INFO, "Recording audio from " + device);
		return track;
	}

	/**
	 * Writes every chunk to the track file. While the recording is paused chunks are dropped, like
	 * the video's frames; while the mic gate is muted they are written as silence, so the file
	 * offset keeps matching the capture clock and the timeline never desyncs.
	 */
	private static void drainAudio(SpawnedProcess process, AudioTrack track, PauseState pauseState,
			@Nullable MicGate micGate) {
		byte[] buffer = new byte[8192];
		InputStream stdout = process.stdout();
		if (stdout == null) {
			LOG.log(System.Logger.Level.WARNING, "Audio capture has no stdout pipe");
			track.completeWithoutData();
			return;
		}
		boolean gated = micGate != null;
		try (InputStream in = stdout) {
			int read;
			while ((read = in.read(buffer)) != -1) {
				if (track.awaitingFirstData()) {
					track.markFirstData(System.currentTimeMillis() - (long) (read / AUDIO_BYTES_PER_MS));
				}
				if (!pauseState.paused().get()) {
					if (gated && micGate != null && micGate.isMuted()) {
						Arrays.fill(buffer, 0, read, (byte) 0);
					}
					track.file().write(buffer, 0, read);
				}
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Audio capture ended: " + error);
		} finally {
			track.completeWithoutData();
		}
	}

	/** Stops every track and closes its file. */
	void killAll(List<AudioTrack> audio) {
		for (AudioTrack track : audio) {
			track.process().destroy();
			CosmicProcessIo.closeQuietly(track.file());
		}
	}

	/** True when the track file holds more than a header's worth of samples. */
	static boolean hasAudioData(Path file) {
		try {
			// A WAV header alone is 44 bytes.
			return Files.size(file) > 1024;
		} catch (IOException error) {
			return false;
		}
	}
}
