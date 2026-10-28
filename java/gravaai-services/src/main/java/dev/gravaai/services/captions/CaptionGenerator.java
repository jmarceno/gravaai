package dev.gravaai.services.captions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import dev.gravaai.services.meeting.CancelSignal;
import dev.gravaai.services.meeting.ports.ProcessRunnerPort;

/**
 * Port of {@code generateAutoCaptionsFromVideo} for one source (electron/ipc/captions/generate.ts):
 * extract 16 kHz mono WAV with the bundled ffmpeg CLI, run whisper with today's args, read the JSON
 * timing (SRT fallback), detect silence and re-segment. Multi-track microphone/system merging lands
 * with the controller increment.
 */
public final class CaptionGenerator {

	private static final Duration SILENCE_TIMEOUT = Duration.ofMinutes(5);

	/** Everything needed for one generation. */
	public record Options(
			String videoPath,
			Path whisperExecutablePath,
			Path modelPath,
			String language,
			List<CaptionAudioCandidate> candidates) {
	}

	/** Generated cues plus the label of the audio source that produced them. */
	public record Result(List<CaptionCue> cues, String audioSourceLabel) {
	}

	private final ProcessRunnerPort processes;
	private final Path ffmpeg;
	private final Path tempRoot;

	public CaptionGenerator(ProcessRunnerPort processes, Path ffmpeg, Path tempRoot) {
		this.processes = processes;
		this.ffmpeg = ffmpeg;
		this.tempRoot = tempRoot;
	}

	/** Runs the full pipeline; throws {@link CancelSignal.CancelledException} on cancellation. */
	public Result generate(Options options, IntConsumer progress, CancelSignal signal) {
		CancelSignal cancel = signal == null ? CancelSignal.NONE : signal;
		Path whisper = CaptionWhisper.resolveExecutable(options.whisperExecutablePath(), processes);
		Path model = options.modelPath().toAbsolutePath().normalize();
		if (!Files.isReadable(model)) {
			throw new IllegalArgumentException("Whisper model is not readable: " + model);
		}
		Path work = createTempDirectory();
		try {
			Path wav = work.resolve("audio.wav");
			Path outputBase = work.resolve("out");
			List<CaptionAudioCandidate> candidates = options.candidates() == null
					|| options.candidates().isEmpty()
							? List.of(new CaptionAudioCandidate(options.videoPath(), "recording"))
							: options.candidates();
			CaptionAudioCandidate audioSource = new CaptionAudioExtraction(processes)
					.extract(ffmpeg, wav, candidates, CaptionGenerator::sourceDelayMs);
			cancel.throwIfCancelled();
			CaptionWhisper.Result whisperResult = new CaptionWhisper(processes).transcribe(
					whisper, model, wav, outputBase, options.language(), progress);
			cancel.throwIfCancelled();
			List<CaptionCue> cues = CaptionOutput.readWhisperCaptionOutput(
					whisperResult.outputBase(), whisperResult.jsonEnabled());
			List<CaptionCue> segmented = segment(cues, wav);
			return new Result(segmented, audioSource.label());
		} finally {
			deleteQuietly(work);
		}
	}

	/**
	 * Whisper cues run sentences together and don't break on pauses: re-segment them into one
	 * caption per sentence/phrase from Whisper's word stream, backed by ffmpeg
	 * {@code silencedetect}. An empty result is valid; only a failure falls back to raw cues.
	 */
	private List<CaptionCue> segment(List<CaptionCue> cues, Path wav) {
		try {
			List<CaptionSilence.SilenceInterval> silences = detectSilence(wav);
			return CaptionSegment.segmentCuesIntoPhrases(cues, silences, null);
		} catch (RuntimeException error) {
			return cues;
		}
	}

	/** Sidecar audio starts after the video; other sources are aligned already. */
	private static long sourceDelayMs(CaptionAudioCandidate candidate) {
		if (!candidate.label().endsWith("audio sidecar")) {
			return 0;
		}
		Long delay = dev.gravaai.services.recording.RecordingDiagnostics
				.getCompanionAudioStartDelayMs(candidate.path());
		return delay == null ? 0 : delay;
	}

	/**
	 * {@code resolveCaptionAudioCandidates}: microphone sidecar, the recording, the remaining
	 * sidecars, then the linked webcam recording (duplicates removed).
	 */
	public static List<CaptionAudioCandidate> resolveCaptionAudioCandidates(String videoPath,
			String webcamPath) {
		List<CaptionAudioCandidate> candidates = new java.util.ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		java.util.function.BiConsumer<String, String> push = (path, label) -> {
			if (path == null || path.isBlank()) {
				return;
			}
			String normalized = Path.of(path).toAbsolutePath().normalize().toString();
			if (seen.add(normalized)) {
				candidates.add(new CaptionAudioCandidate(normalized, label));
			}
		};
		List<CaptionAudioCandidate> companions = CaptionAudioCandidates
				.getCaptionCompanionAudioCandidates(dev.gravaai.services.recording
						.RecordingDiagnostics.getUsableCompanionAudioCandidates(videoPath).stream()
						.map(found -> new CompanionAudioCandidate(found.platform(),
								found.systemPath(), found.micPath(), found.usablePaths()))
						.toList());
		for (CaptionAudioCandidate companion : companions) {
			if (MICROPHONE_SIDECAR.equals(companion.label())) {
				push.accept(companion.path(), companion.label());
			}
		}
		push.accept(videoPath, "recording");
		for (CaptionAudioCandidate companion : companions) {
			push.accept(companion.path(), companion.label());
		}
		push.accept(webcamPath, "linked webcam recording");
		return List.copyOf(candidates);
	}

	private static final String MICROPHONE_SIDECAR = "microphone audio sidecar";
	private static final String SYSTEM_SIDECAR = "system audio sidecar";

	/**
	 * {@code generateAutoCaptionsFromVideo}: with a microphone sidecar the microphone and the
	 * system/secondary audio are transcribed independently (simultaneous voices confuse
	 * recognition) and merged; otherwise one pass over the candidates.
	 */
	public Result generateFromVideo(Options options, IntConsumer progress, CancelSignal signal) {
		List<CaptionAudioCandidate> candidates = options.candidates() == null
				|| options.candidates().isEmpty()
						? resolveCaptionAudioCandidates(options.videoPath(), null)
						: options.candidates();
		List<CaptionAudioCandidate> microphone = candidates.stream()
				.filter(source -> MICROPHONE_SIDECAR.equals(source.label())).toList();
		List<CaptionAudioCandidate> system = candidates.stream()
				.filter(source -> SYSTEM_SIDECAR.equals(source.label())).toList();
		List<CaptionAudioCandidate> secondary = candidates.stream()
				.filter(source -> !microphone.contains(source) && !system.contains(source))
				.toList();
		List<CaptionAudioCandidate> systemAndSecondary = new java.util.ArrayList<>(system);
		systemAndSecondary.addAll(secondary);
		if (microphone.isEmpty()) {
			return generate(withCandidates(options, systemAndSecondary), progress, signal);
		}
		List<CaptionCue> micCues = transcribeTrack(options, microphone, progress, signal);
		List<CaptionCue> systemCues = transcribeTrack(options, systemAndSecondary, progress,
				signal);
		if (micCues == null && systemCues == null) {
			throw new CaptionAudioExtraction.NoCaptionAudioException(
					"No audio could be extracted from the recording.");
		}
		return new Result(CaptionMerge.mergeCaptionSources(
				micCues == null ? List.of() : micCues, systemCues == null ? List.of() : systemCues),
				"microphone and system audio");
	}

	private List<CaptionCue> transcribeTrack(Options options, List<CaptionAudioCandidate> sources,
			IntConsumer progress, CancelSignal signal) {
		if (sources.isEmpty()) {
			return null;
		}
		try {
			return generate(withCandidates(options, sources), progress, signal).cues();
		} catch (CaptionAudioExtraction.NoCaptionAudioException noAudio) {
			return null;
		}
	}

	private static Options withCandidates(Options options, List<CaptionAudioCandidate> sources) {
		return new Options(options.videoPath(), options.whisperExecutablePath(),
				options.modelPath(), options.language(), List.copyOf(sources));
	}

	private List<CaptionSilence.SilenceInterval> detectSilence(Path wav) {
		List<String> command = List.of(
				ffmpeg.toString(),
				"-hide_banner",
				"-nostats",
				"-i",
				wav.toString(),
				"-af",
				"silencedetect=noise=" + CaptionSilence.SILENCE_NOISE_DB + "dB:d="
						+ CaptionSilence.SILENCE_DETECT_MIN_S,
				"-f",
				"null",
				"-");
		try {
			ProcessRunnerPort.ProcessResult result =
					processes.run(command, SILENCE_TIMEOUT, Map.of());
			return CaptionSilence.parseSilenceIntervals(result.stderr());
		} catch (IOException error) {
			throw new IllegalStateException("silencedetect failed: " + error, error);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted during silencedetect.", interrupted);
		}
	}

	private Path createTempDirectory() {
		try {
			Files.createDirectories(tempRoot);
			return Files.createTempDirectory(tempRoot, "gravaai-captions-");
		} catch (IOException error) {
			throw new IllegalStateException("Could not create the caption temp directory.", error);
		}
	}

	private static void deleteQuietly(Path directory) {
		try (var paths = Files.walk(directory)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Best effort, like Promise.allSettled(rm).
				}
			});
		} catch (IOException ignored) {
			// Already gone.
		}
	}
}
