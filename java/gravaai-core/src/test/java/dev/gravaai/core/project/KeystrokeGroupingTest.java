package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Word grouping of the Keystrokes track: fast characters merge, combinations stay separate. */
class KeystrokeGroupingTest {

	private static final long LINGER_MS = 900;

	private static KeystrokeGrouping.Event press(long timeMs, String key, String... modifiers) {
		return new KeystrokeGrouping.Event(timeMs, key, List.of(modifiers));
	}

	@Test
	@DisplayName("merges fast characters into one word element with shift-aware casing")
	void mergesFastCharactersIntoWord() {
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(List.of(
				press(1_000, "H", "shift"), press(1_150, "E"), press(1_250, "L"),
				press(1_350, "L"), press(1_450, "O"), press(1_600, "Space"),
				press(1_700, "W", "shift"), press(1_800, "O"), press(1_900, "R"),
				press(2_000, "L"), press(2_100, "D")), LINGER_MS);

		assertEquals(1, groups.size());
		KeystrokeGrouping.Group word = groups.get(0);
		assertEquals("Hello World", word.text());
		assertEquals(1_000, word.startMs());
		assertEquals(2_100 + LINGER_MS, word.endMs());
		assertTrue(word.modifiers().isEmpty());
	}

	@Test
	@DisplayName("splits characters typed more than the word gap apart into separate words")
	void splitsOnPause() {
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(List.of(
				press(1_000, "H"), press(1_100, "I"),
				press(1_100 + KeystrokeGrouping.WORD_GAP_MS + 1, "Y"),
				press(2_400, "O")), LINGER_MS);

		assertEquals(2, groups.size());
		assertEquals("hi", groups.get(0).text());
		assertEquals(1_000, groups.get(0).startMs());
		assertEquals(1_100 + LINGER_MS, groups.get(0).endMs());
		assertEquals("yo", groups.get(1).text());
		assertEquals(2_101, groups.get(1).startMs());
	}

	@Test
	@DisplayName("keeps modifier combinations as their own elements and breaks the word")
	void combinationsStaySeparate() {
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(List.of(
				press(1_000, "H"), press(1_100, "I"), press(1_200, "S", "ctrl"),
				press(1_300, "X")), LINGER_MS);

		assertEquals(3, groups.size());
		assertEquals("hi", groups.get(0).text());
		KeystrokeGrouping.Group combo = groups.get(1);
		assertEquals("S", combo.text());
		assertEquals(List.of("ctrl"), combo.modifiers());
		assertEquals(1_200, combo.startMs());
		assertEquals("x", groups.get(2).text());
	}

	@Test
	@DisplayName("special keys like Enter break the word and stay their own elements")
	void specialKeysBreakWords() {
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(List.of(
				press(1_000, "H"), press(1_100, "I"), press(1_200, "Enter"),
				press(1_300, "Y"), press(1_400, "O")), LINGER_MS);

		assertEquals(3, groups.size());
		assertEquals("hi", groups.get(0).text());
		assertEquals("Enter", groups.get(1).text());
		assertEquals("yo", groups.get(2).text());
	}

	@Test
	@DisplayName("keeps inner spaces of a typed phrase and trims trailing ones")
	void spacesJoinAndTrim() {
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(List.of(
				press(1_000, "H"), press(1_100, "I"), press(1_200, "Space"),
				press(1_300, "Y"), press(1_400, "O"), press(1_500, "Space")), LINGER_MS);

		assertEquals(1, groups.size());
		assertEquals("hi yo", groups.get(0).text());
	}

	@Test
	@DisplayName("drops whitespace-only runs like lone space presses")
	void dropsWhitespaceOnlyRuns() {
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(
				List.of(press(1_000, "Space"), press(2_000, " ")), LINGER_MS);

		assertTrue(groups.isEmpty());
	}

	@Test
	@DisplayName("merges digits and punctuation like letters")
	void mergesDigitsAndPunctuation() {
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(List.of(
				press(1_000, "1"), press(1_100, "2"), press(1_200, ".")), LINGER_MS);

		assertEquals(1, groups.size());
		assertEquals("12.", groups.get(0).text());
	}

	@Test
	@DisplayName("never merges arrow keys into words despite their single-char labels")
	void arrowsStaySeparate() {
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(
				List.of(press(1_000, "↑"), press(1_100, "↑")), LINGER_MS);

		assertEquals(2, groups.size());
		assertEquals("↑", groups.get(0).text());
		assertEquals("↑", groups.get(1).text());
	}

	@Test
	@DisplayName("keeps a minimum linger window and skips blank keys")
	void normalizesWindowAndSkipsBlankKeys() {
		List<KeystrokeGrouping.Group> groups = KeystrokeGrouping.group(List.of(
				press(1_000, " "), press(1_100, ""), press(1_200, "H")), 0);

		assertEquals(1, groups.size());
		assertEquals("h", groups.get(0).text());
		assertEquals(1_200 + 1, groups.get(0).endMs());
	}
}
