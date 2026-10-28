package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code timeline/core/spans.test.ts}. */
class TimelineSpansTest {

	@Test
	@DisplayName("treats adjacent spans as non-overlapping")
	void treatsAdjacentSpansAsNonOverlapping() {
		assertFalse(TimelineSpans.spansOverlap(new Span(0, 100), new Span(100, 200)));
	}

	@Test
	@DisplayName("detects strict overlap and containment")
	void detectsOverlap() {
		assertTrue(TimelineSpans.spansOverlap(new Span(0, 101), new Span(100, 200)));
		assertTrue(TimelineSpans.spansOverlap(new Span(50, 150), new Span(75, 100)));
	}

	@Test
	@DisplayName("normalizes region to bounds and enforces min duration")
	void normalizesRegionToBounds() {
		assertEquals(new Span(0, 100),
				TimelineSpans.normalizeRegionSpan(
						new TimelineSpans.RegionSpanRequest(-50, 10, 1000, 100)));
	}

	@Test
	@DisplayName("clamps start when requested end exceeds total")
	void clampsStartWhenEndExceedsTotal() {
		assertEquals(new Span(900, 1000),
				TimelineSpans.normalizeRegionSpan(
						new TimelineSpans.RegionSpanRequest(980, 1200, 1000, 100)));
	}

	@Test
	@DisplayName("keeps already valid spans unchanged")
	void keepsValidSpans() {
		assertEquals(new Span(100, 300),
				TimelineSpans.normalizeRegionSpan(
						new TimelineSpans.RegionSpanRequest(100, 300, 1000, 50)));
	}

	@Test
	@DisplayName("never exceeds total when min duration is larger than total")
	void neverExceedsTotal() {
		assertEquals(new Span(0, 80),
				TimelineSpans.normalizeRegionSpan(
						new TimelineSpans.RegionSpanRequest(100, 300, 80, 100)));
	}
}
