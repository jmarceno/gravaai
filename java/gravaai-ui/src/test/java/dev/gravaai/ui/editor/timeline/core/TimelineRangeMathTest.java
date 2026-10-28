package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the pure helpers in {@code hooks/useTimelineRange.ts}. */
class TimelineRangeMathTest {

	private static TimelineRangeMath.WheelInput wheel(double deltaX, double deltaY, boolean shift,
			boolean ctrl, boolean meta, boolean canScrollVertically) {
		return new TimelineRangeMath.WheelInput(deltaX, deltaY, 0, shift, ctrl, meta,
				canScrollVertically);
	}

	@Test
	@DisplayName("reserves ctrl/meta wheel for zooming")
	void reservesCtrlWheelForZoom() {
		assertEquals(0, TimelineRangeMath.resolveWheelPanDeltaPx(wheel(0, 120, false, true, false, true)));
		assertEquals(0, TimelineRangeMath.resolveWheelPanDeltaPx(wheel(0, 120, false, false, true, true)));
		assertEquals(0, TimelineRangeMath.resolveWheelPanDeltaPx(wheel(0, 0, false, false, false, true)));
	}

	@Test
	@DisplayName("pans with horizontal deltas and normalizes wheel delta modes")
	void pansWithHorizontalDeltas() {
		assertEquals(2, TimelineRangeMath.resolveWheelPanDeltaPx(wheel(2, 0, false, false, false, true)));
		assertEquals(-3,
				TimelineRangeMath.resolveWheelPanDeltaPx(wheel(-3, 0, false, false, false, true)));
		assertEquals(-48, TimelineRangeMath.resolveWheelPanDeltaPx(
				new TimelineRangeMath.WheelInput(-3, 0, 1, false, false, false, true)));
	}

	@Test
	@DisplayName("turns shift or non-scrolling vertical wheel into a pan")
	void turnsVerticalWheelIntoPan() {
		assertEquals(120, TimelineRangeMath.resolveWheelPanDeltaPx(wheel(0, 120, true, false, false, true)));
		assertEquals(120,
				TimelineRangeMath.resolveWheelPanDeltaPx(wheel(0, 120, false, false, false, false)));
		assertEquals(0,
				TimelineRangeMath.resolveWheelPanDeltaPx(wheel(0, 120, false, false, false, true)));
	}

	@Test
	@DisplayName("clamps the range to the timeline and keeps zero-duration ranges")
	void clampsRange() {
		assertEquals(new Span(0, 600), TimelineRangeMath.clampedRange(new Span(-50, 600), 1000));
		assertEquals(new Span(200, 1000), TimelineRangeMath.clampedRange(new Span(200, 1200), 1000));
		assertEquals(new Span(0, 500), TimelineRangeMath.clampedRange(new Span(0, 500), 0));
	}

	@Test
	@DisplayName("pans with a fixed visible span and clamps both edges")
	void pansRange() {
		assertEquals(new Span(0, 5000), TimelineRangeMath.panRange(new Span(0, 5000), 5000, 100));
		assertEquals(new Span(0, 2000), TimelineRangeMath.panRange(new Span(1000, 3000), 5000, -2000));
		assertEquals(new Span(3000, 5000),
				TimelineRangeMath.panRange(new Span(1000, 3000), 5000, 10000));
		assertEquals(new Span(1000, 3000), TimelineRangeMath.panRange(new Span(1000, 3000), 5000, 0));
		assertEquals(new Span(1000, 3000), TimelineRangeMath.panRange(new Span(1000, 3000), 0, 500));
	}

	@Test
	@DisplayName("converts a wheel pixel delta into milliseconds")
	void convertsWheelPixelsToMs() {
		assertEquals(500, TimelineRangeMath.wheelPanMs(100, 1000, 5000));
		assertEquals(0, TimelineRangeMath.wheelPanMs(100, 0, 5000));
		assertEquals(0, TimelineRangeMath.wheelPanMs(0, 1000, 0));
	}
}