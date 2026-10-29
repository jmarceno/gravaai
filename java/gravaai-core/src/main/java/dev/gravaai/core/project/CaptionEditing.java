package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Caption text editing (port of {@code src/components/video-editor/captionEditing.ts}). */
public final class CaptionEditing {

	private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern NON_WHITESPACE = Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS);

	/** One word of an edit target, referencing the cue and word position it came from. */
	public record WordRef(
			String cueId, int cueWordIndex, long startMs, long endMs, String text, boolean leadingSpace) {}

	/** The words currently shown on screen (one caption page) for the inline editor. */
	public record EditTarget(String id, long startMs, long endMs, @Nullable String text, List<WordRef> words) {}

	private CaptionEditing() {}

	/** {@code normalizeCaptionEditText}: trim and collapse every whitespace run to one space. */
	public static String normalizeCaptionEditText(String text) {
		return WHITESPACE_RUN.matcher(text.strip()).replaceAll(" ");
	}

	private static List<String> tokens(String text) {
		List<String> tokens = new ArrayList<>();
		var matcher = NON_WHITESPACE.matcher(normalizeCaptionEditText(text));
		while (matcher.find()) {
			tokens.add(matcher.group());
		}
		return tokens;
	}

	private static List<CaptionCueWord> buildCaptionWordsForEditedText(
			String text, long startMs, long endMs) {
		List<String> tokens = tokens(text);
		long normalizedStartMs = Math.max(0, Math.round(startMs));
		long normalizedEndMs = Math.max(normalizedStartMs + 1, Math.round(endMs));
		long durationMs = normalizedEndMs - normalizedStartMs;

		List<CaptionCueWord> words = new ArrayList<>(tokens.size());
		for (int index = 0; index < tokens.size(); index++) {
			long wordStartMs = Math.min(normalizedEndMs - 1,
					Math.max(normalizedStartMs,
							Math.round(normalizedStartMs + (durationMs * (double) index) / tokens.size())));
			long nextBoundaryMs = index == tokens.size() - 1
					? normalizedEndMs
					: Math.round(normalizedStartMs
							+ (durationMs * (double) (index + 1)) / tokens.size());
			long wordEndMs = Math.min(normalizedEndMs, Math.max(wordStartMs + 1, nextBoundaryMs));
			words.add(new CaptionCueWord(tokens.get(index), wordStartMs, wordEndMs,
					index > 0 ? Boolean.TRUE : null));
		}
		return words;
	}

	/** {@code normalizeCaptionWords}: real word timings clamped into the cue, or derived ones. */
	public static List<CaptionCueWord> normalizeCaptionWords(CaptionCue cue) {
		List<CaptionCueWord> validSourceWords = new ArrayList<>();
		if (cue.words() != null) {
			for (CaptionCueWord word : cue.words()) {
				if (word != null && word.text() != null
						&& !normalizeCaptionEditText(word.text()).isEmpty()) {
					validSourceWords.add(word);
				}
			}
		}
		List<CaptionCueWord> sourceWords = !validSourceWords.isEmpty()
				? validSourceWords
				: buildCaptionWordsForEditedText(cue.text(), cue.startMs(), cue.endMs());

		List<CaptionCueWord> result = new ArrayList<>();
		for (CaptionCueWord word : sourceWords) {
			if (word == null || word.text() == null) {
				continue;
			}
			long startMs = Math.max(cue.startMs(),
					Math.min(cue.endMs() - 1, Math.round((double) word.startMs())));
			long endMs = Math.max(startMs + 1,
					Math.min(cue.endMs(), Math.round((double) word.endMs())));
			String text = normalizeCaptionEditText(word.text());
			if (text.isEmpty()) {
				continue;
			}
			result.add(new CaptionCueWord(text, startMs, endMs,
					Boolean.TRUE.equals(word.leadingSpace()) ? Boolean.TRUE : null));
		}
		return result;
	}

	/** {@code captionWordsToText}. */
	public static String captionWordsToText(List<CaptionCueWord> words) {
		StringBuilder text = new StringBuilder();
		for (int index = 0; index < words.size(); index++) {
			CaptionCueWord word = words.get(index);
			if (index > 0 && Boolean.TRUE.equals(word.leadingSpace())) {
				text.append(' ');
			}
			text.append(word.text());
		}
		return text.toString().strip();
	}

	/** {@code normalizeCaptionWordSpacing}: order by time and re-derive the leading spaces. */
	public static List<CaptionCueWord> normalizeCaptionWordSpacing(List<CaptionCueWord> words) {
		List<CaptionCueWord> sorted = new ArrayList<>(words);
		sorted.sort((left, right) -> left.startMs() != right.startMs()
				? Long.compare(left.startMs(), right.startMs())
				: Long.compare(left.endMs(), right.endMs()));
		List<CaptionCueWord> result = new ArrayList<>(sorted.size());
		for (int index = 0; index < sorted.size(); index++) {
			CaptionCueWord word = sorted.get(index);
			result.add(new CaptionCueWord(word.text(), word.startMs(), word.endMs(),
					index > 0 ? Boolean.TRUE : null));
		}
		return result;
	}

	private record CueSegment(CaptionCue cue, long startMs, long endMs) {}

	/** {@code updateCaptionCuesForEditedTarget}. */
	public static List<CaptionCue> updateCaptionCuesForEditedTarget(
			List<CaptionCue> cues, EditTarget target, String text) {
		String normalizedText = normalizeCaptionEditText(text);
		if (normalizedText.isEmpty() || target.words().isEmpty()) {
			return cues;
		}

		Map<String, List<WordRef>> targetWordsByCue = new LinkedHashMap<>();
		for (WordRef word : target.words()) {
			targetWordsByCue.computeIfAbsent(word.cueId(), key -> new ArrayList<>()).add(word);
		}

		List<String> tokens = tokens(normalizedText);
		var targetCueIds = targetWordsByCue.keySet();
		List<CueSegment> cueSegments = new ArrayList<>();
		for (CaptionCue cue : cues) {
			if (!targetCueIds.contains(cue.id())) {
				continue;
			}
			List<WordRef> words = targetWordsByCue.getOrDefault(cue.id(), List.of());
			if (words.isEmpty()) {
				continue;
			}
			long startMs = words.stream().mapToLong(WordRef::startMs).min().orElse(0);
			long endMs = words.stream().mapToLong(WordRef::endMs).max().orElse(0);
			cueSegments.add(new CueSegment(cue, startMs, endMs));
		}

		Map<String, List<CaptionCueWord>> editedWordsByCue = new LinkedHashMap<>();
		Map<String, Integer> tokenCountsByCue =
				distributeEditedTokensAcrossCueSegments(tokens.size(), cueSegments);
		int tokenCursor = 0;
		for (CueSegment segment : cueSegments) {
			int tokenCount = tokenCountsByCue.getOrDefault(segment.cue().id(), 0);
			if (tokenCount <= 0) {
				continue;
			}
			List<String> segmentTokens = tokens.subList(tokenCursor,
					Math.min(tokens.size(), tokenCursor + tokenCount));
			tokenCursor += tokenCount;
			editedWordsByCue.put(segment.cue().id(), buildCaptionWordsForEditedText(
					String.join(" ", segmentTokens), segment.startMs(), segment.endMs()));
		}

		List<CaptionCue> result = new ArrayList<>(cues.size());
		for (CaptionCue cue : cues) {
			List<WordRef> targetWords = targetWordsByCue.get(cue.id());
			if (targetWords == null) {
				result.add(cue);
				continue;
			}

			var targetIndexes = targetWords.stream().map(WordRef::cueWordIndex).collect(
					java.util.stream.Collectors.toSet());
			List<CaptionCueWord> existingWords = normalizeCaptionWords(cue);
			List<CaptionCueWord> keptWords = new ArrayList<>();
			for (int index = 0; index < existingWords.size(); index++) {
				if (!targetIndexes.contains(index)) {
					keptWords.add(existingWords.get(index));
				}
			}
			List<CaptionCueWord> combined = new ArrayList<>(keptWords);
			combined.addAll(editedWordsByCue.getOrDefault(cue.id(), List.of()));
			List<CaptionCueWord> nextWords = normalizeCaptionWordSpacing(combined);
			boolean shouldKeepWords = cue.words() != null && !cue.words().isEmpty();

			result.add(new CaptionCue(cue.id(), cue.startMs(), cue.endMs(),
					captionWordsToText(nextWords),
					shouldKeepWords && !nextWords.isEmpty() ? nextWords : null));
		}
		return result;
	}

	private static Map<String, Integer> distributeEditedTokensAcrossCueSegments(
			int tokenCount, List<CueSegment> segments) {
		Map<String, Integer> tokenCountsByCue = new LinkedHashMap<>();
		if (tokenCount <= 0 || segments.isEmpty()) {
			return tokenCountsByCue;
		}

		if (tokenCount < segments.size()) {
			List<CueSegment> largestSegments = new ArrayList<>(segments);
			largestSegments.sort((left, right) -> Long.compare(
					right.endMs() - right.startMs(), left.endMs() - left.startMs()));
			var selectedCueIds = largestSegments.subList(0, tokenCount).stream()
					.map(segment -> segment.cue().id()).collect(java.util.stream.Collectors.toSet());
			for (CueSegment segment : segments) {
				tokenCountsByCue.put(segment.cue().id(),
						selectedCueIds.contains(segment.cue().id()) ? 1 : 0);
			}
			return tokenCountsByCue;
		}

		int baseTokenCount = 1;
		int remainingTokens = tokenCount - segments.size();
		long totalDuration = Math.max(1, segments.stream()
				.mapToLong(segment -> Math.max(1, segment.endMs() - segment.startMs())).sum());
		List<WeightedCount> weightedCounts = new ArrayList<>();
		for (CueSegment segment : segments) {
			double exactCount = ((double) Math.max(1, segment.endMs() - segment.startMs())
					/ totalDuration) * remainingTokens;
			int extraCount = (int) Math.floor(exactCount);
			weightedCounts.add(new WeightedCount(segment, baseTokenCount + extraCount,
					exactCount - extraCount));
		}
		int assignedTokens = weightedCounts.stream().mapToInt(item -> item.count).sum();

		List<WeightedCount> byRemainder = new ArrayList<>(weightedCounts);
		byRemainder.sort((left, right) -> Double.compare(right.remainder, left.remainder));
		for (WeightedCount item : byRemainder) {
			if (assignedTokens >= tokenCount) {
				break;
			}
			item.count += 1;
			assignedTokens += 1;
		}

		for (WeightedCount item : weightedCounts) {
			tokenCountsByCue.put(item.segment.cue().id(), item.count);
		}
		return tokenCountsByCue;
	}

	private static final class WeightedCount {
		private final CueSegment segment;
		private final double remainder;
		private int count;

		WeightedCount(CueSegment segment, int count, double remainder) {
			this.segment = segment;
			this.count = count;
			this.remainder = remainder;
		}
	}
}
