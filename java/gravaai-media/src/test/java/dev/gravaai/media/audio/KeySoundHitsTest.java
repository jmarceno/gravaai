package dev.gravaai.media.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.KeySoundSettings;
import dev.gravaai.core.project.KeySoundStyle;
import dev.gravaai.media.audio.KeySoundHits.Press;

/** {@link KeySoundHits}: which presses sound and when. */
class KeySoundHitsTest {

	private static final KeySoundSettings ON = new KeySoundSettings(true, KeySoundStyle.CLACK, 1);

	private static Press press(long ms, String key) {
		return new Press(ms, key, false);
	}

	@Test
	@DisplayName("a disabled setting yields no hits")
	void disabledYieldsNothing() {
		assertArrayEquals(new long[0], KeySoundHits.timesMs(
				new KeySoundSettings(false, KeySoundStyle.CLACK, 1),
				List.of(press(100, "A")), List.of()));
	}

	@Test
	@DisplayName("only typing keys sound; modifiers, navigation and shortcuts are silent")
	void filtersNonCharacters() {
		long[] timesMs = KeySoundHits.timesMs(ON, List.of(
				press(100, "A"), press(200, "Shift"), press(300, "Ctrl"), press(400, "F5"),
				press(500, "Space"), press(600, "Backspace"), press(700, "Arrow Left"),
				press(800, "Enter"), new Press(900, "C", true), press(1_000, ";")), List.of());
		assertArrayEquals(new long[] {100, 500, 600, 800, 1_000}, timesMs);
	}

	@Test
	@DisplayName("presses closer than the minimum gap coalesce")
	void coalescesBursts() {
		long[] timesMs = KeySoundHits.timesMs(ON, List.of(
				press(100, "A"), press(110, "B"), press(124, "C"), press(125, "D")), List.of());
		assertArrayEquals(new long[] {100, 125}, timesMs);
	}

	@Test
	@DisplayName("hasTypedKeys reports whether anything would sound")
	void hasTypedKeys() {
		assertFalse(KeySoundHits.hasTypedKeys(List.of(press(1, "Shift"))));
		assertTrue(KeySoundHits.hasTypedKeys(List.of(press(1, "Shift"), press(2, "x"))));
	}

	@Test
	@DisplayName("every bundled key sound decodes")
	void bundledSoundsDecode() {
		for (KeySoundStyle style : KeySoundStyle.values()) {
			assertNotNull(ClickSounds.pcm(style), style + " must decode");
		}
	}
}
