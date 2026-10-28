package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code captionLayout.test.ts}. */
class CaptionLayoutTest {

	@Test
	@DisplayName("forces a break at every cue boundary so each phrase shows on its own")
	void forcesBreakAtCueBoundary() {
		List<CaptionCue> cues = List.of(
				new CaptionCue("a", 0, 1_000, "hello world",
						List.of(new CaptionCueWord("hello", 0, 500, null),
								new CaptionCueWord("world", 500, 1_000, true))),
				new CaptionCue("b", 1_000, 2_000, "next one",
						List.of(new CaptionCueWord("next", 1_000, 1_500, null),
								new CaptionCueWord("one", 1_500, 2_000, true))));

		List<CaptionLayout.FlatWord> flattened = CaptionLayout.flattenCaptionWords(cues);
		CaptionLayout.FlatWord firstWordOfSecondCue = flattened.stream()
				.filter(word -> word.cueId().equals("b") && word.cueWordIndex() == 0)
				.findFirst()
				.orElseThrow();

		assertTrue(firstWordOfSecondCue.forcedBreakBefore());
		assertFalse(firstWordOfSecondCue.leadingSpace());
		assertFalse(flattened.get(0).forcedBreakBefore());
	}
}
