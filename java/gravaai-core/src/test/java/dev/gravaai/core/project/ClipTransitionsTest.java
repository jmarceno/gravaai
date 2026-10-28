package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code clipTransitions.test.ts}. */
class ClipTransitionsTest {

	private static ClipRegion clip(String id, long startMs, long endMs, Long fadeMs) {
		return new ClipRegion(id, startMs, endMs, null, null, null, 1, null, null, null,
				fadeMs == null ? null : new ClipTransition(ClipTransitionKind.FADE_BLACK, fadeMs));
	}

	@Test
	@DisplayName("returns no windows without transitions")
	void returnsNoWindowsWithoutTransitions() {
		List<ClipRegion> clips = List.of(clip("a", 0, 1000, null), clip("b", 1000, 2000, null));
		assertEquals(List.of(), ClipTransitions.buildFadeWindows(clips));
		assertFalse(ClipTransitions.hasClipTransitions(clips));
	}

	@Test
	@DisplayName("centres a fade on the cut")
	void centresFadeOnCut() {
		List<ClipRegion> clips = List.of(clip("b", 2000, 4000, null), clip("a", 0, 2000, 500L));
		assertTrue(ClipTransitions.hasClipTransitions(clips));
		assertEquals(List.of(new ClipTransitions.FadeWindow(1750, 2000, null, 2250)),
				ClipTransitions.buildFadeWindows(clips));
	}

	@Test
	@DisplayName("holds black across a gap and fades the next clip in")
	void holdsBlackAcrossGap() {
		List<ClipTransitions.FadeWindow> windows = ClipTransitions.buildFadeWindows(
				List.of(clip("a", 0, 2000, 500L), clip("b", 3000, 5000, null)));
		assertEquals(List.of(new ClipTransitions.FadeWindow(1750, 2000, 3000.0, 3250)), windows);
		assertEquals(0, ClipTransitions.getFadeBlackAmount(windows, 1750));
		assertEquals(1, ClipTransitions.getFadeBlackAmount(windows, 2000));
		assertEquals(1, ClipTransitions.getFadeBlackAmount(windows, 2500));
		assertEquals(1, ClipTransitions.getFadeBlackAmount(windows, 3000));
		assertEquals(0.5, ClipTransitions.getFadeBlackAmount(windows, 3125), 1e-9);
		assertEquals(0, ClipTransitions.getFadeBlackAmount(windows, 3250));
	}

	@Test
	@DisplayName("clamps the fade to half of a short neighbour")
	void clampsFadeToShortNeighbour() {
		assertEquals(List.of(new ClipTransitions.FadeWindow(1850, 2000, null, 2150)),
				ClipTransitions.buildFadeWindows(
						List.of(clip("a", 0, 2000, 1000L), clip("b", 2000, 2300, null))));
	}

	@Test
	@DisplayName("fades the last clip out before its end")
	void fadesLastClipOut() {
		assertEquals(List.of(new ClipTransitions.FadeWindow(4000, 5000, null, 5000)),
				ClipTransitions.buildFadeWindows(List.of(clip("a", 0, 5000, 1000L))));
		assertEquals(List.of(new ClipTransitions.FadeWindow(0, 600, null, 600)),
				ClipTransitions.buildFadeWindows(List.of(clip("a", 0, 600, 1000L))));
	}

	@Test
	@DisplayName("dips to black at the cut and back")
	void dipsToBlackAtCut() {
		List<ClipTransitions.FadeWindow> windows = ClipTransitions.buildFadeWindows(
				List.of(clip("a", 0, 2000, 500L), clip("b", 2000, 4000, 1000L)));
		assertEquals(0, ClipTransitions.getFadeBlackAmount(windows, 1000));
		assertEquals(0, ClipTransitions.getFadeBlackAmount(windows, 1750));
		assertEquals(0.5, ClipTransitions.getFadeBlackAmount(windows, 1875), 1e-9);
		assertEquals(1, ClipTransitions.getFadeBlackAmount(windows, 2000));
		assertEquals(0, ClipTransitions.getFadeBlackAmount(windows, 2250));
	}

	@Test
	@DisplayName("is fully black at the end of a last-clip fade-out")
	void fullyBlackAtLastClipEnd() {
		List<ClipTransitions.FadeWindow> windows = ClipTransitions.buildFadeWindows(
				List.of(clip("a", 0, 2000, 500L), clip("b", 2000, 4000, 1000L)));
		assertEquals(0, ClipTransitions.getFadeBlackAmount(windows, 3000));
		assertEquals(0.5, ClipTransitions.getFadeBlackAmount(windows, 3500), 1e-9);
		assertEquals(1, ClipTransitions.getFadeBlackAmount(windows, 4000));
	}

	@Test
	@DisplayName("gives the matching audio gain")
	void givesMatchingAudioGain() {
		List<ClipTransitions.FadeWindow> windows = ClipTransitions.buildFadeWindows(
				List.of(clip("a", 0, 2000, 500L), clip("b", 2000, 4000, 1000L)));
		for (double timeMs : new double[] {1000, 1800, 1875, 2000, 3600}) {
			assertEquals(1 - ClipTransitions.getFadeBlackAmount(windows, timeMs),
					ClipTransitions.getFadeGain(windows, timeMs), 1e-9);
		}
	}
}
