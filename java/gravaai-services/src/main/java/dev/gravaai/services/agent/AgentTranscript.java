package dev.gravaai.services.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionCueWord;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;

/**
 * Transcript word utilities used by the agent ops {@code cut_pauses}, {@code cut_fillers},
 * {@code cut_words} and {@code restore_words} (port of the parts of
 * {@code src/components/video-editor/transcriptCut.ts} not already in
 * {@link dev.gravaai.core.editor.TranscriptCut}).
 */
public final class AgentTranscript {

	/** Max silence after a deleted word that is cut together with it. */
	public static final long DEFAULT_TRAILING_GAP_MS = 250;

	/** A single word of the transcript, always in source time. */
	public record Word(String key, String cueId, String text, long startMs, long endMs,
			boolean leadingSpace) {}

	/** A source-time range in milliseconds. */
	public record Range(long startMs, long endMs) {}

	private static final Map<String, List<String>> FILLER_WORDS = Map.of(
			"en", List.of("um", "umm", "uh", "uhh", "erm", "er", "hmm", "mm", "mhm"),
			// Never plain "é": it is the verb "is", one of the most common Portuguese words.
			"pt", List.of("éé", "hã", "hum", "humm", "ahn", "ãh", "eh"));

	private AgentTranscript() {}

	/** {@code flattenTranscriptWords}: every cue's words in source order. */
	public static List<Word> flattenTranscriptWords(List<CaptionCue> cues) {
		List<CaptionCue> sorted = new ArrayList<>(cues);
		sorted.sort(Comparator.comparingLong(CaptionCue::startMs));
		List<Word> words = new ArrayList<>();
		for (CaptionCue cue : sorted) {
			List<CaptionCueWord> cueWords = cue.words();
			if (cueWords != null && !cueWords.isEmpty()) {
				for (int index = 0; index < cueWords.size(); index++) {
					CaptionCueWord word = cueWords.get(index);
					Boolean leadingSpace = word.leadingSpace();
					words.add(new Word(cue.id() + ":" + index, cue.id(), word.text(),
							word.startMs(), word.endMs(),
							leadingSpace == null ? index > 0 : leadingSpace));
				}
				continue;
			}
			words.add(new Word(cue.id() + ":-1", cue.id(), cue.text().trim(), cue.startMs(),
					cue.endMs(), true));
		}
		return words.stream()
				.filter(word -> word.endMs() > word.startMs() && !word.text().trim().isEmpty())
				.toList();
	}

	/** {@code mergeSourceRanges}: overlap/touching ranges become one cut. */
	public static List<Range> mergeSourceRanges(List<Range> ranges) {
		List<Range> sorted = new ArrayList<>();
		for (Range range : ranges) {
			if (range != null && range.endMs() > range.startMs()) {
				sorted.add(new Range(range.startMs(), range.endMs()));
			}
		}
		sorted.sort(Comparator.comparingLong(Range::startMs));
		List<Range> merged = new ArrayList<>();
		for (Range range : sorted) {
			Range previous = merged.isEmpty() ? null : merged.get(merged.size() - 1);
			// Whisper word timings overlap by a few ms, so touching ranges are one cut.
			if (previous != null && range.startMs() <= previous.endMs()) {
				merged.set(merged.size() - 1,
						new Range(previous.startMs(), Math.max(previous.endMs(), range.endMs())));
				continue;
			}
			merged.add(range);
		}
		return List.copyOf(merged);
	}

	/** {@code wordsToCutRanges}: cut each selected word plus its trailing silence. */
	public static List<Range> wordsToCutRanges(List<Word> words, Set<String> selectedKeys) {
		return wordsToCutRanges(words, selectedKeys, DEFAULT_TRAILING_GAP_MS);
	}

	public static List<Range> wordsToCutRanges(List<Word> words, Set<String> selectedKeys,
			long trailingGapMs) {
		long gap = trailingGapMs > 0 ? trailingGapMs : 0;
		List<Range> ranges = new ArrayList<>();
		for (int index = 0; index < words.size(); index++) {
			Word word = words.get(index);
			if (!selectedKeys.contains(word.key())) {
				continue;
			}
			Word next = index + 1 < words.size() ? words.get(index + 1) : null;
			long endMs = next != null
					? Math.max(word.endMs(), Math.min(next.startMs(), word.endMs() + gap))
					: word.endMs() + gap;
			ranges.add(new Range(word.startMs(), endMs));
		}
		return mergeSourceRanges(ranges);
	}

	/** {@code isSourceTimeKept}. */
	public static boolean isSourceTimeKept(double timeMs, List<ClipRegion> clips) {
		if (!Double.isFinite(timeMs)) {
			return false;
		}
		for (ClipRegion clip : clips) {
			if (timeMs >= Clips.getClipSourceStartMs(clip)
					&& timeMs < Clips.getClipSourceEndMs(clip)) {
				return true;
			}
		}
		return false;
	}

	/** {@code isWordKept}: the word's centre survives the cuts. */
	public static boolean isWordKept(Word word, List<ClipRegion> clips) {
		return isSourceTimeKept((word.startMs() + word.endMs()) / 2.0, clips);
	}

	/** {@code normalizeWordText}: lowercase, letters/numbers/apostrophes only. */
	public static String normalizeWordText(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}']", "");
	}

	/** {@code selectFillerWords}. */
	public static Set<String> selectFillerWords(List<Word> words, String language) {
		String prefix = (language == null || language.isBlank() ? "auto" : language)
				.split("-")[0].toLowerCase(Locale.ROOT);
		Set<String> fillers = new LinkedHashSet<>();
		if ("auto".equals(prefix)) {
			for (List<String> list : FILLER_WORDS.values()) {
				for (String filler : list) {
					fillers.add(normalizeWordText(filler));
				}
			}
		} else {
			for (String filler : FILLER_WORDS.getOrDefault(prefix, List.of())) {
				fillers.add(normalizeWordText(filler));
			}
		}
		Set<String> selected = new LinkedHashSet<>();
		for (Word word : words) {
			if (fillers.contains(normalizeWordText(word.text()))) {
				selected.add(word.key());
			}
		}
		return selected;
	}
}
