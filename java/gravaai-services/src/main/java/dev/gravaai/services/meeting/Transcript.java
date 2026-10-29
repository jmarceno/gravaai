package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transcript extraction, annotation and Markdown conversion. Ported 1:1 from
 * {@code electron/ipc/meeting/transcript.ts}; every constant and branch is intentionally kept.
 */
public final class Transcript {

	/** 80 Hz high-pass kills rumble; speechnorm evens out level before whisper sees the audio. */
	static final String SPEECH_FILTER = "highpass=f=80,speechnorm=e=10:l=1";

	/** A turn spans at most a minute; beyond that a new line starts even with the same speaker. */
	static final long MAX_TURN_SPAN_MS = 60_000;

	static final long WHISPER_SAMPLE_RATE = 16_000;

	private Transcript() {
	}

	/** Finds the per-speaker sidecars next to {@code mediaPath}; absent files become null. */
	public static MeetingAudioInputs resolveMeetingAudioInputs(Path mediaPath) {
		Path micPath = SpeakerTracks.speakerTrackPath(mediaPath, SpeakerRole.MIC);
		Path systemPath = SpeakerTracks.speakerTrackPath(mediaPath, SpeakerRole.SYSTEM);
		return new MeetingAudioInputs(
				mediaPath,
				Files.isRegularFile(micPath) ? micPath : null,
				Files.isRegularFile(systemPath) ? systemPath : null);
	}

	/**
	 * Builds the ffmpeg arguments that extract 16 kHz mono PCM for whisper. Two speaker tracks are
	 * merged into a two-channel WAV so whisper can diarise; otherwise the first audio stream is
	 * used.
	 */
	public static List<String> buildWhisperInputArgs(MeetingAudioInputs inputs, Path wavPath) {
		List<String> base = List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-y");
		if (inputs.mic() != null && inputs.system() != null) {
			String complex = "[0:a]aformat=channel_layouts=mono," + SPEECH_FILTER + "[l];"
					+ "[1:a]aformat=channel_layouts=mono," + SPEECH_FILTER + "[r];"
					+ "[l][r]amerge=inputs=2,aresample=" + WHISPER_SAMPLE_RATE + "[a]";
			List<String> args = new ArrayList<>(base);
			args.add("-i");
			args.add(inputs.mic().toString());
			args.add("-i");
			args.add(inputs.system().toString());
			args.add("-filter_complex");
			args.add(complex);
			args.add("-map");
			args.add("[a]");
			args.add("-c:a");
			args.add("pcm_s16le");
			args.add(wavPath.toString());
			return List.copyOf(args);
		}

		Path source = inputs.mediaPath();
		List<String> args = new ArrayList<>(base);
		args.add("-i");
		args.add(source.toString());
		args.add("-map");
		args.add("0:a:0");
		args.add("-vn");
		args.add("-af");
		args.add(SPEECH_FILTER);
		args.add("-ac");
		args.add("1");
		args.add("-ar");
		args.add(String.valueOf(WHISPER_SAMPLE_RATE));
		args.add("-c:a");
		args.add("pcm_s16le");
		args.add(wavPath.toString());
		return List.copyOf(args);
	}

	/**
	 * whisper.cpp arguments: the model, the 16 kHz WAV, the language, threads, print progress,
	 * diarisation and the JSON output that {@link #parseWhisperJson(String)} reads.
	 */
	public static List<String> whisperMeetingArgs(WhisperArgsOptions options) {
		String language = options.language() == null || options.language().trim().isEmpty()
				? "auto"
				: options.language().trim();
		List<String> args = new ArrayList<>();
		args.add("-m");
		args.add(options.modelPath());
		args.add("-f");
		args.add(options.wavPath());
		args.add("-l");
		args.add(language);
		args.add("-t");
		args.add(String.valueOf(Math.max(1, options.threads())));
		args.add("-pp");
		args.add("-mc");
		args.add("0");
		if (options.diarize()) {
			args.add("-di");
		}
		if (options.vadModelPath() != null && !options.vadModelPath().isEmpty()) {
			args.add("--vad");
			args.add("-vm");
			args.add(options.vadModelPath());
		}
		args.add("-oj");
		args.add("-of");
		args.add(options.outputBase());
		return List.copyOf(args);
	}

	/** What whisper's JSON output gives us: the detected language and the trimmed segments. */
	public record ParsedTranscript(@Nullable String language, List<TranscriptSegment> segments) {
	}

	/** Parses whisper.cpp's {@code .json} output; empty text segments are dropped. */
	public static ParsedTranscript parseWhisperJson(String raw) {
		JsonNode data = JsonMapper.shared().readTree(raw);
		JsonNode languageNode = data.path("result").path("language");
		String language = languageNode.isMissingNode() || languageNode.isNull()
				? null
				: languageNode.asString();

		List<TranscriptSegment> segments = new ArrayList<>();
		JsonNode transcription = data.path("transcription");
		if (transcription.isArray()) {
			for (JsonNode segment : transcription) {
				String text = segment.path("text").asString("").strip();
				if (text.isEmpty()) {
					continue;
				}
				long from = segment.path("offsets").path("from").asLong(0L);
				Speaker speaker = Speaker.fromWhisperIndex(segment.get("speaker"));
				segments.add(new TranscriptSegment(Math.max(0L, from), text, speaker));
			}
		}
		return new ParsedTranscript(language, List.copyOf(segments));
	}

	/** {@code [HH:MM:SS]}, every field zero-padded to two digits. */
	public static String formatTimestamp(long milliseconds) {
		long totalSeconds = Math.floorDiv(milliseconds, 1000L);
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;
		return "[" + String.format("%02d:%02d:%02d", hours, minutes, seconds) + "]";
	}

	/**
	 * Transcript as Markdown. Consecutive segments from the same speaker are joined into one turn
	 * when they are less than a minute apart; each turn starts with its timestamp and, when known,
	 * a bold speaker label.
	 */
	public static String formatTranscriptMarkdown(List<TranscriptSegment> segments) {
		List<TranscriptSegment> turns = new ArrayList<>();
		for (TranscriptSegment segment : segments) {
			TranscriptSegment previous = turns.isEmpty() ? null : turns.get(turns.size() - 1);
			if (previous != null && segment.speaker() != null && previous.speaker() == segment.speaker()
					&& segment.startMs() - previous.startMs() < MAX_TURN_SPAN_MS) {
				turns.set(turns.size() - 1, new TranscriptSegment(previous.startMs(),
						previous.text() + " " + segment.text(), previous.speaker()));
				continue;
			}
			turns.add(segment);
		}

		List<String> lines = new ArrayList<>();
		for (TranscriptSegment turn : turns) {
			String label = turn.speaker() != null ? "**" + turn.speaker().label() + ":** " : "";
			lines.add(formatTimestamp(turn.startMs()) + " " + label + turn.text());
		}
		return String.join("\n\n", lines);
	}

	/** Combines the two diarised transcripts into one timeline, ordered by start offset. */
	public static List<TranscriptSegment> mergeSpeakerSegments(
			List<TranscriptSegment> micSegments, List<TranscriptSegment> systemSegments) {
		List<TranscriptSegment> merged = new ArrayList<>();
		for (TranscriptSegment segment : micSegments) {
			merged.add(new TranscriptSegment(segment.startMs(), segment.text(), Speaker.ME));
		}
		for (TranscriptSegment segment : systemSegments) {
			merged.add(new TranscriptSegment(segment.startMs(), segment.text(), Speaker.REMOTE));
		}
		merged.sort(Comparator.comparingLong(TranscriptSegment::startMs));
		return List.copyOf(merged);
	}
}
