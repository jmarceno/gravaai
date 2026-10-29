package dev.gravaai.services.captions;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phrase-aware caption segmentation, 1:1 port of {@code electron/ipc/captions/segment.ts}.
 *
 * <p>Whisper breaks speech on its own boundaries, not sentences. This walks Whisper's word stream
 * (which carries punctuation) and starts a new caption only at a real boundary: terminal sentence
 * punctuation, a large gap between consecutive words, or a long ffmpeg {@code silencedetect}
 * interval inside that gap. A safety cap splits a runaway phrase. Without word timings (SRT path)
 * it first re-segments by acoustic silence, then splits each cue on sentence punctuation.</p>
 */
public final class CaptionSegment {

	private static final long DEFAULT_PHRASE_PAUSE_MS = 700;
	private static final long DEFAULT_SPLIT_SILENCE_MS = 1_500;
	private static final long DEFAULT_EDGE_PAD_MS = 80;
	private static final long DEFAULT_MAX_PHRASE_MS = 12_000;
	private static final long DEFAULT_MIN_CAPTION_MS = 800;
	private static final long DEFAULT_MERGE_GAP_MS = 400;
	private static final long DEFAULT_MAX_MERGED_MS = 2_500;
	private static final int DEFAULT_MAX_MERGED_CHARS = 80;

	private static final Pattern SENTENCE_END = Pattern.compile("[.?!…。！？]$");
	private static final Pattern TRAILING_CLOSERS =
			Pattern.compile("[)\\]}\"'”’»」』）】］｝>]+$", Pattern.UNICODE_CASE);
	private static final Set<String> ABBREVIATIONS = Set.of(
			"mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc");

	/** Options for {@link #segmentCuesIntoPhrases}; null components use the defaults. */
	public record SegmentOptions(
			@Nullable Long pauseMs,
			@Nullable Long splitSilenceMs,
			@Nullable Long edgePadMs,
			@Nullable Long maxPhraseMs,
			@Nullable Long minCaptionMs,
			@Nullable Long mergeGapMs,
			@Nullable Long maxMergedMs,
			@Nullable Integer maxMergedChars) {

		public static SegmentOptions defaults() {
			return new SegmentOptions(null, null, null, null, null, null, null, null);
		}
	}

	private record MergeOptions(long minCaptionMs, long mergeGapMs, long maxMergedMs,
			int maxMergedChars) {
	}

	/** Mutable piece while building phrases. */
	private static final class Piece {

		long startMs;
		long endMs;
		String text;
		List<CaptionWord> words;

		Piece(long startMs, long endMs, String text, List<CaptionWord> words) {
			this.startMs = startMs;
			this.endMs = endMs;
			this.text = text;
			this.words = words == null ? List.of() : words;
		}
	}

	private CaptionSegment() {
	}

	/** A trailing period belongs to an abbreviation/initialism rather than ending a sentence. */
	private static boolean isAbbreviation(String text) {
		String trimmed = TRAILING_CLOSERS.matcher(text.trim()).replaceFirst("").trim();
		if (!trimmed.endsWith(".")) {
			return false;
		}
		String core = trimmed.substring(0, trimmed.length() - 1).toLowerCase(java.util.Locale.ROOT);
		if (core.isEmpty()) {
			return false;
		}
		if (core.length() == 1 && core.chars().allMatch(Character::isLetter)) {
			return true;
		}
		if (core.matches("[a-z](\\.[a-z])+")) {
			return true;
		}
		return ABBREVIATIONS.contains(core);
	}

	/** True when a word's text ends a sentence, ignoring trailing closers and abbreviations. */
	public static boolean endsSentence(String text) {
		if (text == null) {
			return false;
		}
		String trimmed = TRAILING_CLOSERS.matcher(text.trim()).replaceFirst("").trim();
		if (!SENTENCE_END.matcher(trimmed).find()) {
			return false;
		}
		return !isAbbreviation(text);
	}

	private static boolean hasWordTimings(List<CaptionCue> cues) {
		if (cues.isEmpty()) {
			return false;
		}
		for (CaptionCue cue : cues) {
			if (cue.words().isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Flatten all cues' words into one time-ordered stream, spacing across cue joins. */
	private static List<CaptionWord> flattenWords(List<CaptionCue> cues) {
		List<CaptionWord> stream = new ArrayList<>();
		for (CaptionCue cue : cues) {
			List<CaptionWord> words = cue.words();
			for (int index = 0; index < words.size(); index++) {
				CaptionWord word = words.get(index);
				boolean leadingSpace = !stream.isEmpty()
						&& (index == 0 ? true : word.leadingSpace());
				stream.add(new CaptionWord(word.text(), word.startMs(), word.endMs(),
						leadingSpace));
			}
		}
		stream.sort(Comparator.comparingLong(CaptionWord::startMs)
				.thenComparingLong(CaptionWord::endMs));
		return stream;
	}

	/** Drop words that sit entirely inside a long detected silence (Whisper hallucinations). */
	private static List<CaptionWord> dropHallucinations(List<CaptionWord> words,
			List<CaptionSilence.SilenceInterval> silences, long splitSilenceMs) {
		List<CaptionSilence.SilenceInterval> longSilences = new ArrayList<>();
		for (CaptionSilence.SilenceInterval silence : silences) {
			if (duration(silence) >= splitSilenceMs) {
				longSilences.add(silence);
			}
		}
		if (longSilences.isEmpty()) {
			return words;
		}
		List<CaptionWord> kept = new ArrayList<>();
		for (CaptionWord word : words) {
			boolean inside = false;
			for (CaptionSilence.SilenceInterval silence : longSilences) {
				if (silence.startMs() <= word.startMs() && word.endMs() <= silence.endMs()) {
					inside = true;
					break;
				}
			}
			if (!inside) {
				kept.add(word);
			}
		}
		return kept;
	}

	private static long duration(CaptionSilence.SilenceInterval silence) {
		return silence.endMs() == CaptionSilence.INFINITE_END
				? Long.MAX_VALUE
				: silence.endMs() - silence.startMs();
	}

	private static boolean silenceInGap(long gapStartMs, long gapEndMs,
			List<CaptionSilence.SilenceInterval> silences, long splitSilenceMs) {
		for (CaptionSilence.SilenceInterval silence : silences) {
			if (duration(silence) >= splitSilenceMs && silence.startMs() < gapEndMs
					&& silence.endMs() > gapStartMs) {
				return true;
			}
		}
		return false;
	}

	private static List<CaptionWord> normalizePhraseWords(List<CaptionWord> words) {
		List<CaptionWord> normalized = new ArrayList<>(words.size());
		for (int index = 0; index < words.size(); index++) {
			CaptionWord word = words.get(index);
			normalized.add(index == 0 && word.leadingSpace()
					? new CaptionWord(word.text(), word.startMs(), word.endMs(), false)
					: word);
		}
		return normalized;
	}

	private static List<List<CaptionWord>> groupWordsBySentence(List<CaptionWord> words) {
		List<List<CaptionWord>> groups = new ArrayList<>();
		List<CaptionWord> current = new ArrayList<>();
		for (int index = 0; index < words.size(); index++) {
			CaptionWord word = words.get(index);
			current.add(word);
			if (endsSentence(word.text()) && index < words.size() - 1) {
				groups.add(new ArrayList<>(current));
				current = new ArrayList<>();
			}
		}
		if (!current.isEmpty()) {
			groups.add(current);
		}
		return groups;
	}

	private static List<CaptionCue> splitTextBySentence(CaptionCue cue) {
		String[] tokens = cue.text().trim().split("\\s+");
		if (tokens.length <= 1) {
			return List.of(cue);
		}
		List<List<String>> groups = new ArrayList<>();
		List<String> current = new ArrayList<>();
		for (int index = 0; index < tokens.length; index++) {
			String token = tokens[index];
			if (token.isEmpty()) {
				continue;
			}
			current.add(token);
			if (endsSentence(token) && index < tokens.length - 1) {
				groups.add(new ArrayList<>(current));
				current = new ArrayList<>();
			}
		}
		if (!current.isEmpty()) {
			groups.add(current);
		}
		if (groups.size() <= 1) {
			return List.of(cue);
		}
		List<String> texts = new ArrayList<>();
		int totalChars = 0;
		for (List<String> group : groups) {
			String text = String.join(" ", group);
			texts.add(text);
			totalChars += text.length();
		}
		if (totalChars == 0) {
			totalChars = 1;
		}
		long spanMs = Math.max(1, cue.endMs() - cue.startMs());
		long cursorMs = cue.startMs();
		List<CaptionCue> result = new ArrayList<>();
		for (int index = 0; index < texts.size(); index++) {
			String text = texts.get(index);
			long startMs = cursorMs;
			long endMs = index == texts.size() - 1
					? cue.endMs()
					: Math.min(cue.endMs() - 1,
							Math.round(startMs + (spanMs * (double) text.length()) / totalChars));
			cursorMs = Math.max(startMs + 1, endMs);
			result.add(new CaptionCue(cue.id(), startMs, Math.max(startMs + 1, endMs), text));
		}
		return result;
	}

	private static List<CaptionCue> splitCueBySentences(CaptionCue cue) {
		List<CaptionWord> words = cue.words();
		if (words.isEmpty()) {
			return splitTextBySentence(cue);
		}
		List<List<CaptionWord>> groups = groupWordsBySentence(words);
		if (groups.size() <= 1) {
			return List.of(cue);
		}
		List<CaptionCue> result = new ArrayList<>();
		for (List<CaptionWord> group : groups) {
			List<CaptionWord> phraseWords = normalizePhraseWords(group);
			result.add(new CaptionCue(cue.id(), phraseWords.get(0).startMs(),
					phraseWords.get(phraseWords.size() - 1).endMs(),
					CaptionParser.buildCaptionTextFromWords(phraseWords), phraseWords));
		}
		return result;
	}

	private static CaptionCue mergeTwoCues(CaptionCue left, CaptionCue right) {
		if (!left.words().isEmpty() && !right.words().isEmpty()) {
			List<CaptionWord> joined = new ArrayList<>(left.words());
			for (int index = 0; index < right.words().size(); index++) {
				CaptionWord word = right.words().get(index);
				joined.add(index == 0
						? new CaptionWord(word.text(), word.startMs(), word.endMs(), true)
						: word);
			}
			List<CaptionWord> normalized = normalizePhraseWords(joined);
			return new CaptionCue(left.id(), left.startMs(), right.endMs(),
					CaptionParser.buildCaptionTextFromWords(normalized), normalized);
		}
		return new CaptionCue(left.id(), left.startMs(), right.endMs(),
				(left.text() + " " + right.text()).trim());
	}

	private static List<CaptionCue> mergeShortAdjacentCaptions(List<CaptionCue> cues,
			MergeOptions options) {
		if (cues.size() <= 1) {
			return cues;
		}
		List<CaptionCue> merged = new ArrayList<>();
		CaptionCue group = cues.get(0);
		for (int index = 1; index < cues.size(); index++) {
			CaptionCue next = cues.get(index);
			long groupDurationMs = group.endMs() - group.startMs();
			long nextDurationMs = next.endMs() - next.startMs();
			long gapMs = next.startMs() - group.endMs();
			long combinedDurationMs = next.endMs() - group.startMs();
			int combinedChars = group.text().length() + next.text().length() + 1;
			boolean canMerge = groupDurationMs < options.minCaptionMs()
					&& nextDurationMs < options.minCaptionMs()
					&& gapMs <= options.mergeGapMs()
					&& combinedDurationMs <= options.maxMergedMs()
					&& combinedChars <= options.maxMergedChars();
			if (canMerge) {
				group = mergeTwoCues(group, next);
			} else {
				merged.add(group);
				group = next;
			}
		}
		merged.add(group);
		return merged;
	}

	private static List<CaptionCue> renumberCues(List<CaptionCue> cues) {
		List<CaptionCue> renumbered = new ArrayList<>(cues.size());
		for (int index = 0; index < cues.size(); index++) {
			renumbered.add(cues.get(index).withId("caption-" + (index + 1)));
		}
		return List.copyOf(renumbered);
	}

	/** {@code segmentCuesIntoPhrases}. */
	public static List<CaptionCue> segmentCuesIntoPhrases(List<CaptionCue> cues,
			List<CaptionSilence.SilenceInterval> silences, @Nullable SegmentOptions options) {
		SegmentOptions effective = options == null ? SegmentOptions.defaults() : options;
		long pauseMs = effective.pauseMs() == null ? DEFAULT_PHRASE_PAUSE_MS : effective.pauseMs();
		long splitSilenceMs = effective.splitSilenceMs() == null
				? DEFAULT_SPLIT_SILENCE_MS
				: effective.splitSilenceMs();
		long edgePadMs = effective.edgePadMs() == null ? DEFAULT_EDGE_PAD_MS : effective.edgePadMs();
		long maxPhraseMs = effective.maxPhraseMs() == null
				? DEFAULT_MAX_PHRASE_MS
				: effective.maxPhraseMs();
		MergeOptions mergeOptions = new MergeOptions(
				effective.minCaptionMs() == null ? DEFAULT_MIN_CAPTION_MS : effective.minCaptionMs(),
				effective.mergeGapMs() == null ? DEFAULT_MERGE_GAP_MS : effective.mergeGapMs(),
				effective.maxMergedMs() == null ? DEFAULT_MAX_MERGED_MS : effective.maxMergedMs(),
				effective.maxMergedChars() == null
						? DEFAULT_MAX_MERGED_CHARS
						: effective.maxMergedChars());
		List<CaptionSilence.SilenceInterval> silenceList =
				silences == null ? List.of() : silences;

		if (cues == null || cues.isEmpty()) {
			return List.of();
		}

		// Keep untimed segments separate: merging them into timed segments would rebuild the text
		// from words and silently discard the untimed speech.
		boolean anyWords = false;
		boolean allWords = true;
		for (CaptionCue cue : cues) {
			if (!cue.words().isEmpty()) {
				anyWords = true;
			} else {
				allWords = false;
			}
		}
		if (anyWords && !allWords) {
			List<CaptionCue> segmented = new ArrayList<>();
			for (CaptionCue cue : cues) {
				segmented.addAll(segmentCuesIntoPhrases(List.of(cue), silenceList, effective));
			}
			segmented.sort(Comparator.comparingLong(CaptionCue::startMs)
					.thenComparingLong(CaptionCue::endMs));
			for (int index = 0; index < segmented.size() - 1; index++) {
				CaptionCue cue = segmented.get(index);
				long lastWordEnd = cue.startMs();
				for (CaptionWord word : cue.words()) {
					lastWordEnd = Math.max(lastWordEnd, word.endMs());
				}
				long end = Math.max(lastWordEnd, Math.min(cue.endMs(),
						segmented.get(index + 1).startMs()));
				segmented.set(index, cue.withTiming(cue.startMs(), end));
			}
			List<CaptionCue> filtered = new ArrayList<>();
			for (CaptionCue cue : segmented) {
				if (cue.endMs() > cue.startMs()) {
					filtered.add(cue);
				}
			}
			return renumberCues(filtered);
		}

		// No word timings (SRT path): silence-only re-segmentation, then one caption per sentence.
		if (!hasWordTimings(cues)) {
			List<CaptionCue> base = CaptionSilence.resegmentCuesBySilence(cues, silenceList,
					new CaptionSilence.ResegmentOptions(splitSilenceMs, edgePadMs, null));
			List<CaptionCue> sentences = new ArrayList<>();
			for (CaptionCue cue : base) {
				sentences.addAll(splitCueBySentences(cue));
			}
			return renumberCues(mergeShortAdjacentCaptions(sentences, mergeOptions));
		}

		List<CaptionCue> sortedCues = new ArrayList<>(cues);
		sortedCues.sort(Comparator.comparingLong(CaptionCue::startMs)
				.thenComparingLong(CaptionCue::endMs));
		List<CaptionWord> stream = dropHallucinations(flattenWords(sortedCues), silenceList,
				splitSilenceMs);
		if (stream.isEmpty()) {
			return List.of();
		}

		List<List<CaptionWord>> phrases = new ArrayList<>();
		List<CaptionWord> current = new ArrayList<>();
		for (int index = 0; index < stream.size(); index++) {
			CaptionWord word = stream.get(index);
			current.add(word);
			CaptionWord next = index + 1 < stream.size() ? stream.get(index + 1) : null;
			if (next == null) {
				break;
			}
			long gapMs = next.startMs() - word.endMs();
			long phraseDurationMs = word.endMs() - current.get(0).startMs();
			boolean shouldBreak = endsSentence(word.text()) || gapMs >= pauseMs
					|| (gapMs > 0 && silenceInGap(word.endMs(), next.startMs(), silenceList,
							splitSilenceMs))
					|| phraseDurationMs >= maxPhraseMs;
			if (shouldBreak) {
				phrases.add(current);
				current = new ArrayList<>();
			}
		}
		if (!current.isEmpty()) {
			phrases.add(current);
		}

		List<CaptionCue> pieces = new ArrayList<>();
		for (List<CaptionWord> phrase : phrases) {
			List<CaptionWord> phraseWords = normalizePhraseWords(phrase);
			String text = CaptionParser.buildCaptionTextFromWords(phraseWords);
			if (!text.trim().isEmpty()) {
				pieces.add(new CaptionCue("", phraseWords.get(0).startMs(),
						phraseWords.get(phraseWords.size() - 1).endMs(), text, phraseWords));
			}
		}
		if (pieces.isEmpty()) {
			return List.of();
		}
		pieces.sort(Comparator.comparingLong(CaptionCue::startMs)
				.thenComparingLong(CaptionCue::endMs));
		List<CaptionCue> merged = mergeShortAdjacentCaptions(pieces, mergeOptions);
		List<CaptionSilence.Span> spans = new ArrayList<>();
		for (CaptionCue cue : merged) {
			spans.add(new CaptionSilence.Span(cue.startMs(), cue.endMs()));
		}
		CaptionSilence.padSpans(spans, edgePadMs);
		List<CaptionCue> padded = new ArrayList<>();
		for (int index = 0; index < merged.size(); index++) {
			CaptionCue cue = merged.get(index);
			padded.add(cue.withTiming(spans.get(index).startMs, spans.get(index).endMs)
					.withId(cue.id()));
		}
		return renumberCues(padded);
	}
}
