package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/** Port of {@code timeline/core/time.test.ts}. */
class TimelineScaleTest {

	@Test
	@DisplayName("creates fallback range for empty or invalid duration")
	void createsFallbackRange() {
		assertEquals(new Span(0, 1000), TimelineScale.createInitialRange(0));
		assertEquals(new Span(0, 1000), TimelineScale.createInitialRange(-10));
		assertEquals(new Span(0, 2500), TimelineScale.createInitialRange(2500));
	}

	@Test
	@DisplayName("computes scale defaults and caps")
	void computesScaleDefaultsAndCaps() {
		assertEquals(new TimelineScale.Config(100, 1000, 300), TimelineScale.calculateTimelineScale(0));
		assertEquals(100, TimelineScale.calculateTimelineScale(1).defaultItemDurationMs());
		assertEquals(5000, TimelineScale.calculateTimelineScale(100).defaultItemDurationMs());
		assertEquals(30000, TimelineScale.calculateTimelineScale(10_000).defaultItemDurationMs());
	}

	@Test
	@DisplayName("formats timeline labels in fractional, whole-second, and hour modes")
	void formatsTimelineLabels() {
		assertEquals("0:01.23", TimelineScale.formatTimeLabel(1234, 100));
		assertEquals("0:01.2", TimelineScale.formatTimeLabel(1234, 500));
		assertEquals("1:01", TimelineScale.formatTimeLabel(61_900, 1000));
		assertEquals("1:01:01", TimelineScale.formatTimeLabel(3_661_999, 1000));
	}

	@Test
	@DisplayName("formats playhead labels for sub-minute and minute timelines")
	void formatsPlayheadLabels() {
		assertEquals("1.2s", TimelineScale.formatPlayheadTime(1234));
		assertEquals("1:01.4", TimelineScale.formatPlayheadTime(61_400));
	}

	@Test
	@DisplayName("normalizes wheel delta by deltaMode")
	void normalizesWheelDelta() {
		assertEquals(2, TimelineScale.normalizeWheelDeltaToPixels(2, 0));
		assertEquals(32, TimelineScale.normalizeWheelDeltaToPixels(2, 1));
		assertEquals(480, TimelineScale.normalizeWheelDeltaToPixels(2, 2));
		assertEquals(-48, TimelineScale.normalizeWheelDeltaToPixels(-3, 1));
	}

	@Test
	@DisplayName("picks fine and coarse axis scales based on visible range")
	void picksAxisScales() {
		TimelineScale.AxisScale tiny = TimelineScale.calculateAxisScale(1, null);
		TimelineScale.AxisScale typical = TimelineScale.calculateAxisScale(2000, null);
		TimelineScale.AxisScale huge = TimelineScale.calculateAxisScale(24 * 60 * 60 * 1000.0, null);

		assertTrue(tiny.intervalMs() > 0);
		assertTrue(tiny.gridMs() > 0);
		assertTrue(typical.intervalMs() >= tiny.intervalMs());
		assertTrue(huge.intervalMs() >= typical.intervalMs());
	}

	@Test
	@DisplayName("carries fractional rounding into the next minute")
	void carriesFractionalRounding() {
		assertEquals("1:00.00", TimelineScale.formatTimeLabel(59_999, 100));
		assertEquals("1:00.0", TimelineScale.formatPlayheadTime(59_999));
		assertEquals("1:00:00.00", TimelineScale.formatTimeLabel(3_599_999, 100));
	}

	@Test
	@DisplayName("reduces tick density in narrow windows")
	void reducesTickDensityInNarrowWindows() {
		assertTrue(TimelineScale.calculateAxisScale(10_000, 400.0).intervalMs()
				> TimelineScale.calculateAxisScale(10_000, 1600.0).intervalMs());
	}
}
