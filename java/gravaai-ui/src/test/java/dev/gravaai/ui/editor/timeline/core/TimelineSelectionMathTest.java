package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code resolveDeleteSelectionTarget} in {@code hooks/utils/timelineSelectionUtils.ts}. */
class TimelineSelectionMathTest {

	private static TimelineSelectionMath.SelectionIds ids(boolean selectAll, String keyframe,
			String zoom, String clip, String annotation, String audio, String caption) {
		return new TimelineSelectionMath.SelectionIds(selectAll, keyframe, zoom, clip, annotation,
				audio, caption);
	}

	@Test
	@DisplayName("select-all resolves to zoom before any single selection")
	void selectAllWins() {
		assertEquals(TimelineSelectionMath.DeleteTarget.ZOOM,
				TimelineSelectionMath.resolveDeleteTarget(ids(true, "k", "z", "c", "a", "au", "cap")));
	}

	@Test
	@DisplayName("resolves in the React priority order")
	void resolvesInPriorityOrder() {
		assertEquals(TimelineSelectionMath.DeleteTarget.KEYFRAME,
				TimelineSelectionMath.resolveDeleteTarget(ids(false, "k", "z", "c", "a", "au", "cap")));
		assertEquals(TimelineSelectionMath.DeleteTarget.ZOOM,
				TimelineSelectionMath.resolveDeleteTarget(ids(false, null, "z", "c", "a", "au", "cap")));
		assertEquals(TimelineSelectionMath.DeleteTarget.CLIP,
				TimelineSelectionMath.resolveDeleteTarget(ids(false, null, null, "c", "a", "au", "cap")));
		assertEquals(TimelineSelectionMath.DeleteTarget.ANNOTATION,
				TimelineSelectionMath.resolveDeleteTarget(ids(false, null, null, null, "a", "au", "cap")));
		assertEquals(TimelineSelectionMath.DeleteTarget.AUDIO,
				TimelineSelectionMath.resolveDeleteTarget(ids(false, null, null, null, null, "au", "cap")));
		assertEquals(TimelineSelectionMath.DeleteTarget.CAPTION,
				TimelineSelectionMath.resolveDeleteTarget(ids(false, null, null, null, null, null, "cap")));
	}

	@Test
	@DisplayName("returns none without a selection")
	void returnsNone() {
		assertEquals(TimelineSelectionMath.DeleteTarget.NONE,
				TimelineSelectionMath.resolveDeleteTarget(ids(false, null, null, null, null, null, null)));
		assertEquals(TimelineSelectionMath.DeleteTarget.NONE,
				TimelineSelectionMath.resolveDeleteTarget(ids(false, "  ", "", null, null, null, null)));
	}
}
