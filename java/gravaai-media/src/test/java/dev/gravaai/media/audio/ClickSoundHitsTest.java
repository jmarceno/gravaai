package dev.gravaai.media.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClickSoundSettings;
import dev.gravaai.core.project.ClickSoundStyle;
import dev.gravaai.media.compose.CursorSample;

/** {@link ClickSoundHits}: the single plan shared by the preview and the export. */
class ClickSoundHitsTest {

	private static final ClickSoundSettings ON =
			new ClickSoundSettings(true, ClickSoundStyle.CRISP, 1);

	private static CursorSample sample(double seconds, String interactionType) {
		return new CursorSample(seconds, 0.5, 0.5, "arrow", interactionType);
	}

	private static ClipRegion clip(String id, long timelineStart, long timelineEnd,
			Long sourceStart, double speed) {
		return new ClipRegion(id, timelineStart, timelineEnd, sourceStart, null, null, speed,
				null, null, null, null);
	}

	@Test
	@DisplayName("a disabled setting yields no hits")
	void disabledYieldsNothing() {
		assertArrayEquals(new long[0], ClickSoundHits.timesMs(
				new ClickSoundSettings(false, ClickSoundStyle.CRISP, 1),
				List.of(sample(1, "click")), List.of()));
	}

	@Test
	@DisplayName("only click interactions produce hits")
	void interactionFilter() {
		long[] timesMs = ClickSoundHits.timesMs(ON, List.of(
				sample(1, "move"), sample(2, "mouseup"),
				sample(3, "click"), sample(3.5, "double-click"),
				sample(4, "right-click"), sample(4.5, "middle-click")), List.of());
		assertArrayEquals(new long[] {3_000, 3_500, 4_000, 4_500}, timesMs);
	}

	@Test
	@DisplayName("a click outside every clip is dropped, not snapped to a boundary")
	void outOfClipClickIsDropped() {
		// Clips.mapSourceTimeToTimelineTime clamps out-of-clip times, which would resurrect a
		// trimmed-away click at the cut; the plan filters on the source timeline first.
		List<ClipRegion> clips = List.of(clip("a", 0, 1_000, 0L, 1));
		assertArrayEquals(new long[0],
				ClickSoundHits.timesMs(ON, List.of(sample(1.5, "click")), clips));
	}

	@Test
	@DisplayName("a trimmed clip maps its source window to the timeline")
	void trimMapping() {
		// Timeline 0-2000 ms shows source 1000-3000 ms: a source click at 1.2 s lands at 200 ms.
		List<ClipRegion> clips = List.of(clip("a", 0, 2_000, 1_000L, 1));
		assertArrayEquals(new long[] {200},
				ClickSoundHits.timesMs(ON, List.of(sample(1.2, "click")), clips));
	}

	@Test
	@DisplayName("clip speed divides the source offset like the picture")
	void speedMapping() {
		// Timeline 0-500 ms shows source 1000-2000 ms at speed 2: source 1.5 s lands at 250 ms.
		List<ClipRegion> clips = List.of(clip("a", 0, 500, 1_000L, 2));
		assertArrayEquals(new long[] {250},
				ClickSoundHits.timesMs(ON, List.of(sample(1.5, "click")), clips));
	}

	@Test
	@DisplayName("near-duplicate hits coalesce within 8 ms")
	void dedupe() {
		long[] timesMs = ClickSoundHits.timesMs(ON, List.of(
				sample(1.000, "double-click"), sample(1.004, "click"),
				sample(1.020, "click")), List.of());
		assertArrayEquals(new long[] {1_000, 1_020}, timesMs);
	}

	@Test
	@DisplayName("hits come out sorted even when samples do not")
	void ordering() {
		long[] timesMs = ClickSoundHits.timesMs(ON,
				List.of(sample(3, "click"), sample(1, "click"), sample(2, "click")), List.of());
		assertArrayEquals(new long[] {1_000, 2_000, 3_000}, timesMs);
	}

	@Test
	@DisplayName("no clips means the whole source (the mapper's identity case)")
	void emptyClipsKeepEverything() {
		assertArrayEquals(new long[] {5_000},
				ClickSoundHits.timesMs(ON, List.of(sample(5, "click")), List.of()));
	}

	@Test
	@DisplayName("hasClicks reports the inspector hint from the sidecar")
	void hasClicks() {
		assertTrue(ClickSoundHits.hasClicks(List.of(sample(1, "right-click"))));
		assertFalse(ClickSoundHits.hasClicks(List.of(sample(1, "move"), sample(2, "mouseup"))));
		assertFalse(ClickSoundHits.hasClicks(List.of()));
	}
}
