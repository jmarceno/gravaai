package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code captionOps.test.ts}. */
class CaptionOpsTest {

	private static List<CaptionCue> makeCues() {
		return List.of(
				new CaptionCue("a", 0, 2_000, "one two three four",
						List.of(new CaptionCueWord("one", 0, 500, null),
								new CaptionCueWord("two", 500, 1_000, true),
								new CaptionCueWord("three", 1_000, 1_500, true),
								new CaptionCueWord("four", 1_500, 2_000, true))),
				new CaptionCue("b", 2_200, 3_000, "five six",
						List.of(new CaptionCueWord("five", 2_200, 2_600, null),
								new CaptionCueWord("six", 2_600, 3_000, true))));
	}

	private static void assertCuesValid(List<CaptionCue> cues) {
		for (int index = 0; index < cues.size() - 1; index++) {
			assertTrue(cues.get(index).startMs() <= cues.get(index + 1).startMs());
			assertTrue(cues.get(index).endMs() <= cues.get(index + 1).startMs());
		}
		List<CaptionLayout.FlatWord> words = CaptionLayout.flattenCaptionWords(cues);
		for (int index = 0; index < words.size(); index++) {
			assertTrue(words.get(index).endMs() > words.get(index).startMs());
			if (index > 0) {
				assertTrue(words.get(index).startMs() >= words.get(index - 1).startMs());
				assertTrue(words.get(index).startMs() >= words.get(index - 1).endMs());
			}
		}
	}

	@Test
	@DisplayName("extends end and rescales word timings proportionally")
	void extendsEndAndRescalesWords() {
		List<CaptionCue> single = List.of(makeCues().get(0));
		List<CaptionCue> result = CaptionOps.retimeCue(single, "a",
				new CaptionOps.RetimeSpan(0, 4_000));
		CaptionCue cue = result.stream().filter(value -> value.id().equals("a")).findFirst().orElseThrow();
		assertEquals(4_000, cue.endMs());
		assertEquals(4_000, cue.words().get(cue.words().size() - 1).endMs());
		assertEquals(1_000, cue.words().get(1).startMs());
		assertEquals("one two three four", cue.text());
		assertCuesValid(result);
	}

	@Test
	@DisplayName("honors requested timing without clamping to neighbors")
	void honorsRequestedTiming() {
		List<CaptionCue> result = CaptionOps.retimeCue(makeCues(), "a",
				new CaptionOps.RetimeSpan(1_000, 2_500));
		CaptionCue cue = result.stream().filter(value -> value.id().equals("a")).findFirst().orElseThrow();
		CaptionCue neighbor = result.stream().filter(value -> value.id().equals("b")).findFirst().orElseThrow();
		assertEquals(1_000, cue.startMs());
		assertEquals(2_500, cue.endMs());
		assertEquals(1_000, cue.words().get(0).startMs());
		assertEquals(2_500, cue.words().get(cue.words().size() - 1).endMs());
		assertEquals(2_200, neighbor.startMs());
	}

	@Test
	@DisplayName("returns cue-level timing when the cue has no words")
	void returnsCueLevelTimingWithoutWords() {
		List<CaptionCue> result = CaptionOps.retimeCue(
				List.of(new CaptionCue("a", 0, 1_000, "hello", null)), "a",
				new CaptionOps.RetimeSpan(200, 1_500));
		assertEquals(200, result.get(0).startMs());
		assertEquals(1_500, result.get(0).endMs());
		assertNull(result.get(0).words());
	}

	@Test
	@DisplayName("keeps words valid and non-overlapping when retimed shorter than its word count")
	void keepsWordsValidWhenRetimedTooShort() {
		List<CaptionCue> result = CaptionOps.retimeCue(makeCues(), "a",
				new CaptionOps.RetimeSpan(0, 3));
		CaptionCue cue = result.stream().filter(value -> value.id().equals("a")).findFirst().orElseThrow();
		assertEquals(4, cue.words().size());
		assertCuesValid(result);
	}

	@Test
	@DisplayName("is a no-op for an unknown id")
	void retimeIsNoOpForUnknownId() {
		List<CaptionCue> cues = makeCues();
		assertSame(cues, CaptionOps.retimeCue(cues, "missing", new CaptionOps.RetimeSpan(0, 100)));
	}

	@Test
	@DisplayName("splits at the nearest word boundary into two cues")
	void splitsAtNearestWordBoundary() {
		List<CaptionCue> result = CaptionOps.splitCue(makeCues(), "a", 1_000);
		assertEquals(3, result.size());
		CaptionCue left = result.get(0);
		CaptionCue right = result.get(1);
		assertEquals("a", left.id());
		assertEquals("one two", left.text());
		assertNotEquals("a", right.id());
		assertEquals("three four", right.text());
		assertNull(right.words().get(0).leadingSpace());
		assertTrue(left.endMs() <= right.startMs());
		assertCuesValid(result);
	}

	@Test
	@DisplayName("does not split a single-word cue")
	void doesNotSplitSingleWordCue() {
		List<CaptionCue> cues = List.of(new CaptionCue("solo", 0, 500, "word",
				List.of(new CaptionCueWord("word", 0, 500, null))));
		assertSame(cues, CaptionOps.splitCue(cues, "solo", 250));
	}

	@Test
	@DisplayName("merges adjacent cues and re-derives spacing")
	void mergesAdjacentCues() {
		List<CaptionCue> result = CaptionOps.mergeCues(makeCues(), "a", "b");
		assertEquals(1, result.size());
		CaptionCue merged = result.get(0);
		assertEquals("a", merged.id());
		assertEquals(0, merged.startMs());
		assertEquals(3_000, merged.endMs());
		assertEquals("one two three four five six", merged.text());
		assertEquals(true, merged.words().get(4).leadingSpace());
		assertCuesValid(result);
	}

	@Test
	@DisplayName("rejects a non-adjacent merge")
	void rejectsNonAdjacentMerge() {
		List<CaptionCue> cues = new ArrayList<>(makeCues());
		cues.add(new CaptionCue("c", 4_000, 5_000, "seven",
				List.of(new CaptionCueWord("seven", 4_000, 5_000, null))));
		assertSame(cues, CaptionOps.mergeCues(cues, "a", "c"));
	}

	@Test
	@DisplayName("removes the cue and keeps the rest sorted")
	void deletesCue() {
		List<CaptionCue> result = CaptionOps.deleteCue(makeCues(), "a");
		assertEquals(List.of("b"), result.stream().map(CaptionCue::id).toList());
	}

	@Test
	@DisplayName("is a no-op for an unknown id")
	void deleteIsNoOpForUnknownId() {
		List<CaptionCue> cues = makeCues();
		assertSame(cues, CaptionOps.deleteCue(cues, "missing"));
	}
}
