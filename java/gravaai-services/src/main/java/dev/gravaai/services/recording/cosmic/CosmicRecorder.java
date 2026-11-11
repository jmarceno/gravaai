package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import dev.gravaai.media.export.VideoEncoderProbe;
import dev.gravaai.services.recording.CosmicOutput;
import dev.gravaai.services.recording.CosmicSession;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioInput;
import dev.gravaai.services.recording.CosmicSession.CosmicMux;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxInput;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxResult;
import dev.gravaai.services.recording.CosmicSession.MeetingInfo;
import dev.gravaai.services.recording.CosmicSources;
import dev.gravaai.services.recording.CursorTelemetrySource;
import dev.gravaai.services.recording.KeystrokeSource;
import dev.gravaai.services.recording.MicNoiseReduction;
import dev.gravaai.services.recording.RecordingEvents;
import dev.gravaai.services.recording.RecordingFrameRate;
import dev.gravaai.services.recording.RecordingQuality;
import dev.gravaai.services.recording.cosmic.CosmicRecording.AudioTrack;
import dev.gravaai.services.recording.cosmic.CosmicRecording.CaptureFormat;
import dev.gravaai.services.recording.cosmic.CosmicRecording.PauseState;
import dev.gravaai.services.recording.cosmic.CosmicRecording.VideoCapture;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.SpawnedProcess;
import dev.gravaai.services.sources.SelectedSource;

/**
 * Native screen recording for Wayland compositors with ext-image-copy-capture (COSMIC), a
 * line-by-line port of {@code electron/ipc/recording/cosmic.ts} (plan §5.6).
 *
 * <p>The xdg-desktop-portal screencast shows stale browser content after tab switches and stalls
 * on rotated outputs, so video comes straight from the compositor and audio from PulseAudio
 * (parec), then everything is muxed.
 *
 * <p>This class is the session state machine: it owns the active recording and decides when each
 * stage runs. The stages themselves live next to it —
 * {@link CosmicVideoPipeline} (capture to encoder),
 * {@link CosmicAudioTracks} (one parec per device),
 * {@link PulseAudioSources} (source selection),
 * {@link CosmicMuxer} (the stop-time mux),
 * {@link CosmicSessionWatch} (manifest and the disk/capture watchdogs),
 * {@link CosmicTelemetryWriter} (cursor and key sidecars) and
 * {@link CosmicOutputPicker} (output list and click-to-pick).
 */
public final class CosmicRecorder implements CosmicMux, AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	/** Audio captured with a recording: one parec per role. */
	public record RecordingOptions(
			boolean capturesSystemAudio,
			boolean capturesMicrophone,
			@Nullable String microphoneDeviceId,
			@Nullable String microphoneLabel,
			boolean keepSpeakerTracks) {
	}

	/**
	 * {@code { success, message? }} of the start requests.
	 *
	 * @param message the failure message, or null when the request succeeded
	 */
	public record StartResult(boolean success, @Nullable String message) {

		public static StartResult ok() {
			return new StartResult(true, null);
		}

		public static StartResult failed(String message) {
			return new StartResult(false, message);
		}
	}

	/** The stop result; {@code meeting} is set for meeting recordings (speaker tracks kept). */
	public record StopResult(boolean success, @Nullable String path, @Nullable String error,
			@Nullable MeetingInfo meeting) {

		public static StopResult ok(String path, @Nullable MeetingInfo meeting) {
			return new StopResult(true, path, null, meeting);
		}

		public static StopResult failed(String error) {
			return new StopResult(false, null, error, null);
		}
	}

	private final Supplier<Path> recordingsDir;
	private final RecordingProcesses processes;
	private final Supplier<MicNoiseReduction> micNoiseReductionSetting;
	private final Supplier<RecordingFrameRate> frameRateSetting;
	private final Supplier<RecordingQuality> videoQualitySetting;

	private final CosmicProcessIo processIo;
	private final CosmicVideoPipeline video;
	private final CosmicAudioTracks audioTracks;
	private final CosmicMuxer muxer;
	private final CosmicDiskGuard diskGuard;
	private final CosmicSessionWatch sessionWatch;
	private final CosmicTelemetryWriter telemetry;
	private final CosmicOutputPicker outputPicker;

	private @Nullable CosmicRecording active;

	public CosmicRecorder(
			Supplier<Path> recordingsDir,
			RecordingProcesses processes,
			RecordingEnvironment environment,
			RecordingEvents events,
			CursorTelemetrySource cursorTelemetry,
			KeystrokeSource keystrokes,
			Supplier<MicNoiseReduction> micNoiseReductionSetting,
			Supplier<RecordingFrameRate> frameRateSetting,
			Supplier<RecordingQuality> videoQualitySetting) {
		this(recordingsDir, processes, environment, events, cursorTelemetry, keystrokes,
				micNoiseReductionSetting, frameRateSetting, videoQualitySetting,
				VideoEncoderProbe.bundled());
	}

	/** Test seam: run the encoder a fixed probe answers with instead of this machine's GPU. */
	CosmicRecorder(
			Supplier<Path> recordingsDir,
			RecordingProcesses processes,
			RecordingEnvironment environment,
			RecordingEvents events,
			CursorTelemetrySource cursorTelemetry,
			KeystrokeSource keystrokes,
			Supplier<MicNoiseReduction> micNoiseReductionSetting,
			Supplier<RecordingFrameRate> frameRateSetting,
			Supplier<RecordingQuality> videoQualitySetting,
			VideoEncoderProbe encoderProbe) {
		this.recordingsDir = recordingsDir;
		this.processes = processes;
		this.micNoiseReductionSetting = micNoiseReductionSetting;
		this.frameRateSetting = frameRateSetting;
		this.videoQualitySetting = videoQualitySetting;
		this.processIo = new CosmicProcessIo(processes);
		PulseAudioSources pulseSources = new PulseAudioSources(environment, processIo);
		this.video = new CosmicVideoPipeline(processes, environment, processIo, encoderProbe);
		this.audioTracks = new CosmicAudioTracks(processes, environment, pulseSources, processIo);
		this.muxer = new CosmicMuxer(environment, processes, processIo.pipeDrains());
		this.diskGuard = new CosmicDiskGuard();
		this.sessionWatch = new CosmicSessionWatch(events, diskGuard);
		this.telemetry = new CosmicTelemetryWriter(cursorTelemetry, keystrokes);
		this.outputPicker = new CosmicOutputPicker(environment, processes, processIo, diskGuard);
	}

	/** Timestamp of the recording in progress, so recovery leaves its files alone. */
	public synchronized @Nullable Long getActiveCosmicTimestamp() {
		return active == null ? null : active.timestamp();
	}

	/**
	 * Wall clock of the screen video's first frame for the recording in progress (the webcam
	 * track anchors to it), or null when nothing is recording.
	 */
	public synchronized @Nullable Long getActiveCosmicStartedAtMs() {
		return active == null ? null : active.startedAtMs();
	}

	public synchronized boolean isCosmicRecordingActive() {
		return active != null;
	}

	/**
	 * The encoder label of the recording in progress ({@code h264_vaapi (GPU)}, {@code libx264
	 * (CPU)}), or null when nothing records (or the recording is audio-only).
	 */
	public synchronized @Nullable String activeEncoderName() {
		if (active == null || active.video() == null) {
			return null;
		}
		return active.video().encoderSpec().label();
	}

	// ── Start ──────────────────────────────────────────────────────────────

	/** Starts a screen recording of one COSMIC output. */
	public synchronized StartResult startCosmicRecording(
			SelectedSource source, RecordingOptions options) {
		if (active != null) {
			return StartResult.failed("A screen recording is already active.");
		}
		String outputName = CosmicSources.outputNameForSourceId(source == null ? null : source.id());
		Path recordingsDir = this.recordingsDir.get();
		String lowDisk = CosmicDiskGuard.checkFreeSpaceToStart(recordingsDir);
		if (lowDisk != null) {
			return StartResult.failed(lowDisk);
		}
		long timestamp = System.currentTimeMillis();
		Path outputPath = recordingsDir.resolve("recording-" + timestamp + ".mp4");
		Path videoPath = recordingsDir.resolve(".recording-" + timestamp + ".video.mkv");

		PauseState pauseState = PauseState.running();
		List<AudioTrack> audio = audioTracks.start(recordingsDir, timestamp,
				new CosmicAudioTracks.Options(
						options.capturesSystemAudio(), options.capturesMicrophone(),
						options.microphoneLabel()),
				pauseState);
		int fps = currentCaptureFps();
		SpawnedProcess capture;
		try {
			capture = video.startCapture(outputName, fps);
		} catch (IOException error) {
			audioTracks.killAll(audio);
			return StartResult.failed(String.valueOf(error));
		}
		CaptureFormat format;
		try {
			format = video.readCaptureFormat(capture);
		} catch (IOException error) {
			capture.destroy();
			audioTracks.killAll(audio);
			return StartResult.failed(
					error.getMessage() == null ? String.valueOf(error) : error.getMessage());
		}
		// The helper prints FORMAT right after grabbing the first frame and its frame clock
		// starts there.
		long videoStartedAtMs = System.currentTimeMillis();

		// The encoder is chosen here (hardware when this machine can run one) and, if it dies
		// immediately, restarted on libx264 before a single frame is lost.
		VideoCapture videoCapture;
		try {
			videoCapture = video.startVideo(capture, format, videoPath, fps, currentVideoQuality());
		} catch (IOException error) {
			capture.destroy();
			audioTracks.killAll(audio);
			return StartResult.failed(String.valueOf(error));
		}
		SpawnedProcess encoder = videoCapture.encoder();
		video.drainPipeline(capture, encoder);
		video.startCopying(capture, encoder);

		CosmicRecording recording = new CosmicRecording(
				videoCapture, audio, outputPath, videoStartedAtMs, options.keepSpeakerTracks(),
				pauseState, currentMicNoiseReduction(), timestamp,
				CosmicSession.sessionManifestPath(recordingsDir, timestamp));
		beginRecordingSession(recording);
		sessionWatch.watchCaptureEnd(recording, capture, this::isActive);
		LOG.log(System.Logger.Level.INFO, "Recording " + outputName + " " + format.width() + "x"
				+ format.height() + " transform " + format.transform() + " encoded with "
				+ videoCapture.encoderSpec().label() + " to " + outputPath);
		return StartResult.ok();
	}

	/** Records only audio (a meeting without the screen) into an .m4a. */
	public synchronized StartResult startCosmicAudioRecording(RecordingOptions options) {
		if (active != null) {
			return StartResult.failed("A recording is already active.");
		}
		if (!options.capturesMicrophone() && !options.capturesSystemAudio()) {
			return StartResult.failed("Choose a microphone or system audio to record.");
		}
		Path recordingsDir = this.recordingsDir.get();
		String lowDisk = CosmicDiskGuard.checkFreeSpaceToStart(recordingsDir);
		if (lowDisk != null) {
			return StartResult.failed(lowDisk);
		}
		long timestamp = System.currentTimeMillis();
		PauseState pauseState = PauseState.running();
		List<AudioTrack> audio = audioTracks.start(recordingsDir, timestamp,
				new CosmicAudioTracks.Options(
						options.capturesSystemAudio(), options.capturesMicrophone(),
						options.microphoneLabel()),
				pauseState);
		List<Long> starts = new ArrayList<>();
		for (AudioTrack track : audio) {
			if (!track.awaitingFirstData()) {
				starts.add(track.startedAtMs());
			}
		}
		if (starts.isEmpty()) {
			audioTracks.killAll(audio);
			return StartResult.failed("No audio device produced any sound data.");
		}
		CosmicRecording recording = new CosmicRecording(
				null, audio, recordingsDir.resolve("recording-" + timestamp + ".m4a"),
				starts.stream().min(Long::compare).orElse(timestamp), options.keepSpeakerTracks(),
				pauseState, currentMicNoiseReduction(), timestamp,
				CosmicSession.sessionManifestPath(recordingsDir, timestamp));
		beginRecordingSession(recording);
		LOG.log(System.Logger.Level.INFO, "Recording audio only to " + recording.outputPath());
		return StartResult.ok();
	}

	/** Publishes the recording as active and starts the manifest/disk bookkeeping. */
	private void beginRecordingSession(CosmicRecording recording) {
		synchronized (this) {
			active = recording;
		}
		sessionWatch.begin(recording, recordingsDir.get(), this::isActive);
	}

	/** True while {@code recording} is still the one in progress. */
	private synchronized boolean isActive(CosmicRecording recording) {
		return active == recording;
	}

	// ── Pause / resume ─────────────────────────────────────────────────────

	public synchronized StartResult pauseCosmicRecording() {
		CosmicRecording recording = active;
		if (recording == null) {
			return StartResult.failed("No screen recording is active.");
		}
		if (!recording.isPaused()) {
			if (recording.video() != null) {
				CosmicProcessIo.writeCommand(recording.video().capture(), "pause");
			}
			recording.pauseState().paused().set(true);
		}
		return StartResult.ok();
	}

	public synchronized StartResult resumeCosmicRecording() {
		CosmicRecording recording = active;
		if (recording == null) {
			return StartResult.failed("No screen recording is active.");
		}
		if (recording.isPaused()) {
			if (recording.video() != null) {
				CosmicProcessIo.writeCommand(recording.video().capture(), "resume");
			}
			recording.pauseState().paused().set(false);
		}
		return StartResult.ok();
	}

	// ── Stop / discard ─────────────────────────────────────────────────────

	public synchronized StopResult stopCosmicRecording() {
		CosmicRecording recording = active;
		if (recording == null) {
			return StopResult.failed("No screen recording is active.");
		}
		active = null;
		sessionWatch.stopDiskCheck();

		VideoCapture video = recording.video();
		if (video != null) {
			CosmicProcessIo.writeCommand(video.capture(), "stop");
			CosmicProcessIo.closeStdin(video.capture());
		}
		for (AudioTrack track : recording.audio()) {
			track.process().destroy();
		}
		if (video != null) {
			try {
				video.capture().waitFor();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
			telemetry.persist(recording.outputPath().toString(), recording.startedAtMs());
		}
		int encoderCode = reapStoppedProcesses(recording, video);
		if (encoderCode != 0) {
			return StopResult.failed("The video encoder exited with code " + encoderCode + ".");
		}

		List<CosmicAudioInput> audioTracks = new ArrayList<>();
		for (AudioTrack track : recording.audio()) {
			if (!track.awaitingFirstData() && CosmicAudioTracks.hasAudioData(track.path())) {
				audioTracks.add(new CosmicAudioInput(track.role(), track.path().toString(),
						track.startedAtMs()));
			} else {
				LOG.log(System.Logger.Level.WARNING, "No audio captured in " + track.path());
			}
		}
		if (video == null && audioTracks.isEmpty()) {
			removeTempFiles(recording);
			return StopResult.failed("No audio was captured.");
		}

		// On failure the temp files and manifest stay, so the next launch offers recovery.
		CosmicMuxResult mux = mux(new CosmicMuxInput(
				video == null ? null : video.videoPath().toString(),
				List.copyOf(audioTracks),
				recording.startedAtMs(),
				recording.outputPath().toString(),
				recording.keepSpeakerTracks(),
				recording.micNoiseReduction(),
				false));
		if (!mux.success()) {
			return StopResult.failed(
					Objects.requireNonNull(mux.error(), "failed mux result has no error"));
		}

		removeTempFiles(recording);
		LOG.log(System.Logger.Level.INFO, "Saved " + recording.outputPath());
		return StopResult.ok(
				recording.outputPath().toString(),
				recording.keepSpeakerTracks() ? new MeetingInfo(video != null) : null);
	}

	/**
	 * Waits for the encoder and every audio track, closing the track files, and returns the
	 * encoder's exit code.
	 *
	 * <p>The processes were already told to stop; they are reaped in one scope-shaped executor so a
	 * hung wait on one cannot leave the others unreaped (and no reaper outlives this method).
	 */
	private static int reapStoppedProcesses(CosmicRecording recording, @Nullable VideoCapture video) {
		int encoderCode = 0;
		try (ExecutorService reaper = Executors.newThreadPerTaskExecutor(
				Thread.ofVirtual().name("gravaai-record-reap-", 0).factory())) {
			Future<Integer> encoderExit = video == null ? null
					: reaper.submit(() -> {
						try {
							return video.encoder().waitFor();
						} catch (InterruptedException error) {
							Thread.currentThread().interrupt();
							return -1;
						}
					});
			List<Future<Void>> audioExits = new ArrayList<>();
			for (AudioTrack track : recording.audio()) {
				audioExits.add(reaper.submit(() -> {
					try {
						track.process().waitFor();
					} catch (InterruptedException error) {
						Thread.currentThread().interrupt();
					}
					CosmicProcessIo.closeQuietly(track.file());
					return null;
				}));
			}
			for (Future<Void> audioExit : audioExits) {
				audioExit.get();
			}
			if (encoderExit != null) {
				try {
					encoderCode = encoderExit.get();
				} catch (RuntimeException error) {
					// The reaper already folded a failed wait into the exit code.
					encoderCode = -1;
				}
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException error) {
			LOG.log(System.Logger.Level.WARNING, "Reaping the stopped recording failed: " + error);
		}
		return encoderCode;
	}

	/** Stops the processes without producing a file. */
	public synchronized void discardCosmicRecording() {
		CosmicRecording recording = active;
		if (recording == null) {
			return;
		}
		active = null;
		sessionWatch.stopDiskCheck();
		VideoCapture video = recording.video();
		if (video != null) {
			video.capture().destroy();
			video.encoder().destroy();
		}
		audioTracks.killAll(recording.audio());
		if (video != null) {
			try {
				video.capture().waitFor();
				video.encoder().waitFor();
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
		}
		removeTempFiles(recording);
	}

	private static void removeTempFiles(CosmicRecording recording) {
		for (Path file : recording.tempFiles()) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "Could not remove temp file " + file + ": " + error);
			}
		}
	}

	// ── Mux ────────────────────────────────────────────────────────────────

	/**
	 * Muxes the temp files into the final recording, plus the speaker tracks for meetings. Used by
	 * a normal stop and by crash recovery.
	 */
	@Override
	public CosmicMuxResult mux(CosmicMuxInput input) {
		return muxer.mux(input);
	}

	// ── Outputs ────────────────────────────────────────────────────────────

	/** Lists the COSMIC outputs by running {@code gravaai-wayland-capture --list}. */
	public List<CosmicOutput> listCosmicOutputs() throws IOException {
		return outputPicker.listOutputs();
	}

	/**
	 * Waits for the next mouse click anywhere and resolves with the output under it.
	 *
	 * @return a future with the picked output, or null when nothing was picked (timeout, no click,
	 *     helper failure)
	 */
	public CompletableFuture<@Nullable CosmicOutput> waitForCosmicOutputClick(long timeoutMs) {
		return outputPicker.waitForClick(timeoutMs);
	}

	// ── Internals ──────────────────────────────────────────────────────────

	private MicNoiseReduction currentMicNoiseReduction() {
		MicNoiseReduction level = micNoiseReductionSetting.get();
		return level == null ? MicNoiseReduction.DEFAULT : level;
	}

	/** The frame rate the capture helper and encoder run at, read at each start. */
	private int currentCaptureFps() {
		RecordingFrameRate rate = frameRateSetting.get();
		return (rate == null ? RecordingFrameRate.DEFAULT : rate).fps();
	}

	/** The encoder quality, read at each start. */
	private RecordingQuality currentVideoQuality() {
		RecordingQuality quality = videoQualitySetting.get();
		return quality == null ? RecordingQuality.DEFAULT : quality;
	}

	/** Stops the watchdog and the process executors (app shutdown). */
	@Override
	public void close() {
		sessionWatch.close();
		diskGuard.close();
		processIo.close();
	}
}
