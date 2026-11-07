package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Instrument-mock geometry: a 22px ruler over uniform 44px lanes, no gaps ({@code .lane} is
 * 44px and every row is a lane).
 */
class TimelineLayoutTest {

	@Test
	@DisplayName("every lane is one uniform 44px row, with no gaps")
	void reservesVerticalSpace() {
		assertEquals(5 * TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX,
				TimelineLayout.getTimelineRowsMinHeightPx(5));
		assertEquals(TimelineLayout.TIMELINE_AXIS_HEIGHT_PX
						+ 5 * TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX,
				TimelineLayout.getTimelineContentMinHeightPx(5));
		assertEquals(TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX,
				TimelineLayout.TIMELINE_CLIP_ROW_HEIGHT_PX);
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
		assertEquals(2 * TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX,
				TimelineLayout.getTimelineRowsMinHeightPx(2.9));
		assertEquals(TimelineLayout.TIMELINE_AXIS_HEIGHT_PX
						+ 2 * TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX,
				TimelineLayout.getTimelineContentMinHeightPx(2.9));
	}

	@Test
	@DisplayName("four lanes plus the toolbar fit the raised default strip without scrolling")
	void fitsDefaultStrip() {
		// Toolbar 30 + ruler 22 + 4 x 44 lanes = 228, inside the 400px default cap.
		double strip = 30 + TimelineLayout.getTimelineContentMinHeightPx(4);
		assertTrue(strip <= 400);
		assertTrue(TimelineLayout.TIMELINE_ROW_MIN_HEIGHT_PX == 44);
	}
}
