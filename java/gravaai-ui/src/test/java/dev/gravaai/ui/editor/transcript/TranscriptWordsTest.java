package dev.gravaai.ui.editor.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import dev.gravaai.core.editor.TranscriptCut;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.CaptionCueWord;
import dev.gravaai.core.project.ClipRegion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the pure helpers in {@code transcriptCut.ts} (fixture values from transcript-cut.spec). */
class TranscriptWordsTest {

	private static final List<CaptionCue> CUES = TranscriptFixtures.generatedCues();

	private static List<TranscriptWords.Word> words() {
		return TranscriptWords.flatten(CUES);
	}

	@Test
	@DisplayName("flattens cues into source-time words with stable keys")
	void flattensWords() {
		List<TranscriptWords.Word> words = words();

		assertEquals(List.of("cue-1:0", "cue-1:1", "cue-1:2", "cue-1:3"),
				words.stream().map(TranscriptWords.Word::key).toList());
		assertEquals("three", words.get(2).text());
		assertEquals(2_500, words.get(2).startMs());
		assertEquals(3_000, words.get(2).endMs());
		assertTrue(words.get(1).leadingSpace());
	}

	@Test
	@DisplayName("falls back to a single source word for cues without timings")
	void fallsBackToCueText() {
		List<TranscriptWords.Word> words = TranscriptWords.flatten(
				List.of(new CaptionCue("c", 1_000, 2_000, " whole cue ", null)));

		assertEquals(1, words.size());
		assertEquals("c:-1", words.get(0).key());
		assertEquals("whole cue", words.get(0).text());
	}

	@Test
	@DisplayName("groups paragraphs when the silence gap exceeds two seconds")
	void groupsParagraphs() {
		List<TranscriptWords.Word> words = List.of(
				new TranscriptWords.Word("a", "c", "one", 0, 500, false),
				new TranscriptWords.Word("b", "c", "two", 1_000, 1_500, true),
				new TranscriptWords.Word("c", "c", "three", 4_000, 4_500, true));

		List<List<TranscriptWords.Word>> paragraphs = TranscriptWords.paragraphs(words);

		assertEquals(2, paragraphs.size());
		assertEquals(2, paragraphs.get(0).size());
		assertEquals(1, paragraphs.get(1).size());
	}

	@Test
	@DisplayName("swallows the trailing silence but never the next spoken word")
	void buildsCutRangesWithTrailingGap() {
		List<TranscriptCut.SourceRange> ranges = TranscriptWords.wordsToCutRanges(words(),
				Set.of("cue-1:2"), TranscriptWords.DEFAULT_TRAILING_GAP_MS);

		assertEquals(List.of(new TranscriptCut.SourceRange(2_500, 3_250)), ranges,
				"three ends at 3000 and the cut swallows the 250ms of silence after it");
	}

	@Test
	@DisplayName("merges touching and overlapping ranges into one cut")
	void mergesRanges() {
		List<TranscriptCut.SourceRange> merged = TranscriptWords.mergeSourceRanges(List.of(
				new TranscriptCut.SourceRange(1_500, 1_950),
				new TranscriptCut.SourceRange(1_000, 1_500),
				new TranscriptCut.SourceRange(3_000, 2_000)));

		assertEquals(List.of(new TranscriptCut.SourceRange(1_000, 1_950)), merged);
	}

	@Test
	@DisplayName("selects filler words for the configured language")
	void selectsFillers() {
		Set<String> english = TranscriptWords.selectFillerWords(words(), "en");
		assertEquals(Set.of("cue-1:1"), english);

		Set<String> auto = TranscriptWords.selectFillerWords(words(), "auto");
		assertEquals(Set.of("cue-1:1"), auto);

		Set<String> portuguese = TranscriptWords.selectFillerWords(words(), "pt");
		assertTrue(portuguese.isEmpty());
	}

	@Test
	@DisplayName("marks words cut out of the timeline as not kept")
	void tracksKeptWords() {
		List<ClipRegion> clips = List.of(new ClipRegion("clip-1", 0, 2_500, 0L, null, null, 1,
				null, null, null, null));

		assertTrue(TranscriptWords.isWordKept(words().get(0), clips));
		assertFalse(TranscriptWords.isWordKept(words().get(3), clips),
				"four is after the cut and is no longer on the timeline");
	}
}
