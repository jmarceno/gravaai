package dev.gravaai.ui.editor.transcript;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionCueWord;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;

/**
 * Transcript word helpers (port of {@code transcriptCut.ts}): flattening cues to words, paragraph
 * grouping, selection-to-source-range conversion with the trailing-silence rule, cut-kept checks
 * and filler-word selection.
 */
public final class TranscriptWords {

	/** Silence longer than this starts a new paragraph. */
	public static final long PARAGRAPH_GAP_MS = 2_000;
	/** Max silence after a deleted word that is cut together with it. */
	public static final long DEFAULT_TRAILING_GAP_MS = 250;

	/** One transcript word, always in source time. */
	public record Word(String key, String cueId, String text, long startMs, long endMs,
			boolean leadingSpace) {
	}

	private static final Map<String, List<String>> FILLER_WORDS = Map.of(
			"en", List.of("um", "umm", "uh", "uhh", "erm", "er", "hmm", "mm", "mhm"),
			// Never plain "é": it is the verb "is", one of the most common Portuguese words.
			"pt", List.of("éé", "hã", "hum", "humm", "ahn", "ãh", "eh"));

	private TranscriptWords() {
	}

	/** Port of {@code flattenTranscriptWords}. */
	public static List<Word> flatten(List<CaptionCue> cues) {
		if (cues == null || cues.isEmpty()) {
			return List.of();
		}
		List<CaptionCue> sorted = new ArrayList<>(cues);
		sorted.sort((left, right) -> Long.compare(left.startMs(), right.startMs()));
		List<Word> words = new ArrayList<>();
		for (CaptionCue cue : sorted) {
			List<CaptionCueWord> cueWords = cue.words();
			if (cueWords != null && !cueWords.isEmpty()) {
				for (int index = 0; index < cueWords.size(); index++) {
					CaptionCueWord word = cueWords.get(index);
					words.add(new Word(cue.id() + ":" + index, cue.id(), word.text(),
							word.startMs(), word.endMs(),
							Boolean.TRUE.equals(word.leadingSpace()) || index > 0));
				}
			} else {
				words.add(new Word(cue.id() + ":-1", cue.id(), cue.text() == null ? ""
						: cue.text().trim(), cue.startMs(), cue.endMs(), true));
			}
		}
		words.removeIf(word -> word.endMs() <= word.startMs() || word.text() == null
				|| word.text().trim().isEmpty());
		return List.copyOf(words);
	}

	/** Groups words into paragraphs whenever the silence gap exceeds {@link #PARAGRAPH_GAP_MS}. */
	public static List<List<Word>> paragraphs(List<Word> words) {
		List<List<Word>> groups = new ArrayList<>();
		List<Word> current = new ArrayList<>();
		Word previous = null;
		for (Word word : words) {
			if (previous != null && word.startMs() - previous.endMs() > PARAGRAPH_GAP_MS) {
				groups.add(List.copyOf(current));
				current = new ArrayList<>();
			}
			current.add(word);
			previous = word;
		}
		if (!current.isEmpty()) {
			groups.add(List.copyOf(current));
		}
		return List.copyOf(groups);
	}

	/** Port of {@code mergeSourceRanges}: overlap/touching ranges become one cut. */
	public static List<TranscriptCut.SourceRange> mergeSourceRanges(
			List<TranscriptCut.SourceRange> ranges) {
		return TranscriptCut.mergeSourceRanges(ranges);
	}

	/**
	 * Port of {@code wordsToCutRanges}: selected words become source ranges; each range swallows up
	 * to {@code trailingGapMs} of following silence but never the next spoken word.
	 */
	public static List<TranscriptCut.SourceRange> wordsToCutRanges(List<Word> words,
			Set<String> selectedKeys, long trailingGapMs) {
		long gap = trailingGapMs > 0 ? trailingGapMs : 0;
		List<TranscriptCut.SourceRange> ranges = new ArrayList<>();
		for (int index = 0; index < words.size(); index++) {
			Word word = words.get(index);
			if (selectedKeys == null || !selectedKeys.contains(word.key())) {
				continue;
			}
			Word next = index + 1 < words.size() ? words.get(index + 1) : null;
			long endMs = next == null
					? word.endMs() + gap
					: Math.max(word.endMs(), Math.min(next.startMs(), word.endMs() + gap));
			ranges.add(new TranscriptCut.SourceRange(word.startMs(), endMs));
		}
		return mergeSourceRanges(ranges);
	}

	/** Port of {@code isSourceTimeKept}. */
	public static boolean isSourceTimeKept(double sourceTimeMs, List<ClipRegion> clips) {
		if (!Double.isFinite(sourceTimeMs) || clips == null) {
			return false;
		}
		for (ClipRegion clip : clips) {
			if (sourceTimeMs >= Clips.getClipSourceStartMs(clip)
					&& sourceTimeMs < Clips.getClipSourceEndMs(clip)) {
				return true;
			}
		}
		return false;
	}

	/** Port of {@code isWordKept}: the word midpoint still exists in some clip. */
	public static boolean isWordKept(Word word, List<ClipRegion> clips) {
		return isSourceTimeKept((word.startMs() + word.endMs()) / 2.0, clips);
	}

	/** Port of {@code selectFillerWords} (language prefix, {@code auto} checks en and pt). */
	public static Set<String> selectFillerWords(List<Word> words, String language) {
		String prefix = (language == null || language.isBlank() ? "auto" : language)
				.split("-")[0].toLowerCase(Locale.ROOT);
		List<String> keys = "auto".equals(prefix) ? new ArrayList<>(FILLER_WORDS.keySet())
				: List.of(prefix);
		Set<String> fillers = new LinkedHashSet<>();
		for (String key : keys) {
			for (String filler : FILLER_WORDS.getOrDefault(key, List.of())) {
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

	/** Port of {@code normalizeWordText}: lowercase, keep letters/digits/apostrophes. */
	public static String normalizeWordText(String text) {
		return text == null ? "" : text.toLowerCase(Locale.ROOT)
				.replaceAll("[^\\p{L}\\p{N}']", "");
	}
}
