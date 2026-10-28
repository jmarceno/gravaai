package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code clipSpeedChange.test.ts}. */
class ClipSpeedChangesTest {

	private static ClipRegion clip(String id, long startMs, long endMs, double speed) {
		return new ClipRegion(id, startMs, endMs, null, null, null, speed, null, null, null, null);
	}

	private static ZoomRegion zoom(String id, long startMs, long endMs, ZoomDepth depth) {
		return new ZoomRegion(id, startMs, endMs, depth, new ZoomFocus(0.5, 0.5), null);
	}

	@Test
	@DisplayName("returns labels only for non-default positive speeds")
	void formatsSpeedLabels() {
		assertNull(ClipSpeedChanges.formatClipSpeedLabel(1));
		assertNull(ClipSpeedChanges.formatClipSpeedLabel(0));
		assertNull(ClipSpeedChanges.formatClipSpeedLabel(-1));
		assertNull(ClipSpeedChanges.formatClipSpeedLabel(Double.POSITIVE_INFINITY));
		assertNull(ClipSpeedChanges.formatClipSpeedLabel(Double.NaN));
		assertEquals("0.5x", ClipSpeedChanges.formatClipSpeedLabel(0.5));
		assertEquals("2x", ClipSpeedChanges.formatClipSpeedLabel(2));
	}

	@Test
	@DisplayName("returns null for missing clips and invalid speeds")
	void returnsNullForInvalidInput() {
		List<ClipRegion> clips = List.of(clip("clip-1", 0, 5_000, 1));
		assertNull(ClipSpeedChanges.planClipSpeedChange(clips, List.of(), "missing", 0.5));
		for (double speed : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertNull(ClipSpeedChanges.planClipSpeedChange(clips, List.of(), "clip-1", speed));
		}
	}

	@Test
	@DisplayName("extends an isolated clip when slowing it down")
	void extendsSlowClip() {
		ClipSpeedChanges.Result result = ClipSpeedChanges.planClipSpeedChange(
				List.of(clip("clip-1", 0, 5_000, 1)), List.of(), "clip-1", 0.5);
		assertEquals(new ClipSpeedChanges.Plan(
				List.of(clip("clip-1", 0, 10_000, 0.5)), List.of()), result);
	}

	@Test
	@DisplayName("shortens an isolated clip when speeding it up")
	void shortensFastClip() {
		ClipSpeedChanges.Result result = ClipSpeedChanges.planClipSpeedChange(
				List.of(clip("clip-1", 0, 6_000, 1)), List.of(), "clip-1", 2);
		assertEquals(new ClipSpeedChanges.Plan(
				List.of(clip("clip-1", 0, 3_000, 2)), List.of()), result);
	}

	@Test
	@DisplayName("treats invalid stored clip speed as 1x")
	void treatsInvalidStoredSpeedAsOne() {
		ClipSpeedChanges.Result result = ClipSpeedChanges.planClipSpeedChange(
				List.of(clip("clip-1", 0, 4_000, Double.NaN)), List.of(), "clip-1", 0.5);
		assertEquals(new ClipSpeedChanges.Plan(
				List.of(clip("clip-1", 0, 8_000, 0.5)), List.of()), result);
	}

	@Test
	@DisplayName("blocks slow speed changes that would overlap the next clip")
	void blocksClipOverlap() {
		ClipSpeedChanges.Result result = ClipSpeedChanges.planClipSpeedChange(
				List.of(clip("clip-1", 0, 5_000, 1), clip("clip-2", 5_000, 10_000, 1)),
				List.of(), "clip-1", 0.5);
		assertEquals(new ClipSpeedChanges.Blocked(ClipSpeedChanges.BlockReason.CLIP_OVERLAP), result);
	}

	@Test
	@DisplayName("scales zoom regions inside the changed clip")
	void scalesZoomRegions() {
		ClipSpeedChanges.Result result = ClipSpeedChanges.planClipSpeedChange(
				List.of(clip("clip-1", 1_000, 5_000, 1)),
				List.of(zoom("zoom-1", 2_000, 3_000, ZoomDepth.TWO)), "clip-1", 0.5);
		assertEquals(new ClipSpeedChanges.Plan(
				List.of(clip("clip-1", 1_000, 9_000, 0.5)),
				List.of(zoom("zoom-1", 3_000, 5_000, ZoomDepth.TWO))), result);
	}

	@Test
	@DisplayName("does not scale zoom regions that start outside the changed clip")
	void doesNotScaleOutsideZooms() {
		ClipSpeedChanges.Result result = ClipSpeedChanges.planClipSpeedChange(
				List.of(clip("clip-1", 2_000, 6_000, 1)),
				List.of(zoom("zoom-before", 1_000, 1_500, ZoomDepth.TWO),
						zoom("zoom-after", 6_000, 6_500, ZoomDepth.TWO)),
				"clip-1", 0.5);
		assertEquals(new ClipSpeedChanges.Plan(
				List.of(clip("clip-1", 2_000, 10_000, 0.5)),
				List.of(zoom("zoom-before", 1_000, 1_500, ZoomDepth.TWO),
						zoom("zoom-after", 6_000, 6_500, ZoomDepth.TWO))), result);
	}

	@Test
	@DisplayName("blocks speed changes that would make scaled zooms overlap unchanged zooms")
	void blocksZoomOverlap() {
		ClipSpeedChanges.Result result = ClipSpeedChanges.planClipSpeedChange(
				List.of(clip("clip-1", 0, 5_000, 1)),
				List.of(zoom("zoom-1", 2_000, 3_000, ZoomDepth.TWO),
						zoom("zoom-2", 5_500, 6_500, ZoomDepth.THREE)),
				"clip-1", 0.5);
		assertInstanceOf(ClipSpeedChanges.Blocked.class, result);
		assertEquals(ClipSpeedChanges.BlockReason.ZOOM_OVERLAP,
				((ClipSpeedChanges.Blocked) result).blockedReason());
	}
}
