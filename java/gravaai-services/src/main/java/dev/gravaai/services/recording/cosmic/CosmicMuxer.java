package dev.gravaai.services.recording.cosmic;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import dev.gravaai.services.recording.CosmicSession.CosmicAudioInput;
import dev.gravaai.services.recording.CosmicSession.CosmicAudioRole;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxInput;
import dev.gravaai.services.recording.CosmicSession.CosmicMuxResult;
import dev.gravaai.services.recording.CosmicSession.CosmicMux;
import dev.gravaai.services.recording.MicNoiseReduction;
import dev.gravaai.services.recording.MicNoiseReductionFilters;
import dev.gravaai.services.recording.RecordingAudioManifest;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;

/**
 * The stop-time mux: raw audio tracks and the encoded video are combined into the final file, and
 * meeting recordings also get their per-speaker tracks.
 *
 * <p>Everything here is ffmpeg argument building plus process supervision, split out of the
 * recorder so the pipeline and the state machine stay readable.
 */
final class CosmicMuxer implements CosmicMux {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	private static final int AUDIO_RATE = 48_000;
	private static final int AUDIO_CHANNELS = 2;
	private static final String AUDIO_ENCODING = "192k";
	private static final String SPEAKER_ENCODING = "48k";

	private final RecordingEnvironment environment;
	private final RecordingProcesses processes;
	private final ExecutorService pipeDrains;

	CosmicMuxer(RecordingEnvironment environment, RecordingProcesses processes,
			ExecutorService pipeDrains) {
		this.environment = environment;
		this.processes = processes;
		this.pipeDrains = pipeDrains;
	}

	/** One track as the filter builders see it. */
	record TrackRef(CosmicAudioRole role, long startedAtMs) {
	}

	/** Muxes the temp files into the final recording, plus the speaker tracks for meetings. */
	@Override
	public CosmicMuxResult mux(CosmicMuxInput input) {
		// Microphone noise reduction runs the speech denoiser over the raw capture first; the
		// mux (and a meeting's speaker track) then reads the cleaned capture in its place.
		java.util.Map<String, Path> cleanedMic = denoiseMicTracks(input);
		try {
			return mux(input, cleanedMic);
		} finally {
			for (Path cleaned : cleanedMic.values()) {
				try {
					java.nio.file.Files.deleteIfExists(cleaned);
				} catch (IOException error) {
					LOG.log(System.Logger.Level.DEBUG, "could not delete " + cleaned);
				}
			}
		}
	}

	private CosmicMuxResult mux(CosmicMuxInput input, java.util.Map<String, Path> cleanedMic) {
		List<String> micFilters = cleanedMic.isEmpty()
				? prepareMicFilters(input.micNoiseReduction())
				// Already denoised: only the record-time rumble filter is left to apply.
				: List.of(MicNoiseReductionFilters.RECORDING_HIGH_PASS);
		List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y"));
		if (input.videoPath() != null) {
			// A video that was never finalised may end in a truncated cluster.
			if (input.recovering()) {
				args.addAll(List.of("-fflags", "+genpts+discardcorrupt", "-err_detect", "ignore_err"));
			}
			args.add("-i");
			args.add(input.videoPath());
		}
		int firstAudioInput = input.videoPath() != null ? 1 : 0;
		for (CosmicAudioInput track : input.audio()) {
			args.addAll(rawTrackInput(sourceOf(track, cleanedMic)));
		}
		if (input.videoPath() != null) {
			args.addAll(List.of("-map", "0:v"));
		}
		if (!input.audio().isEmpty()) {
			for (CosmicAudioInput track : input.audio()) {
				LOG.log(System.Logger.Level.INFO,
						Path.of(track.path()).getFileName() + " align "
								+ alignTrackFilter(track.startedAtMs(), input.startedAtMs()));
			}
			args.addAll(List.of("-filter_complex",
					buildMixFilter(trackRefs(input.audio()), input.startedAtMs(), firstAudioInput, micFilters),
					"-map", "[a]"));
		}
		if (input.videoPath() != null) {
			args.addAll(List.of("-c:v", "copy"));
		}
		if (!input.audio().isEmpty()) {
			args.addAll(List.of("-c:a", "aac", "-b:a", AUDIO_ENCODING));
		}
		args.add("-movflags");
		args.add("+faststart");
		if (input.videoPath() != null) {
			args.add("-shortest");
		}
		args.add(input.outputPath());

		// Speaker tracks are transcription input only: mono, aligned like the mix, never played
		// back (listeners always get the mixed track above).
		List<FfmpegRun> speakerRuns = new ArrayList<>();
		if (input.keepSpeakerTracks()) {
			for (CosmicAudioInput track : input.audio()) {
				speakerRuns.add(runFfmpeg(speakerArgs(input, track, micFilters, cleanedMic)));
			}
		}

		// With microphone noise reduction on, the recording's own audio carries the cleaned
		// microphone. The capture tracks are kept untouched next to it, so the editor can start
		// from the real microphone and the noise reduction stays reversible.
		List<FfmpegRun> sidecarRuns = new ArrayList<>();
		List<String> sidecarTracks = startCaptureSidecars(input, sidecarRuns);

		FfmpegRun mux = runFfmpeg(args);
		int muxCode = waitFor(mux);
		String muxStderr = mux.stderr().join();
		for (FfmpegRun speaker : speakerRuns) {
			if (waitFor(speaker) != 0) {
				LOG.log(System.Logger.Level.WARNING, "Speaker track failed: " + speaker.stderr().join());
			}
		}
		boolean sidecarsOk = !sidecarTracks.isEmpty();
		for (FfmpegRun sidecar : sidecarRuns) {
			if (waitFor(sidecar) != 0) {
				sidecarsOk = false;
				LOG.log(System.Logger.Level.WARNING,
						"Capture sidecar failed: " + sidecar.stderr().join());
			}
		}
		finishCaptureSidecars(input, sidecarTracks, sidecarsOk && muxCode == 0);
		if (muxCode != 0) {
			return CosmicMuxResult.err(muxStderr.isEmpty()
					? "Muxing failed with code " + muxCode + "."
					: muxStderr);
		}
		return CosmicMuxResult.ok();
	}

	/**
	 * Starts the ffmpeg runs that keep the capture tracks next to the recording: the untouched
	 * microphone as lossless WAV and the system audio as AAC, both lined up like the mix. Only
	 * for a screen recording made with microphone noise reduction — without it the recording's
	 * own audio already is the untouched capture.
	 *
	 * @return the roles being written ({@code "mic"}, {@code "system"}), empty when none are
	 */
	private List<String> startCaptureSidecars(CosmicMuxInput input, List<FfmpegRun> runs) {
		if (input.micNoiseReduction() == MicNoiseReduction.OFF || input.videoPath() == null) {
			return List.of();
		}
		CosmicAudioInput mic = firstTrack(input, CosmicAudioRole.MIC);
		if (mic == null) {
			return List.of();
		}
		Path recording = Path.of(input.outputPath());
		List<String> tracks = new ArrayList<>();
		int micChannels = AUDIO_CHANNELS;
		try {
			// A mono microphone opened as stereo is kept as one channel: half the disk.
			if (dev.gravaai.media.denoise.DenoisedAudio.isDualMono(Path.of(mic.path()))) {
				micChannels = 1;
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "could not inspect the microphone capture: " + error);
		}
		runs.add(runFfmpeg(sidecarArgs(input, mic, List.of("-ac", String.valueOf(micChannels),
				"-c:a", "pcm_s16le"), RecordingAudioManifest.micSidecar(recording))));
		tracks.add("mic");
		CosmicAudioInput system = firstTrack(input, CosmicAudioRole.SYSTEM);
		if (system != null) {
			runs.add(runFfmpeg(sidecarArgs(input, system, List.of("-c:a", "aac", "-b:a",
					AUDIO_ENCODING), RecordingAudioManifest.systemSidecar(recording))));
			tracks.add("system");
		}
		return List.copyOf(tracks);
	}

	private static List<String> sidecarArgs(CosmicMuxInput input, CosmicAudioInput track,
			List<String> encoding, Path output) {
		List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y"));
		args.addAll(rawTrackInput(Path.of(track.path())));
		// Lined up with the recording's start like the mix, and nothing else: no denoise, no
		// high-pass. This is the capture.
		args.addAll(List.of("-af", alignTrackFilter(track.startedAtMs(), input.startedAtMs())));
		args.addAll(encoding);
		args.add(output.toString());
		return List.copyOf(args);
	}

	/**
	 * Writes the manifest that makes the sidecars count, or removes them. All or nothing: a
	 * manifest promises that the sidecars cover every captured track, so a half-written set must
	 * not be left for the editor to trust.
	 */
	private static void finishCaptureSidecars(CosmicMuxInput input, List<String> tracks,
			boolean ok) {
		if (tracks.isEmpty()) {
			return;
		}
		Path recording = Path.of(input.outputPath());
		if (ok) {
			try {
				new RecordingAudioManifest(tracks, input.micNoiseReduction().wire())
						.write(recording);
				return;
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING,
						"could not write the audio manifest; dropping the sidecars: " + error);
			}
		}
		deleteQuietly(RecordingAudioManifest.micSidecar(recording));
		deleteQuietly(RecordingAudioManifest.systemSidecar(recording));
		deleteQuietly(RecordingAudioManifest.pathFor(recording));
	}

	private static @Nullable CosmicAudioInput firstTrack(CosmicMuxInput input,
			CosmicAudioRole role) {
		for (CosmicAudioInput track : input.audio()) {
			if (track.role() == role) {
				return track;
			}
		}
		return null;
	}

	/** The raw file the mux reads for a track: its cleaned capture when there is one. */
	private static Path sourceOf(CosmicAudioInput track, java.util.Map<String, Path> cleanedMic) {
		Path cleaned = cleanedMic.get(track.path());
		return cleaned != null ? cleaned : Path.of(track.path());
	}

	/**
	 * Runs the speech denoiser over every microphone capture and returns the cleaned files by
	 * the original track path. Empty when noise reduction is off, the build has no denoiser or
	 * any capture failed: the mux then uses the ffmpeg fallback chain for all of them, so a
	 * recording never ends up with one microphone on each engine.
	 */
	private java.util.Map<String, Path> denoiseMicTracks(CosmicMuxInput input) {
		MicNoiseReduction level = input.micNoiseReduction();
		RecordingEnvironment.MicDenoiser denoiser =
				level == MicNoiseReduction.OFF ? null : environment.micDenoiser();
		if (denoiser == null) {
			return java.util.Map.of();
		}
		double dryGain = dev.gravaai.media.denoise.DenoisedAudio.dryGain(switch (level) {
			case OFF -> dev.gravaai.core.project.AudioCleanup.NoiseReduction.OFF;
			case LIGHT -> dev.gravaai.core.project.AudioCleanup.NoiseReduction.LIGHT;
			case BALANCED -> dev.gravaai.core.project.AudioCleanup.NoiseReduction.BALANCED;
			case STRONG -> dev.gravaai.core.project.AudioCleanup.NoiseReduction.STRONG;
		});
		java.util.Map<String, Path> cleaned = new java.util.LinkedHashMap<>();
		for (CosmicAudioInput track : input.audio()) {
			if (track.role() != CosmicAudioRole.MIC) {
				continue;
			}
			Path raw = Path.of(track.path());
			Path output = raw.resolveSibling(raw.getFileName() + ".clean");
			try {
				denoiser.denoise(raw, output, AUDIO_CHANNELS, dryGain);
				cleaned.put(track.path(), output);
			} catch (IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING,
						"speech denoiser failed on the microphone capture; using the basic"
								+ " filter instead: " + error);
				for (Path partial : cleaned.values()) {
					deleteQuietly(partial);
				}
				deleteQuietly(output);
				return java.util.Map.of();
			}
		}
		if (!cleaned.isEmpty()) {
			LOG.log(System.Logger.Level.INFO, "mic noise reduction: " + level.wire()
					+ " (speech denoiser)");
		}
		return cleaned;
	}

	private static void deleteQuietly(Path file) {
		try {
			java.nio.file.Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// A stray temp capture is removed with the recording's temp files.
		}
	}

	/** The per-speaker ffmpeg run for one track, or null when the track needs none. */
	private static List<String> speakerArgs(CosmicMuxInput input, CosmicAudioInput track,
			List<String> micFilters, java.util.Map<String, Path> cleanedMic) {
		List<String> speakerArgs = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y"));
		speakerArgs.addAll(rawTrackInput(sourceOf(track, cleanedMic)));
		speakerArgs.addAll(List.of(
				"-af",
				String.join(",", trackFilterChain(
						new TrackRef(track.role(), track.startedAtMs()), input.startedAtMs(), micFilters)),
				"-ac", "1",
				"-c:a", "libopus",
				"-b:a", SPEAKER_ENCODING,
				speakerTrackPath(input.outputPath(), track.role())));
		return List.copyOf(speakerArgs);
	}

	private List<String> prepareMicFilters(MicNoiseReduction level) {
		// Logs why it fell back when the model is missing.
		Path modelPath = level == MicNoiseReduction.OFF ? null : environment.rnnoiseModel();
		if ((level == MicNoiseReduction.BALANCED || level == MicNoiseReduction.STRONG) && modelPath == null) {
			LOG.log(System.Logger.Level.WARNING,
					"RNNoise model not found; using the light mic filter.");
		}
		LOG.log(System.Logger.Level.INFO, "mic noise reduction: " + level.wire());
		return MicNoiseReductionFilters.buildMicNoiseFilters(
				level, modelPath == null ? null : modelPath.toString());
	}

	private static List<TrackRef> trackRefs(List<CosmicAudioInput> audio) {
		List<TrackRef> tracks = new ArrayList<>();
		for (CosmicAudioInput track : audio) {
			tracks.add(new TrackRef(track.role(), track.startedAtMs()));
		}
		return List.copyOf(tracks);
	}

	private record FfmpegRun(SpawnedProcess process, CompletableFuture<String> stderr) {
	}

	/** Runs the bundled ffmpeg CLI with {@code args} (the arguments only, without the program). */
	private FfmpegRun runFfmpeg(List<String> args) {
		List<String> command = new ArrayList<>(args.size() + 1);
		command.add(environment.ffmpeg().toString());
		command.addAll(args);
		try {
			SpawnedProcess process = processes.spawn(command, new SpawnOptions(false, false, true));
			InputStream errorStream = process.stderr();
			CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> {
				if (errorStream == null) {
					return "";
				}
				try (InputStream in = errorStream) {
					return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
				} catch (IOException error) {
					return "";
				}
			}, pipeDrains);
			return new FfmpegRun(process, stderr);
		} catch (IOException error) {
			throw new IllegalStateException("Could not start ffmpeg", error);
		}
	}

	private static int waitFor(FfmpegRun run) {
		try {
			return run.process().waitFor();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			return -1;
		}
	}

	private static List<String> rawTrackInput(Path file) {
		return List.of(
				"-f", "s16le",
				"-ar", String.valueOf(AUDIO_RATE),
				"-ac", String.valueOf(AUDIO_CHANNELS),
				"-i", file.toString());
	}

	/**
	 * The -filter_complex that lines every audio track up and mixes them into [a].
	 *
	 * @param tracks the tracks in input order
	 * @param referenceMs the recording's reference start
	 * @param firstInputIndex the ffmpeg input index of the first track
	 * @param micFilters the denoise chain for the microphone track
	 */
	static String buildMixFilter(
			List<TrackRef> tracks, long referenceMs, int firstInputIndex, List<String> micFilters) {
		List<String> chains = new ArrayList<>();
		for (int index = 0; index < tracks.size(); index++) {
			TrackRef track = tracks.get(index);
			chains.add("[" + (index + firstInputIndex) + ":a]"
					+ String.join(",", trackFilterChain(track, referenceMs, micFilters))
					+ "[a" + index + "]");
		}
		if (tracks.size() == 1) {
			return chains.get(0).replace("[a0]", "[a]");
		}
		StringBuilder labels = new StringBuilder();
		for (int index = 0; index < tracks.size(); index++) {
			labels.append("[a").append(index).append("]");
		}
		return String.join(";", chains)
				+ ";" + labels + "amix=inputs=" + tracks.size() + ":duration=longest:normalize=0[a]";
	}

	/** Filters for one track, in order: alignment first so timing stays exact, then mic denoise. */
	static List<String> trackFilterChain(TrackRef track, long referenceMs, List<String> micFilters) {
		List<String> filters = new ArrayList<>();
		filters.add(alignTrackFilter(track.startedAtMs(), referenceMs));
		if (track.role() == CosmicAudioRole.MIC) {
			filters.addAll(micFilters);
		}
		return List.copyOf(filters);
	}

	/** ffmpeg filter that lines a raw track up with the recording's reference start. */
	static String alignTrackFilter(long trackStartedAtMs, long referenceMs) {
		long offsetMs = Math.round((double) (trackStartedAtMs - referenceMs));
		return offsetMs > 0
				? "adelay=" + offsetMs + ":all=1"
				: "atrim=start=" + String.format(Locale.ROOT, "%.3f", (-offsetMs) / 1000.0)
						+ ",asetpts=PTS-STARTPTS";
	}

	/** Path of the per-speaker meeting track kept next to a recording. */
	static String speakerTrackPath(String outputPath, CosmicAudioRole role) {
		int slash = Math.max(outputPath.lastIndexOf('/'), outputPath.lastIndexOf('\\'));
		int dot = outputPath.lastIndexOf('.');
		String stem = dot > slash ? outputPath.substring(0, dot) : outputPath;
		return stem + ".meeting-" + role.wire() + ".opus";
	}
}
