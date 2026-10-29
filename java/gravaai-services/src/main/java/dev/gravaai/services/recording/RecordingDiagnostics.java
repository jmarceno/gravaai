package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Recording diagnostics helpers, ported from the pure/companion-audio parts of
 * {@code electron/ipc/recording/diagnostics.ts} (plan §5.6, P1.8).
 *
 * <p>The Windows/macOS capture diagnostics log itself is platform code (plan §2.3); what is ported
 * here is the timeout/probe parsing, the microphone-chunk timing summary and the companion-audio
 * sidecar discovery that the import pipeline and the editor read-model need on every platform.
 */
public final class RecordingDiagnostics {

	public static final long MIN_VALID_RECORDED_VIDEO_BYTES = 1024;
	public static final long RECORDING_AUDIO_MUX_MIN_TIMEOUT_MS = 5 * 60 * 1000;
	public static final long RECORDING_AUDIO_MUX_MAX_TIMEOUT_MS = 2 * 60 * 60 * 1000;

	private static final int MAX_GAPS = 10;

	/** One microphone chunk timing event ({@code MicrophoneChunkTimingEvent}). */
	public record MicrophoneChunkTimingEvent(
			int index,
			int size,
			long elapsedMs,
			@Nullable Long deltaMs,
			@Nullable Long recordedElapsedMs,
			@Nullable Long recordedDeltaMs) {
	}

	/** One pause interval ({@code MicrophonePauseInterval}). */
	public record MicrophonePauseInterval(long startElapsedMs, @Nullable Long endElapsedMs, @Nullable Double durationMs) {
	}

	/** A wall-clock gap in the microphone chunk stream. */
	public record WallClockGap(int index, long deltaMs, long elapsedMs) {
	}

	/** A recorded-time gap that pause accounting did not explain. */
	public record RecordedGap(int index, long deltaMs, long recordedElapsedMs) {
	}

	/** {@code summarizeMicrophoneChunkTiming} result. */
	public record MicrophoneChunkTimingSummary(
			String status,
			int timesliceMs,
			int thresholdMs,
			int eventCount,
			int pauseIntervalCount,
			long pausedDurationMs,
			long maxWallClockDeltaMs,
			long maxRecordedDeltaMs,
			int wallClockGapCount,
			int recordedGapCount,
			List<WallClockGap> wallClockGaps,
			List<RecordedGap> recordedGaps) {
	}

	/** ffprobe stream timing ({@code VideoStreamDurationProbe}). */
	public record VideoStreamDurationProbe(@Nullable Double durationSeconds, @Nullable Long frameCount, @Nullable Double frameRate) {
	}

	/** One companion-audio layout that has usable files ({@code CompanionAudioCandidate}). */
	public record CompanionAudioCandidate(
			String platform, String systemPath, String micPath, List<String> usablePaths) {
	}

	private record CompanionLayout(String platform, String systemSuffix, String micSuffix) {
	}

	private static final List<CompanionLayout> COMPANION_AUDIO_LAYOUTS = List.of(
			new CompanionLayout("mac", ".system.m4a", ".mic.m4a"),
			new CompanionLayout("win", ".system.wav", ".mic.wav"),
			new CompanionLayout("mac", ".system.webm", ".mic.webm"));

	private RecordingDiagnostics() {
	}

	/** Port of {@code getFileSizeIfPresent}. */
	public static @Nullable Long getFileSizeIfPresent(String filePath) {
		if (filePath.isBlank()) {
			return null;
		}
		try {
			return Files.size(Path.of(filePath));
		} catch (IOException error) {
			return null;
		}
	}

	/** Parses {@code Duration: hh:mm:ss.ss} from ffmpeg's stderr. */
	public static @Nullable Double parseFfmpegDurationSeconds(String stderr) {
		java.util.regex.Matcher match = java.util.regex.Pattern
				.compile("Duration:\\s+(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE)
				.matcher(stderr);
		if (!match.find()) {
			return null;
		}
		double hours = Double.parseDouble(match.group(1));
		double minutes = Double.parseDouble(match.group(2));
		double seconds = Double.parseDouble(match.group(3));
		if (!Double.isFinite(hours) || !Double.isFinite(minutes) || !Double.isFinite(seconds)) {
			return null;
		}
		return hours * 3600 + minutes * 60 + seconds;
	}

	/** Scales the audio mux timeout with the recording duration. */
	public static long getRecordingAudioMuxTimeoutMs(double durationSeconds) {
		if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
			return RECORDING_AUDIO_MUX_MIN_TIMEOUT_MS;
		}
		long realtimeMuxBudgetMs = (long) Math.ceil(durationSeconds * 1000) + 60_000;
		return Math.min(
				RECORDING_AUDIO_MUX_MAX_TIMEOUT_MS,
				Math.max(RECORDING_AUDIO_MUX_MIN_TIMEOUT_MS, realtimeMuxBudgetMs));
	}

	private static @Nullable Double getFiniteNonNegativeNumber(@Nullable Object value) {
		return value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0
				? number.doubleValue()
				: null;
	}

	/** Port of {@code summarizeMicrophoneChunkTiming}: pause-aware microphone gap classification. */
	public static @Nullable MicrophoneChunkTimingSummary summarizeMicrophoneChunkTiming(
			List<MicrophoneChunkTimingEvent> chunkEvents,
			List<MicrophonePauseInterval> pauseIntervals,
			double timesliceMs) {
		int normalizedTimesliceMs =
				Double.isFinite(timesliceMs) && timesliceMs > 0 ? (int) Math.round(timesliceMs) : 250;
		int thresholdMs = Math.max(600, (int) Math.round(normalizedTimesliceMs * 2.5));
		List<MicrophoneChunkTimingEvent> events = chunkEvents;
		List<MicrophonePauseInterval> pauses = pauseIntervals;
		long pausedDurationMs = 0;
		for (MicrophonePauseInterval interval : pauses) {
			Double durationMs = getFiniteNonNegativeNumber(interval.durationMs());
			if (durationMs != null) {
				pausedDurationMs += Math.round(durationMs);
			}
		}
		List<WallClockGap> wallClockGaps = new ArrayList<>();
		List<RecordedGap> recordedGaps = new ArrayList<>();
		double maxWallClockDeltaMs = 0;
		double maxRecordedDeltaMs = 0;
		for (MicrophoneChunkTimingEvent event : events) {
			double deltaMs = event.deltaMs() == null ? 0 : event.deltaMs();
			maxWallClockDeltaMs = Math.max(maxWallClockDeltaMs, deltaMs);
			if (deltaMs > thresholdMs) {
				wallClockGaps.add(new WallClockGap(
						event.index(), Math.round(deltaMs), Math.round(event.elapsedMs())));
			}
			Double recordedDeltaMs = getFiniteNonNegativeNumber(event.recordedDeltaMs());
			double effectiveRecordedDeltaMs = recordedDeltaMs != null
					? recordedDeltaMs
					: (event.deltaMs() == null ? 0 : event.deltaMs());
			maxRecordedDeltaMs = Math.max(maxRecordedDeltaMs, effectiveRecordedDeltaMs);
			if (effectiveRecordedDeltaMs > thresholdMs) {
				long recordedElapsedMs = event.recordedElapsedMs() != null
						? event.recordedElapsedMs()
						: event.elapsedMs();
				recordedGaps.add(new RecordedGap(
						event.index(), Math.round(effectiveRecordedDeltaMs), Math.round(recordedElapsedMs)));
			}
		}
		String status = !recordedGaps.isEmpty()
				? "needs-review"
				: (!wallClockGaps.isEmpty() && pausedDurationMs > 0 ? "pause-accounted" : "ok");
		return new MicrophoneChunkTimingSummary(
				status,
				normalizedTimesliceMs,
				thresholdMs,
				events.size(),
				pauses.size(),
				pausedDurationMs,
				Math.round(maxWallClockDeltaMs),
				Math.round(maxRecordedDeltaMs),
				wallClockGaps.size(),
				recordedGaps.size(),
				List.copyOf(wallClockGaps.subList(0, Math.min(MAX_GAPS, wallClockGaps.size()))),
				List.copyOf(recordedGaps.subList(0, Math.min(MAX_GAPS, recordedGaps.size()))));
	}

	/** @param raw the raw JSON value, or null when the field is absent */
	private static @Nullable Double parsePositiveNumber(@Nullable Object raw) {
		if (raw instanceof Number number) {
			double value = number.doubleValue();
			return Double.isFinite(value) && value > 0 ? value : null;
		}
		if (raw instanceof String text) {
			try {
				double value = Double.parseDouble(text);
				return Double.isFinite(value) && value > 0 ? value : null;
			} catch (NumberFormatException error) {
				return null;
			}
		}
		return null;
	}

	/** @param raw the raw JSON value, or null when the field is absent */
	private static @Nullable Long parsePositiveInteger(@Nullable Object raw) {
		Double value = parsePositiveNumber(raw);
		return value == null ? null : Math.round(value);
	}

	/** @param raw the raw JSON value, or null when the field is absent */
	private static @Nullable Double parseFrameRate(@Nullable Object raw) {
		if (!(raw instanceof String text) || text.isBlank()) {
			return null;
		}
		String trimmed = text.trim();
		int slash = trimmed.indexOf('/');
		if (slash < 0) {
			try {
				double value = Double.parseDouble(trimmed);
				return Double.isFinite(value) && value > 0 ? value : null;
			} catch (NumberFormatException error) {
				return null;
			}
		}
		try {
			double numerator = Double.parseDouble(trimmed.substring(0, slash));
			double denominator = Double.parseDouble(trimmed.substring(slash + 1));
			if (!Double.isFinite(numerator) || !Double.isFinite(denominator) || denominator <= 0) {
				return null;
			}
			double value = numerator / denominator;
			return Double.isFinite(value) && value > 0 ? value : null;
		} catch (NumberFormatException error) {
			return null;
		}
	}

	private static @Nullable JsonNode nodeValue(JsonNode node, String field) {
		return node.get(field);
	}

	/** @param node the JSON value, or null when the field is absent */
	private static @Nullable Object javaValue(@Nullable JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isNumber()) {
			return node.asDouble();
		}
		if (node.isString()) {
			return node.asString();
		}
		return null;
	}

	/** Port of {@code parseFfprobeVideoStreamDuration}: stream duration or frame-count fallback. */
	public static @Nullable VideoStreamDurationProbe parseFfprobeVideoStreamDuration(String output) {
		JsonNode parsed;
		try {
			parsed = Json.parseJsonWithByteOrderMark(output);
		} catch (RuntimeException error) {
			return null;
		}
		JsonNode streams = nodeValue(parsed, "streams");
		JsonNode stream = streams != null && streams.isArray() && !streams.isEmpty()
				? streams.get(0)
				: null;
		if (stream == null) {
			return null;
		}
		Double frameRate = parseFrameRate(javaValue(nodeValue(stream, "avg_frame_rate")));
		if (frameRate == null) {
			frameRate = parseFrameRate(javaValue(nodeValue(stream, "r_frame_rate")));
		}
		Long frameCount = parsePositiveInteger(javaValue(nodeValue(stream, "nb_read_frames")));
		if (frameCount == null) {
			frameCount = parsePositiveInteger(javaValue(nodeValue(stream, "nb_frames")));
		}
		Double streamDuration = parsePositiveNumber(javaValue(nodeValue(stream, "duration")));
		Double frameDerivedDuration = frameCount != null && frameRate != null && frameRate > 0
				? frameCount / frameRate
				: null;
		return new VideoStreamDurationProbe(
				streamDuration != null ? streamDuration : frameDerivedDuration, frameCount, frameRate);
	}

	/** {@code getRecordingDiagnosticsPath}: next to the video, with its extension replaced. */
	public static String getRecordingDiagnosticsPath(String videoPath) {
		int slash = Math.max(videoPath.lastIndexOf('/'), videoPath.lastIndexOf('\\'));
		int dot = videoPath.lastIndexOf('.');
		String stem = dot > slash ? videoPath.substring(0, dot) : videoPath;
		return stem + ".recording-diagnostics.json";
	}

	/** The companion audio files that exist and are non-empty, in layout priority order. */
	public static List<CompanionAudioCandidate> getUsableCompanionAudioCandidates(String videoPath) {
		int slash = Math.max(videoPath.lastIndexOf('/'), videoPath.lastIndexOf('\\'));
		int dot = videoPath.lastIndexOf('.');
		String basePath = dot > slash ? videoPath.substring(0, dot) : videoPath;
		List<CompanionAudioCandidate> candidates = new ArrayList<>();
		for (CompanionLayout layout : COMPANION_AUDIO_LAYOUTS) {
			String systemPath = basePath + layout.systemSuffix();
			String micPath = basePath + layout.micSuffix();
			List<String> usablePaths = new ArrayList<>();
			for (String companionPath : List.of(systemPath, micPath)) {
				try {
					if (Files.size(Path.of(companionPath)) > 0) {
						usablePaths.add(companionPath);
					}
				} catch (IOException missing) {
					// Missing companion audio is expected for many recordings.
				}
			}
			if (!usablePaths.isEmpty()) {
				candidates.add(new CompanionAudioCandidate(
						layout.platform(), systemPath, micPath, List.copyOf(usablePaths)));
			}
		}
		return candidates;
	}

	private static @Nullable JsonNode readCompanionAudioTimingMetadata(String companionPath) {
		try {
			JsonNode parsed = Json.parseJsonWithByteOrderMark(
					Files.readString(Path.of(companionPath + ".json"), StandardCharsets.UTF_8));
			return parsed.isObject() ? parsed : null;
		} catch (IOException | RuntimeException error) {
			return null;
		}
	}

	/** The {@code startDelayMs} recorded next to a companion audio sidecar, or null. */
	public static @Nullable Long getCompanionAudioStartDelayMs(String companionPath) {
		JsonNode metadata = readCompanionAudioTimingMetadata(companionPath);
		JsonNode startDelayNode = metadata == null ? null : metadata.get("startDelayMs");
		if (startDelayNode == null || !startDelayNode.isNumber()) {
			return null;
		}
		double startDelayMs = startDelayNode.asDouble();
		if (!Double.isFinite(startDelayMs) || startDelayMs < 0) {
			return null;
		}
		return Math.round(startDelayMs);
	}

	/** Truncates a diagnostics process output to its last {@code maxLength} characters. */
	public static String truncateDiagnosticsText(String value, int maxLength) {
		if (value.length() <= maxLength) {
			return value;
		}
		return value.substring(value.length() - maxLength)
				+ "\n[gravaai: truncated to last " + maxLength + " chars]";
	}
}
