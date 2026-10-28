package dev.gravaai.services.captions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from {@code electron/ipc/captions/segment.test.ts}. */
class CaptionSegmentTest {

	private static CaptionCue cue(String id, long startMs, long endMs, String text) {
		return new CaptionCue(id, startMs, endMs, text);
	}

	private static CaptionCue cue(String id, long startMs, long endMs, String text,
			List<CaptionWord> words) {
		return new CaptionCue(id, startMs, endMs, text, words);
	}

	private static CaptionWord word(String text, long startMs, long endMs) {
		return new CaptionWord(text, startMs, endMs, false);
	}

	private static CaptionWord space(String text, long startMs, long endMs) {
		return new CaptionWord(text, startMs, endMs, true);
	}

	private static List<CaptionSilence.SilenceInterval> silence(long startMs, long endMs) {
		return List.of(new CaptionSilence.SilenceInterval(startMs, endMs));
	}

	private static List<String> texts(List<CaptionCue> cues) {
		return cues.stream().map(CaptionCue::text).toList();
	}

	@Test
	@DisplayName("detects terminal punctuation, ignoring trailing closers")
	void detectsTerminalPunctuation() {
		assertTrue(CaptionSegment.endsSentence("world."));
		assertTrue(CaptionSegment.endsSentence("you?"));
		assertTrue(CaptionSegment.endsSentence("stop!"));
		assertTrue(CaptionSegment.endsSentence("wait…"));
		assertTrue(CaptionSegment.endsSentence("said.\""));
		assertFalse(CaptionSegment.endsSentence("done,"));
		assertFalse(CaptionSegment.endsSentence("hello"));
	}

	@Test
	@DisplayName("does not treat common abbreviations or initialisms as a sentence end")
	void ignoresAbbreviations() {
		assertFalse(CaptionSegment.endsSentence("Mr."));
		assertFalse(CaptionSegment.endsSentence("Dr."));
		assertFalse(CaptionSegment.endsSentence("etc."));
		assertFalse(CaptionSegment.endsSentence("e.g."));
		assertFalse(CaptionSegment.endsSentence("U.S."));
		assertFalse(CaptionSegment.endsSentence("J."));
		assertTrue(CaptionSegment.endsSentence("Smith."));
		assertTrue(CaptionSegment.endsSentence("5."));
		assertTrue(CaptionSegment.endsSentence("really?"));
	}

	@Test
	@DisplayName("splits back-to-back sentences with no pause into separate captions")
	void splitsBackToBackSentences() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 1_800, "Hello world. How are you?",
				List.of(word("Hello", 0, 400), space("world.", 400, 800),
						space("How", 800, 1_100), space("are", 1_100, 1_400),
						space("you?", 1_400, 1_800))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(2, result.size());
		assertEquals("Hello world.", result.get(0).text());
		assertEquals("How are you?", result.get(1).text());
		assertTrue(result.get(0).endMs() <= result.get(1).startMs());
	}

	@Test
	@DisplayName("does not leak the first word of the next sentence into the previous caption")
	void doesNotLeakNextSentenceWord() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 1_800, "Hello world. How are you?",
				List.of(word("Hello", 0, 400), space("world.", 400, 800),
						space("How", 800, 1_100), space("are", 1_100, 1_400),
						space("you?", 1_400, 1_800))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		List<CaptionWord> words = result.get(1).words();
		assertEquals("How", words.get(0).text());
		assertEquals(800, words.get(0).startMs());
		assertFalse(words.get(0).leadingSpace());
	}

	@Test
	@DisplayName("merges rapid-fire short sentences into one caption (word-timed)")
	void mergesRapidFireTimed() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 800, "Okay. Great.",
				List.of(word("Okay.", 0, 400), space("Great.", 400, 800))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(1, result.size());
		assertEquals("Okay. Great.", result.get(0).text());
		assertEquals(List.of("Okay.", "Great."),
				result.get(0).words().stream().map(CaptionWord::text).toList());
	}

	@Test
	@DisplayName("merges rapid-fire short sentences into one caption (word-less)")
	void mergesRapidFireUntimed() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 400, "Okay."),
				cue("caption-2", 400, 800, "Great."));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(1, result.size());
		assertEquals("Okay. Great.", result.get(0).text());
	}

	@Test
	@DisplayName("does not merge two short captions across a real pause just above the merge gap")
	void doesNotMergeAcrossPause() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 1_250, "Okay. Great.",
				List.of(word("Okay.", 0, 400), space("Great.", 850, 1_250))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(2, result.size());
		assertEquals("Okay.", result.get(0).text());
		assertEquals("Great.", result.get(1).text());
		assertTrue(result.get(0).endMs() <= result.get(1).startMs());
	}

	@Test
	@DisplayName("does not merge a short sentence into a full-length one")
	void doesNotMergeIntoFullLength() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 2_400,
				"Okay. This is a full length sentence that should stand alone.",
				List.of(word("Okay.", 0, 400), space("This", 400, 700), space("is", 700, 900),
						space("a", 900, 1_050), space("full", 1_050, 1_350),
						space("length", 1_350, 1_650), space("sentence", 1_650, 1_950),
						space("that", 1_950, 2_100), space("should", 2_100, 2_250),
						space("stand", 2_250, 2_350), space("alone.", 2_350, 2_400))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(2, result.size());
		assertEquals("Okay.", result.get(0).text());
		assertEquals("This is a full length sentence that should stand alone.",
				result.get(1).text());
	}

	@Test
	@DisplayName("keeps a comma/clause inside one caption")
	void keepsCommaClause() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 1_500, "When I'm done, I start.",
				List.of(word("When", 0, 300), space("I'm", 300, 600),
						space("done,", 600, 900), space("I", 900, 1_100),
						space("start.", 1_100, 1_500))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(1, result.size());
		assertEquals("When I'm done, I start.", result.get(0).text());
	}

	@Test
	@DisplayName("does not split on an abbreviation, only on the real sentence end")
	void doesNotSplitOnAbbreviation() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 1_800,
				"Hello Mr. Smith. How are you?",
				List.of(word("Hello", 0, 300), space("Mr.", 300, 600),
						space("Smith.", 600, 900), space("How", 900, 1_200),
						space("are", 1_200, 1_500), space("you?", 1_500, 1_800))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(2, result.size());
		assertEquals("Hello Mr. Smith.", result.get(0).text());
		assertEquals("How are you?", result.get(1).text());
	}

	@Test
	@DisplayName("splits on a real pause even without punctuation")
	void splitsOnPauseWithoutPunctuation() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 2_000, "one two three four",
				List.of(word("one", 0, 300), space("two", 300, 600),
						space("three", 1_400, 1_700), space("four", 1_700, 2_000))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(2, result.size());
		assertEquals("one two", result.get(0).text());
		assertEquals("three four", result.get(1).text());
	}

	@Test
	@DisplayName("splits on a long acoustic silence in a word gap even when the word gap is short")
	void splitsOnLongSilenceInGap() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 2_600, "alpha beta",
				List.of(word("alpha", 0, 600), space("beta", 2_200, 2_600))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, silence(600, 2_100),
				new CaptionSegment.SegmentOptions(5_000L, null, null, null, null, null, null,
						null));
		assertEquals(2, result.size());
		assertEquals("alpha", result.get(0).text());
		assertEquals("beta", result.get(1).text());
	}

	@Test
	@DisplayName("does not split that same input when there is no silence and the gap is below the pause")
	void doesNotSplitWithoutSilence() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 2_600, "alpha beta",
				List.of(word("alpha", 0, 600), space("beta", 2_200, 2_600))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				new CaptionSegment.SegmentOptions(5_000L, null, null, null, null, null, null,
						null));
		assertEquals(1, result.size());
		assertEquals("alpha beta", result.get(0).text());
	}

	@Test
	@DisplayName("splits a runaway phrase with no punctuation and no pause via the safety cap")
	void splitsRunawayPhrase() {
		List<CaptionWord> words = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		for (int index = 0; index < 12; index++) {
			words.add(index == 0 ? word("w" + index, index * 300L, index * 300L + 300)
					: space("w" + index, index * 300L, index * 300L + 300));
			if (index > 0) {
				text.append(' ');
			}
			text.append("w").append(index);
		}
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 3_600, text.toString(), words));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				new CaptionSegment.SegmentOptions(null, null, null, 2_000L, null, null, null,
						null));
		assertTrue(result.size() > 1);
		assertEquals(text.toString(), String.join(" ", texts(result)));
	}

	@Test
	@DisplayName("drops words that fall entirely inside a long silence (hallucination)")
	void dropsHallucinations() {
		List<CaptionCue> cues = List.of(cue("caption-1", 5_000, 6_000, "Thank you.",
				List.of(word("Thank", 5_000, 5_500), space("you.", 5_500, 6_000))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, silence(4_000, 9_000),
				CaptionSegment.SegmentOptions.defaults());
		assertTrue(result.isEmpty());
	}

	@Test
	@DisplayName("produces sorted, non-overlapping cues with sequential ids")
	void producesSortedNonOverlappingCues() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 2_400,
				"First phrase here. Second phrase here. Third one.",
				List.of(word("First", 0, 300), space("phrase", 300, 600),
						space("here.", 600, 900), space("Second", 900, 1_200),
						space("phrase", 1_200, 1_500), space("here.", 1_500, 1_800),
						space("Third", 1_800, 2_100), space("one.", 2_100, 2_400))));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(3, result.size());
		for (int index = 0; index < result.size() - 1; index++) {
			assertTrue(result.get(index).startMs() <= result.get(index + 1).startMs());
			assertTrue(result.get(index).endMs() <= result.get(index + 1).startMs());
		}
		for (int index = 0; index < result.size(); index++) {
			assertEquals("caption-" + (index + 1), result.get(index).id());
		}
	}

	@Test
	@DisplayName("keeps a single-sentence word-less cue intact (legacy silence trim preserved)")
	void keepsSingleWordlessCue() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 13_920, "Hello, this is a test"));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, silence(0, 3_000),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(1, result.size());
		assertEquals(3_000 - 80, result.get(0).startMs());
		assertEquals(13_920 + 80, result.get(0).endMs());
		assertEquals("Hello, this is a test", result.get(0).text());
	}

	@Test
	@DisplayName("splits a continuous word-less paragraph into one caption per sentence")
	void splitsWordlessParagraph() {
		List<CaptionCue> cues = List.of(cue("caption-1", 0, 3_000, "This is one."),
				cue("caption-2", 3_000, 6_000, "This is two."),
				cue("caption-3", 6_000, 9_000, "This is three."));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				CaptionSegment.SegmentOptions.defaults());
		assertEquals(3, result.size());
		assertEquals(List.of("This is one.", "This is two.", "This is three."), texts(result));
		for (int index = 0; index < result.size() - 1; index++) {
			assertTrue(result.get(index).endMs() <= result.get(index + 1).startMs());
		}
		assertEquals(List.of("caption-1", "caption-2", "caption-3"),
				result.stream().map(CaptionCue::id).toList());
	}

	@Test
	@DisplayName("returns an empty list for empty input")
	void emptyInput() {
		assertTrue(CaptionSegment.segmentCuesIntoPhrases(List.of(), List.of(),
				CaptionSegment.SegmentOptions.defaults()).isEmpty());
	}

	@Test
	@DisplayName("keeps the last timed word when an adjacent untimed cue overlaps padded edges")
	void keepsTimedWordWithAdjacentUntimedCue() {
		List<CaptionCue> cues = List.of(
				cue("timed", 0, 1_200, "Hello.", List.of(word("Hello.", 0, 1_000))),
				cue("untimed", 950, 2_000, "Next sentence."));
		List<CaptionCue> result = CaptionSegment.segmentCuesIntoPhrases(cues, List.of(),
				new CaptionSegment.SegmentOptions(null, null, 100L, null, 0L, null, null, null));
		CaptionCue timed = result.stream().filter(cue -> !cue.words().isEmpty()).findFirst()
				.orElseThrow();
		assertTrue(timed.endMs() >= 1_000);
		assertTrue(result.stream().anyMatch(cue -> "Next sentence.".equals(cue.text())));
	}
}
