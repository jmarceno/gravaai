package dev.gravaai.services.captions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of electron/ipc/captions/silence.ts: silence-aware caption re-segmentation from ffmpeg
 * {@code silencedetect} output.
 */
public final class CaptionSilence {

	/** ffmpeg {@code silencedetect} noise floor (dB). */
	public static final double SILENCE_NOISE_DB = -30;
	/** ffmpeg {@code silencedetect} minimum silence duration (seconds). */
	public static final double SILENCE_DETECT_MIN_S = 0.5;

	private static final long DEFAULT_SPLIT_SILENCE_MS = 1_500;
	private static final long DEFAULT_EDGE_PAD_MS = 80;
	private static final long DEFAULT_MIN_SPEECH_MS = 150;

	/** Sentinel end for a trailing silence that runs to the end of the audio. */
	public static final long INFINITE_END = Long.MAX_VALUE;

	private static final Pattern SILENCE_START = Pattern.compile("silence_start:\\s*(-?[\\d.]+)");
	private static final Pattern SILENCE_END = Pattern.compile("silence_end:\\s*(-?[\\d.]+)");

	/** A detected silence interval; {@link #INFINITE_END} means "to end of audio". */
	public record SilenceInterval(long startMs, long endMs) {
	}

	/** Options for {@link #resegmentCuesBySilence}; null components use the defaults. */
	public record ResegmentOptions(Long splitSilenceMs, Long edgePadMs, Long minSpeechMs) {
		public static ResegmentOptions defaults() {
			return new ResegmentOptions(null, null, null);
		}
	}

	/** A mutable time span ({@code Span} in silence.ts). */
	public static class Span {

		public long startMs;
		public long endMs;

		public Span(long startMs, long endMs) {
			this.startMs = startMs;
			this.endMs = endMs;
		}

		long durationMs() {
			return endMs == INFINITE_END ? Long.MAX_VALUE : Math.max(0, endMs - startMs);
		}

		@Override
		public String toString() {
			return "Span[" + startMs + "," + endMs + "]";
		}
	}

	/** One piece while building the re-segmented cues. */
	private static final class CaptionPiece extends Span {

		final String text;
		final List<CaptionWord> words;

		CaptionPiece(long startMs, long endMs, String text, List<CaptionWord> words) {
			super(startMs, endMs);
			this.text = text;
			this.words = words;
		}
	}

	private CaptionSilence() {
	}

	/** {@code parseSilenceIntervals}: ffmpeg silencedetect stderr to ordered intervals. */
	public static List<SilenceInterval> parseSilenceIntervals(String stderr) {
		List<SilenceInterval> intervals = new ArrayList<>();
		Long pendingStartMs = null;
		for (String line : (stderr == null ? "" : stderr).split("\\r?\\n")) {
			Matcher startMatch = SILENCE_START.matcher(line);
			if (startMatch.find()) {
				pendingStartMs = Math.max(0,
						Math.round(Double.parseDouble(startMatch.group(1)) * 1000));
				continue;
			}
			Matcher endMatch = SILENCE_END.matcher(line);
			if (endMatch.find() && pendingStartMs != null) {
				long endMs = Math.round(Double.parseDouble(endMatch.group(1)) * 1000);
				if (endMs > pendingStartMs) {
					intervals.add(new SilenceInterval(pendingStartMs, endMs));
				}
				pendingStartMs = null;
			}
		}
		if (pendingStartMs != null) {
			intervals.add(new SilenceInterval(pendingStartMs, INFINITE_END));
		}
		intervals.sort(Comparator.comparingLong(SilenceInterval::startMs));
		return List.copyOf(intervals);
	}

	private static List<Span> subtractIntervals(long startMs, long endMs, List<Span> intervals) {
		List<Span> spans = new ArrayList<>();
		long cursor = startMs;
		for (Span interval : intervals) {
			if (interval.endMs <= startMs || interval.startMs >= endMs) {
				continue;
			}
			long clippedStart = Math.max(interval.startMs, startMs);
			if (clippedStart > cursor) {
				spans.add(new Span(cursor, clippedStart));
			}
			cursor = Math.max(cursor, Math.min(interval.endMs, endMs));
		}
		if (cursor < endMs) {
			spans.add(new Span(cursor, endMs));
		}
		return spans;
	}

	private static int nearestRegionIndex(List<Span> regions, long timeMs) {
		int bestIndex = 0;
		long bestDistance = Long.MAX_VALUE;
		for (int index = 0; index < regions.size(); index++) {
			Span region = regions.get(index);
			long distance = timeMs < region.startMs
					? region.startMs - timeMs
					: timeMs > region.endMs ? timeMs - region.endMs : 0;
			if (distance < bestDistance) {
				bestDistance = distance;
				bestIndex = index;
			}
		}
		return bestIndex;
	}

	private static String joinText(String existing, String addition) {
		StringBuilder joined = new StringBuilder();
		for (String part : new String[] {existing, addition}) {
			String trimmed = part == null ? "" : part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (joined.length() > 0) {
				joined.append(' ');
			}
			joined.append(trimmed);
		}
		return joined.toString();
	}

	private static List<String> splitTextProportionally(String text, List<Span> overlaps) {
		List<String> tokens = new ArrayList<>();
		for (String token : text.trim().split("\\s+")) {
			if (!token.isEmpty()) {
				tokens.add(token);
			}
		}
		if (tokens.isEmpty()) {
			List<String> empty = new ArrayList<>();
			for (int index = 0; index < overlaps.size(); index++) {
				empty.add("");
			}
			return empty;
		}
		long totalDurationMs = 0;
		for (Span span : overlaps) {
			totalDurationMs += span.endMs - span.startMs;
		}
		if (totalDurationMs == 0) {
			totalDurationMs = 1;
		}
		List<String> result = new ArrayList<>();
		int cursor = 0;
		for (int index = 0; index < overlaps.size(); index++) {
			boolean isLast = index == overlaps.size() - 1;
			double share = (overlaps.get(index).endMs - overlaps.get(index).startMs)
					/ (double) totalDurationMs;
			int count = isLast
					? tokens.size() - cursor
					: Math.min(tokens.size() - cursor,
							(int) Math.round(tokens.size() * share));
			StringBuilder part = new StringBuilder();
			for (int token = cursor; token < cursor + count; token++) {
				if (part.length() > 0) {
					part.append(' ');
				}
				part.append(tokens.get(token));
			}
			result.add(part.toString());
			cursor += count;
		}
		if (cursor < tokens.size()) {
			StringBuilder rest = new StringBuilder();
			for (int token = cursor; token < tokens.size(); token++) {
				if (rest.length() > 0) {
					rest.append(' ');
				}
				rest.append(tokens.get(token));
			}
			int last = result.size() - 1;
			result.set(last, joinText(result.get(last), rest.toString()));
		}
		return result;
	}

	/** {@code padSpans}: grow each span toward neighbours by at most half the gap. */
	public static void padSpans(List<? extends Span> spans, long edgePadMs) {
		for (int index = 0; index < spans.size(); index++) {
			Span current = spans.get(index);
			Long prevEndMs = index > 0 ? ((Span) spans.get(index - 1)).endMs : null;
			Long nextStartMs = index < spans.size() - 1 ? ((Span) spans.get(index + 1)).startMs : null;
			long rawStart = current.startMs;
			long rawEnd = current.endMs;
			long leftPad = prevEndMs == null
					? Math.min(edgePadMs, rawStart)
					: Math.min(edgePadMs, Math.floorDiv(Math.max(0, rawStart - prevEndMs), 2));
			long rightPad = nextStartMs == null
					? edgePadMs
					: Math.min(edgePadMs, Math.floorDiv(Math.max(0, nextStartMs - rawEnd), 2));
			current.startMs = Math.max(0, Math.round(rawStart - leftPad));
			current.endMs = Math.max(current.startMs + 1, Math.round(rawEnd + rightPad));
		}
	}

	/** {@code resegmentCuesBySilence}. */
	public static List<CaptionCue> resegmentCuesBySilence(
			List<CaptionCue> cues, List<SilenceInterval> silences, ResegmentOptions options) {
		ResegmentOptions effective = options == null ? ResegmentOptions.defaults() : options;
		long splitSilenceMs = effective.splitSilenceMs() == null
				? DEFAULT_SPLIT_SILENCE_MS
				: effective.splitSilenceMs();
		long edgePadMs = effective.edgePadMs() == null ? DEFAULT_EDGE_PAD_MS : effective.edgePadMs();
		long minSpeechMs = effective.minSpeechMs() == null
				? DEFAULT_MIN_SPEECH_MS
				: effective.minSpeechMs();
		if (cues == null || cues.isEmpty()) {
			return List.of();
		}

		List<CaptionCue> sortedCues = new ArrayList<>(cues);
		sortedCues.sort(Comparator.comparingLong(CaptionCue::startMs)
				.thenComparingLong(CaptionCue::endMs));
		long transcriptStartMs = sortedCues.get(0).startMs();
		long transcriptEndMs = transcriptStartMs;
		for (CaptionCue cue : sortedCues) {
			transcriptEndMs = Math.max(transcriptEndMs, cue.endMs());
		}

		List<Span> boundaries = new ArrayList<>();
		for (SilenceInterval silence : silences == null ? List.<SilenceInterval>of() : silences) {
			long startMs = Math.max(silence.startMs(), transcriptStartMs);
			long endMs = Math.min(silence.endMs(), transcriptEndMs);
			Span span = new Span(startMs, endMs);
			if (span.endMs <= span.startMs) {
				continue;
			}
			if (span.durationMs() >= splitSilenceMs
					|| span.startMs <= transcriptStartMs
					|| span.endMs >= transcriptEndMs) {
				boundaries.add(span);
			}
		}
		boundaries.sort(Comparator.comparingLong(span -> span.startMs));

		List<Span> regions = new ArrayList<>();
		for (Span region : subtractIntervals(transcriptStartMs, transcriptEndMs, boundaries)) {
			if (region.durationMs() >= minSpeechMs) {
				regions.add(region);
			}
		}
		if (regions.isEmpty()) {
			return List.of();
		}

		List<String> regionTexts = new ArrayList<>();
		List<List<CaptionWord>> regionWords = new ArrayList<>();
		for (int index = 0; index < regions.size(); index++) {
			regionTexts.add("");
			regionWords.add(new ArrayList<>());
		}

		for (CaptionCue cue : sortedCues) {
			List<Integer> overlappingIndexes = new ArrayList<>();
			for (int index = 0; index < regions.size(); index++) {
				Span region = regions.get(index);
				if (cue.startMs() < region.endMs && cue.endMs() > region.startMs) {
					overlappingIndexes.add(index);
				}
			}
			if (overlappingIndexes.isEmpty()) {
				continue;
			}
			if (!cue.words().isEmpty()) {
				for (CaptionWord word : cue.words()) {
					long center = (word.startMs() + word.endMs()) / 2;
					int targetIndex = -1;
					for (int index : overlappingIndexes) {
						Span region = regions.get(index);
						if (center >= region.startMs && center <= region.endMs) {
							targetIndex = index;
							break;
						}
					}
					if (targetIndex < 0) {
						List<Span> overlappingRegions = new ArrayList<>();
						for (int index : overlappingIndexes) {
							overlappingRegions.add(regions.get(index));
						}
						targetIndex = overlappingIndexes.get(
								nearestRegionIndex(overlappingRegions, center));
					}
					regionWords.get(targetIndex).add(word);
				}
				continue;
			}
			if (overlappingIndexes.size() == 1) {
				int index = overlappingIndexes.get(0);
				regionTexts.set(index, joinText(regionTexts.get(index), cue.text()));
				continue;
			}
			List<Span> overlaps = new ArrayList<>();
			for (int index : overlappingIndexes) {
				Span region = regions.get(index);
				overlaps.add(new Span(
						Math.max(cue.startMs(), region.startMs),
						Math.min(cue.endMs(), region.endMs)));
			}
			List<String> texts = splitTextProportionally(cue.text(), overlaps);
			for (int position = 0; position < overlappingIndexes.size(); position++) {
				int index = overlappingIndexes.get(position);
				regionTexts.set(index, joinText(regionTexts.get(index), texts.get(position)));
			}
		}

		List<CaptionPiece> pieces = new ArrayList<>();
		for (int index = 0; index < regions.size(); index++) {
			Span region = regions.get(index);
			List<CaptionWord> words = new ArrayList<>(regionWords.get(index));
			words.sort(Comparator.comparingLong(CaptionWord::startMs)
					.thenComparingLong(CaptionWord::endMs));
			if (!words.isEmpty()) {
				pieces.add(new CaptionPiece(
						words.get(0).startMs(),
						words.get(words.size() - 1).endMs(),
						buildCaptionTextFromWords(words),
						words));
			} else {
				pieces.add(new CaptionPiece(
						region.startMs, region.endMs, regionTexts.get(index).trim(), List.of()));
			}
		}
		pieces.removeIf(piece -> piece.text.trim().isEmpty());
		pieces.sort(Comparator.comparingLong((CaptionPiece piece) -> piece.startMs)
				.thenComparingLong(piece -> piece.endMs));
		padSpans(pieces, edgePadMs);

		List<CaptionCue> result = new ArrayList<>();
		for (int index = 0; index < pieces.size(); index++) {
			CaptionPiece piece = pieces.get(index);
			result.add(new CaptionCue("caption-" + (index + 1), piece.startMs, piece.endMs,
					piece.text, piece.words));
		}
		return List.copyOf(result);
	}

	private static String buildCaptionTextFromWords(List<CaptionWord> words) {
		return CaptionParser.buildCaptionTextFromWords(words);
	}
}
