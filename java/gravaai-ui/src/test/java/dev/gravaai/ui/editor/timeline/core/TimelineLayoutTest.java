package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code timeline/timelineLayout.test.ts}. */
class TimelineLayoutTest {

	@Test
	@DisplayName("reserves vertical space for every rendered timeline row")
	void reservesVerticalSpace() {
		assertEquals(TimelineLayout.TIMELINE_CLIP_ROW_HEIGHT_PX
						+ 4 * TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX + 10,
				TimelineLayout.getTimelineRowsMinHeightPx(5));
		assertEquals(TimelineLayout.TIMELINE_AXIS_HEIGHT_PX
						+ TimelineLayout.TIMELINE_CLIP_ROW_HEIGHT_PX
						+ 4 * TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX + 10,
				TimelineLayout.getTimelineContentMinHeightPx(5));
	}

	@Test
	@DisplayName("ignores invalid row counts")
	void ignoresInvalidRowCounts() {
		assertEquals(0, TimelineLayout.getTimelineRowsMinHeightPx(-1));
		assertEquals(0, TimelineLayout.getTimelineRowsMinHeightPx(Double.NaN));
		assertEquals(TimelineLayout.TIMELINE_AXIS_HEIGHT_PX,
				TimelineLayout.getTimelineContentMinHeightPx(Double.POSITIVE_INFINITY));
	}

	@Test
	@DisplayName("floors fractional row counts")
	void floorsFractionalRowCounts() {
		assertEquals(TimelineLayout.TIMELINE_CLIP_ROW_HEIGHT_PX
						+ 2 * TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX + 4,
				TimelineLayout.getTimelineRowsMinHeightPx(2.9));
		assertEquals(TimelineLayout.TIMELINE_AXIS_HEIGHT_PX
						+ TimelineLayout.TIMELINE_CLIP_ROW_HEIGHT_PX
						+ 2 * TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX + 4,
				TimelineLayout.getTimelineContentMinHeightPx(2.9));
	}

	@Test
	@DisplayName("fits clip and full zoom, or clip and two compact tracks, in the minimum viewport")
	void fitsMinimumViewport() {
		double availableHeight = 180 - 24;
		assertTrue(TimelineLayout.getTimelineContentMinHeightPx(2) <= availableHeight);
		assertTrue(TimelineLayout.getTimelineContentMinHeightPx(3) <= availableHeight);
		assertTrue(TimelineLayout.getTimelineContentMinHeightPx(4) > availableHeight);
	}
}
