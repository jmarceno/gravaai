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
	@DisplayName("formats timeline labels as zero-padded MM:SS (Instrument mock ruler)")
	void formatsTimelineLabels() {
		assertEquals("00:01.23", TimelineScale.formatTimeLabel(1234, 100));
		assertEquals("00:01.2", TimelineScale.formatTimeLabel(1234, 500));
		assertEquals("01:01", TimelineScale.formatTimeLabel(61_900, 1000));
		assertEquals("00:05", TimelineScale.formatTimeLabel(5_000, 5_000));
		assertEquals("00:00", TimelineScale.formatTimeLabel(0, 5_000));
		assertEquals("01:01:01", TimelineScale.formatTimeLabel(3_661_999, 1000));
	}

	@Test
	@DisplayName("formats the playhead tag as HH:MM:SS (mock .playhead .tag)")
	void formatsPlayheadLabels() {
		assertEquals("00:00:01", TimelineScale.formatPlayheadTime(1234));
		assertEquals("00:01:01", TimelineScale.formatPlayheadTime(61_400));
		assertEquals("00:14:22", TimelineScale.formatPlayheadTime(14 * 60_000 + 22_034));
		assertEquals("00:00:00", TimelineScale.formatPlayheadTime(-5));
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
	@DisplayName("ruler labels round, the playhead tag truncates like a timecode")
	void carriesFractionalRounding() {
		assertEquals("01:00.00", TimelineScale.formatTimeLabel(59_999, 100));
		assertEquals("00:00:59", TimelineScale.formatPlayheadTime(59_999));
		assertEquals("01:00:00.00", TimelineScale.formatTimeLabel(3_599_999, 100));
	}

	@Test
	@DisplayName("reduces tick density in narrow windows")
	void reducesTickDensityInNarrowWindows() {
		assertTrue(TimelineScale.calculateAxisScale(10_000, 400.0).intervalMs()
				> TimelineScale.calculateAxisScale(10_000, 1600.0).intervalMs());
	}
}
