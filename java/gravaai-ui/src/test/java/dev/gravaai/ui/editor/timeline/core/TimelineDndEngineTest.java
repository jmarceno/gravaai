package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code timeline/dnd/engine.test.ts}. */
class TimelineDndEngineTest {

	private static final List<TimelineRegionSpan> BASE_SPANS = List.of(
			new TimelineRegionSpan("a", 0, 1000, "row-clip"),
			new TimelineRegionSpan("b", 1500, 2500, "row-clip"),
			new TimelineRegionSpan("c", 3000, 3600, "row-clip"),
			new TimelineRegionSpan("aud-1", 100, 500, "row-audio-0"));

	private static final TimelineDndEngine.SpanBounds BOUNDS =
			new TimelineDndEngine.SpanBounds(5000, 100);

	@Test
	@DisplayName("clamps item span to timeline bounds and min duration")
	void clampsSpanToBounds() {
		assertEquals(new Span(0, 120),
				TimelineDndEngine.clampSpanToBounds(new Span(-100, 20), BOUNDS));
		assertEquals(new Span(2900, 5000),
				TimelineDndEngine.clampSpanToBounds(new Span(4900, 7000), BOUNDS));
	}

	@Test
	@DisplayName("handles zero-duration timelines in span clamping")
	void handlesZeroDurationTimelines() {
		assertEquals(new Span(0, 100), TimelineDndEngine.clampSpanToBounds(new Span(-10, -5),
				new TimelineDndEngine.SpanBounds(0, 100)));
		assertEquals(new Span(50, 60), TimelineDndEngine.clampSpanToBounds(new Span(50, 60),
				new TimelineDndEngine.SpanBounds(0, 1)));
	}

	@Test
	@DisplayName("clamps visible range for bounded and unbounded timelines")
	void clampsVisibleRange() {
		assertEquals(new Span(4700, 5000), TimelineDndEngine.clampRange(new Span(4900, 5200),
				new TimelineDndEngine.RangeBounds(5000, 300)));
		assertEquals(new Span(0, 300), TimelineDndEngine.clampRange(new Span(-20, 50),
				new TimelineDndEngine.RangeBounds(0, 300)));
	}

	@Test
	@DisplayName("resolves siblings by row and active item")
	void resolvesSiblings() {
		assertEquals(List.of("a", "c"), TimelineDndEngine.getSiblingSpans("b", null, BASE_SPANS)
				.stream().map(TimelineRegionSpan::id).toList());
		assertEquals(List.of("a", "b", "c"), TimelineDndEngine.getSiblingSpans("missing", "row-clip",
				BASE_SPANS).stream().map(TimelineRegionSpan::id).toList());
		assertEquals(List.of(), TimelineDndEngine.getSiblingSpans("missing", null, BASE_SPANS));
	}

	@Test
	@DisplayName("clamps resize against nearest neighbours and min duration")
	void clampsResizeAgainstNeighbours() {
		TimelineDndEngine.NeighbourConfig config =
				new TimelineDndEngine.NeighbourConfig(BASE_SPANS, 100, 5000);
		Span resizedRight = TimelineDndEngine.clampResizedSpanToNeighbours(
				new Span(900, 2000), "a", config);
		assertEquals(1500, resizedRight.end());

		Span resizedLeft = TimelineDndEngine.clampResizedSpanToNeighbours(
				new Span(900, 2500), "b", config);
		assertEquals(1000, resizedLeft.start());
	}

	@Test
	@DisplayName("inserts into a compact sequence even when legacy positions contain gaps")
	void insertsIntoCompactSequence() {
		TimelineSpanResult result = TimelineDndEngine.clampDraggedSpanToNeighbours(
				new Span(1400, 2400), "b", "row-clip",
				new TimelineDndEngine.NeighbourConfig(BASE_SPANS, 100, 5000));
		assertEquals(new TimelineSpanResult(1000, 2000, 1), result);
	}

	@Test
	@DisplayName("falls back to generic clamping when active drag item is unknown")
	void fallsBackToGenericClamping() {
		TimelineSpanResult dragged = TimelineDndEngine.clampDraggedSpanToNeighbours(
				new Span(-10, 20), "missing", "row-clip",
				new TimelineDndEngine.NeighbourConfig(BASE_SPANS, 100, 5000));
		assertEquals(new Span(0, 100), dragged.span());
		assertNull(dragged.sequenceIndex());
	}

	@Test
	@DisplayName("resolves resize end with overlap fallback semantics")
	void resolvesResizeEndWithOverlapFallback() {
		Span result = TimelineDndEngine.resolveResizeEnd("aud-1", new Span(100, 2200),
				new TimelineDndEngine.ResizeConfig(5000, 100,
						List.of(new TimelineRegionSpan("a", 0, 1000, "row-clip"),
								new TimelineRegionSpan("b", 1500, 2500, "row-clip"),
								new TimelineRegionSpan("c", 3000, 3600, "row-clip"),
								new TimelineRegionSpan("aud-1", 100, 500, "row-audio-0"),
								new TimelineRegionSpan("aud-2", 1500, 2500, "row-audio-0")),
						(span, excludeId, rowId) -> span.end() > 1500));
		assertEquals(new Span(100, 1500), result);
	}

	@Test
	@DisplayName("returns null when resize still overlaps after neighbour clamp")
	void rejectsResizeWhenStillOverlapping() {
		Span result = TimelineDndEngine.resolveResizeEnd("aud-1", new Span(100, 2200),
				new TimelineDndEngine.ResizeConfig(5000, 100, BASE_SPANS,
						(span, excludeId, rowId) -> true));
		assertNull(result);
	}

	private static final List<TimelineRegionSpan> SEQUENCE = List.of(
			new TimelineRegionSpan("a", 0, 1000, "row-clip"),
			new TimelineRegionSpan("b", 1000, 2000, "row-clip"),
			new TimelineRegionSpan("c", 2000, 3000, "row-clip"));

	private static final TimelineDndEngine.DragConfig SEQUENCE_CONFIG =
			new TimelineDndEngine.DragConfig(SEQUENCE, 3000, 100,
					(span, excludeId, rowId) -> true);

	@Test
	@DisplayName("inserts A after B without jumping over C")
	void insertsAAfterB() {
		TimelineDndEngine.DragEnd result = TimelineDndEngine.resolveDragEnd("a",
				new Span(1100, 2100), "row-clip", SEQUENCE_CONFIG, null);
		assertEquals("row-clip", result.rowId());
		assertEquals(new TimelineSpanResult(1000, 2000, 1), result.span());
	}

	@Test
	@DisplayName("inserts B before A at the start of the sequence")
	void insertsBBeforeA() {
		TimelineDndEngine.DragEnd result = TimelineDndEngine.resolveDragEnd("b",
				new Span(0, 1000), "row-clip", SEQUENCE_CONFIG, null);
		assertEquals("row-clip", result.rowId());
		assertEquals(new TimelineSpanResult(0, 1000, 0), result.span());
	}

	@Test
	@DisplayName("keeps the final clip in the sequence when dragged beyond its end")
	void keepsFinalClipInSequence() {
		TimelineDndEngine.DragEnd result = TimelineDndEngine.resolveDragEnd("c",
				new Span(5200, 5800), "row-clip", SEQUENCE_CONFIG, null);
		assertEquals("row-clip", result.rowId());
		assertEquals(new TimelineSpanResult(2000, 3000, 2), result.span());
	}

	@Test
	@DisplayName("inserts a middle clip after the final clip without adding empty time")
	void insertsMiddleClipAfterFinal() {
		TimelineDndEngine.DragEnd result = TimelineDndEngine.resolveDragEnd("b",
				new Span(4200, 5200), "row-clip", SEQUENCE_CONFIG, null);
		assertEquals("row-clip", result.rowId());
		assertEquals(new TimelineSpanResult(2000, 3000, 2), result.span());
	}

	@Test
	@DisplayName("uses the resolved target row for primary sequence insertion")
	void usesResolvedTargetRow() {
		TimelineDndEngine.DragEnd result = TimelineDndEngine.resolveDragEnd("a",
				new Span(1100, 2100), "row-audio-0", SEQUENCE_CONFIG,
				(activeItemId, proposedRowId) -> "row-clip");
		assertEquals(1, result.span().sequenceIndex());
	}

	@Test
	@DisplayName("allows restoring a trimmed clip into its adjacent clip's old time")
	void allowsRestoringTrimmedClip() {
		assertEquals(new Span(0, 1500), TimelineDndEngine.resolveResizeEnd("a",
				new Span(0, 1500), new TimelineDndEngine.ResizeConfig(3000, 100, SEQUENCE,
						(span, excludeId, rowId) -> true)));
	}

	@Test
	@DisplayName("allows restoring the final clip beyond the current sequence end")
	void allowsRestoringFinalClip() {
		assertEquals(new Span(2000, 4000), TimelineDndEngine.resolveResizeEnd("c",
				new Span(2000, 4000), new TimelineDndEngine.ResizeConfig(3000, 100, SEQUENCE,
						(span, excludeId, rowId) -> true)));
	}

	@Test
	@DisplayName("allows restoring a trimmed first clip before time zero for source-aware repacking")
	void allowsRestoringFirstClipBeforeZero() {
		assertEquals(new Span(-500, 1000), TimelineDndEngine.resolveResizeEnd("a",
				new Span(-500, 1000), new TimelineDndEngine.ResizeConfig(3000, 100, SEQUENCE,
						(span, excludeId, rowId) -> true)));
	}

	@Test
	@DisplayName("preserves the stationary edge when a clip trim crosses its minimum duration")
	void preservesStationaryEdgeOnTrim() {
		TimelineDndEngine.ResizeConfig config = new TimelineDndEngine.ResizeConfig(3000, 100,
				SEQUENCE, (span, excludeId, rowId) -> true);
		assertEquals(new Span(1900, 2000), TimelineDndEngine.resolveResizeEnd("b",
				new Span(1990, 2000), config));
		assertEquals(new Span(1000, 1100), TimelineDndEngine.resolveResizeEnd("b",
				new Span(1000, 1010), config));
	}

	@Test
	@DisplayName("rejects non-finite clip resize positions")
	void rejectsNonFiniteResize() {
		assertNull(TimelineDndEngine.resolveResizeEnd("a", new Span(Double.NaN, 1000),
				new TimelineDndEngine.ResizeConfig(3000, 100, SEQUENCE,
						(span, excludeId, rowId) -> true)));
	}

	@Test
	@DisplayName("keeps non-clip drags bounded by the current timeline duration")
	void keepsNonClipDragsBounded() {
		TimelineDndEngine.DragEnd result = TimelineDndEngine.resolveDragEnd("aud-1",
				new Span(5200, 5600), "row-audio-0",
				new TimelineDndEngine.DragConfig(BASE_SPANS, 5000, 100,
						(span, excludeId, rowId) -> false), null);
		assertEquals("row-audio-0", result.rowId());
		assertEquals(new Span(4600, 5000), result.span().span());
		assertNull(result.span().sequenceIndex());
	}

	@Test
	@DisplayName("returns null when drag still overlaps after neighbour clamp")
	void rejectsDragWhenStillOverlapping() {
		TimelineDndEngine.DragEnd result = TimelineDndEngine.resolveDragEnd("aud-1",
				new Span(1200, 1800), "row-audio-0",
				new TimelineDndEngine.DragConfig(BASE_SPANS, 5000, 100,
						(span, excludeId, rowId) -> true), null);
		assertNull(result);
	}

	@Test
	@DisplayName("keeps proposed row when no target row resolver is provided")
	void keepsProposedRowWithoutResolver() {
		TimelineDndEngine.DragEnd result = TimelineDndEngine.resolveDragEnd("aud-1",
				new Span(700, 1000), "row-audio-2",
				new TimelineDndEngine.DragConfig(BASE_SPANS, 5000, 100,
						(span, excludeId, rowId) -> false), null);
		assertEquals("row-audio-2", result.rowId());
	}
}
