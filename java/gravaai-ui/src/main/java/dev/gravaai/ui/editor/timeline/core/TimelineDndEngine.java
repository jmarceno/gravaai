package dev.gravaai.ui.editor.timeline.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Drag-and-drop span constraints for the timeline (port of {@code timeline/dnd/engine.ts}).
 *
 * <p>Pure geometry: clamp to the timeline, keep a minimum duration, respect row neighbours, and
 * model the primary clip row as an insertion sequence (a drag never leaves empty time there).
 */
public final class TimelineDndEngine {

	/** Port of the {@code hasOverlap} dependency: true when the span collides with another item. */
	@FunctionalInterface
	public interface OverlapCheck {
		boolean hasOverlap(Span span, String excludeId, String rowId);
	}

	/** Port of the {@code { totalMs, minItemDurationMs }} config slice. */
	public record SpanBounds(double totalMs, double minItemDurationMs) {
	}

	/** Port of the {@code { totalMs, minVisibleRangeMs }} config slice. */
	public record RangeBounds(double totalMs, double minVisibleRangeMs) {
	}

	/** Port of the neighbour-clamping config slice. */
	public record NeighbourConfig(List<TimelineRegionSpan> allRegionSpans, double minItemDurationMs,
			double totalMs) {
	}

	/** Port of the resize config slice. */
	public record ResizeConfig(double totalMs, double minItemDurationMs,
			List<TimelineRegionSpan> allRegionSpans, OverlapCheck hasOverlap) {
	}

	/** Port of the drag config slice. */
	public record DragConfig(List<TimelineRegionSpan> allRegionSpans, double totalMs,
			double minItemDurationMs, OverlapCheck hasOverlap) {
	}

	/** Port of the {@code { span, rowId }} drag result. */
	public record DragEnd(TimelineSpanResult span, String rowId) {
	}

	private TimelineDndEngine() {
	}

	/** Port of {@code clampSpanToBounds}. */
	public static Span clampSpanToBounds(Span span, SpanBounds config) {
		double totalMs = config.totalMs();
		double minItemDurationMs = config.minItemDurationMs();
		double rawDuration = Math.max(span.end() - span.start(), 0);
		double normalizedStart = Double.isFinite(span.start()) ? span.start() : 0;

		if (totalMs == 0) {
			double minDuration = Math.max(minItemDurationMs, 1);
			double duration = Math.max(rawDuration, minDuration);
			double start = Math.max(0, normalizedStart);
			return new Span(start, start + duration);
		}

		double minDuration = Math.min(Math.max(minItemDurationMs, 1), totalMs);
		double duration = Math.min(Math.max(rawDuration, minDuration), totalMs);
		double start = Math.max(0, Math.min(normalizedStart, totalMs - duration));
		return new Span(start, start + duration);
	}

	/** Port of {@code clampRange}. */
	public static Span clampRange(Span candidate, RangeBounds config) {
		double totalMs = config.totalMs();
		double minVisibleRangeMs = config.minVisibleRangeMs();
		if (totalMs == 0) {
			double minSpan = Math.max(minVisibleRangeMs, 1);
			double span = Math.max(candidate.end() - candidate.start(), minSpan);
			double start = Math.max(0, Math.min(candidate.start(), candidate.end() - span));
			return new Span(start, start + span);
		}

		double rawStart = Math.max(0, candidate.start());
		double rawEnd = candidate.end();
		double clampedEnd = Math.min(rawEnd, totalMs);
		double minSpan = Math.min(Math.max(minVisibleRangeMs, 1), totalMs);
		double desiredSpan = clampedEnd - rawStart;
		double span = Math.min(Math.max(desiredSpan, minSpan), totalMs);

		double finalStart = rawStart;
		double finalEnd = finalStart + span;
		if (finalEnd > totalMs) {
			finalEnd = totalMs;
			finalStart = Math.max(0, finalEnd - span);
		}
		return new Span(finalStart, finalEnd);
	}

	/** Port of {@code getSiblingSpans}: the other regions on the same row, left to right. */
	public static List<TimelineRegionSpan> getSiblingSpans(String activeItemId, String rowId,
			List<TimelineRegionSpan> allRegionSpans) {
		TimelineRegionSpan activeItem = find(allRegionSpans, activeItemId);
		String resolvedRowId = rowId != null ? rowId : activeItem == null ? null : activeItem.rowId();
		if (resolvedRowId == null) {
			return List.of();
		}
		List<TimelineRegionSpan> siblings = new ArrayList<>();
		for (TimelineRegionSpan region : allRegionSpans) {
			if (!region.id().equals(activeItemId) && resolvedRowId.equals(region.rowId())) {
				siblings.add(region);
			}
		}
		siblings.sort(Comparator.comparingDouble(TimelineRegionSpan::start));
		return List.copyOf(siblings);
	}

	/** Port of {@code clampResizedSpanToNeighbours}. */
	public static Span clampResizedSpanToNeighbours(Span span, String activeItemId,
			NeighbourConfig config) {
		List<TimelineRegionSpan> siblings = getSiblingSpans(activeItemId, null,
				config.allRegionSpans());
		TimelineRegionSpan activeItem = find(config.allRegionSpans(), activeItemId);
		double start = span.start();
		double end = span.end();

		for (TimelineRegionSpan sibling : siblings) {
			if (end > sibling.start() && start < sibling.start()) {
				end = sibling.start();
			}
			if (start < sibling.end() && end > sibling.end()) {
				start = sibling.end();
			}
		}

		double minDur = Math.min(config.minItemDurationMs(),
				config.totalMs() != 0 ? config.totalMs() : config.minItemDurationMs());
		if (end - start < minDur) {
			boolean resizedLeft = activeItem != null && span.start() != activeItem.start()
					&& span.end() == activeItem.end();
			if (resizedLeft) {
				start = end - minDur;
			} else {
				end = start + minDur;
			}
		}

		return new Span(Math.max(0, start),
				Math.min(end, config.totalMs() != 0 ? config.totalMs() : end));
	}

	/** Port of {@code clampDraggedSpanToNeighbours}. */
	public static TimelineSpanResult clampDraggedSpanToNeighbours(Span span, String activeItemId,
			String rowId, NeighbourConfig config) {
		double totalMs = config.totalMs();
		double minItemDurationMs = config.minItemDurationMs();
		TimelineRegionSpan activeItem = find(config.allRegionSpans(), activeItemId);
		if (activeItem == null) {
			return TimelineSpanResult.of(clampSpanToBounds(span, new SpanBounds(totalMs, minItemDurationMs)));
		}

		List<TimelineRegionSpan> siblings = getSiblingSpans(activeItemId, rowId, config.allRegionSpans());
		double duration = Math.max(activeItem.end() - activeItem.start(),
				Math.min(minItemDurationMs, totalMs != 0 ? totalMs : minItemDurationMs));
		double proposedStart = Double.isFinite(span.start()) ? span.start() : activeItem.start();

		if (TimelineConstants.CLIP_ROW_ID.equals(activeItem.rowId())
				&& TimelineConstants.CLIP_ROW_ID.equals(rowId != null ? rowId : activeItem.rowId())) {
			return resolveClipSequenceDrag(activeItem, siblings, proposedStart);
		}

		TimelineRegionSpan previousSibling = null;
		for (int index = siblings.size() - 1; index >= 0; index--) {
			if (siblings.get(index).end() <= activeItem.start()) {
				previousSibling = siblings.get(index);
				break;
			}
		}
		TimelineRegionSpan nextSibling = null;
		for (TimelineRegionSpan sibling : siblings) {
			if (sibling.start() >= activeItem.end()) {
				nextSibling = sibling;
				break;
			}
		}
		double minStart = previousSibling != null ? previousSibling.end() : 0;
		double maxStart = nextSibling != null
				? nextSibling.start() - duration
				: totalMs > 0 ? totalMs - duration : proposedStart;

		double start = Math.max(minStart, Math.min(proposedStart, maxStart));
		Span clamped = clampSpanToBounds(new Span(start, start + duration),
				new SpanBounds(totalMs, minItemDurationMs));
		return TimelineSpanResult.of(clamped);
	}

	/** Port of {@code resolveResizeEnd}; {@code null} when the resize is rejected. */
	public static Span resolveResizeEnd(String activeItemId, Span updatedSpan, ResizeConfig config) {
		double totalMs = config.totalMs();
		double minItemDurationMs = config.minItemDurationMs();
		TimelineRegionSpan activeItem = find(config.allRegionSpans(), activeItemId);
		if (activeItem != null && TimelineConstants.CLIP_ROW_ID.equals(activeItem.rowId())) {
			if (!Double.isFinite(updatedSpan.start()) || !Double.isFinite(updatedSpan.end())) {
				return null;
			}
			// Source limits are enforced by the clip command. Negative left edges and extension
			// beyond the old sequence end reveal trimmed source before repacking.
			double minDuration = Math.max(1, minItemDurationMs);
			boolean resizedLeft = updatedSpan.start() != activeItem.start()
					&& updatedSpan.end() == activeItem.end();
			return resizedLeft
					? new Span(Math.min(updatedSpan.start(), updatedSpan.end() - minDuration),
							updatedSpan.end())
					: new Span(updatedSpan.start(),
							Math.max(updatedSpan.end(), updatedSpan.start() + minDuration));
		}
		Span clamped = clampSpanToBounds(updatedSpan, new SpanBounds(totalMs, minItemDurationMs));
		double effectiveMinDuration = totalMs > 0 ? Math.min(minItemDurationMs, totalMs)
				: minItemDurationMs;
		if (clamped.end() - clamped.start() < effectiveMinDuration) {
			return null;
		}

		if (config.hasOverlap().hasOverlap(clamped, activeItemId, null)) {
			clamped = clampSpanToBounds(
					clampResizedSpanToNeighbours(clamped, activeItemId,
							new NeighbourConfig(config.allRegionSpans(), minItemDurationMs, totalMs)),
					new SpanBounds(totalMs, minItemDurationMs));
			if (config.hasOverlap().hasOverlap(clamped, activeItemId, null)) {
				return null;
			}
		}
		return clamped;
	}

	/** Port of {@code resolveDragEnd}; {@code null} when the drag is rejected. */
	public static DragEnd resolveDragEnd(String activeItemId, Span updatedSpan, String proposedRowId,
			DragConfig config, BiFunction<String, String, String> resolveTargetRowId) {
		double totalMs = config.totalMs();
		double minItemDurationMs = config.minItemDurationMs();
		String resolvedRowId = resolveTargetRowId != null
				? resolveTargetRowId.apply(activeItemId, proposedRowId)
				: proposedRowId;

		TimelineRegionSpan activeItem = find(config.allRegionSpans(), activeItemId);
		double originalDuration = activeItem != null
				? activeItem.end() - activeItem.start()
				: updatedSpan.end() - updatedSpan.start();
		Span dragSpan = new Span(updatedSpan.start(), updatedSpan.start() + originalDuration);
		if (activeItem != null && TimelineConstants.CLIP_ROW_ID.equals(activeItem.rowId())
				&& TimelineConstants.CLIP_ROW_ID.equals(resolvedRowId)) {
			double proposedStart = Double.isFinite(updatedSpan.start())
					? updatedSpan.start()
					: activeItem.start();
			return new DragEnd(resolveClipSequenceDrag(activeItem,
					getSiblingSpans(activeItemId, resolvedRowId, config.allRegionSpans()), proposedStart),
					resolvedRowId);
		}

		Span clamped = clampSpanToBounds(dragSpan, new SpanBounds(totalMs, minItemDurationMs));
		if (config.hasOverlap().hasOverlap(clamped, activeItemId, resolvedRowId)) {
			clamped = clampDraggedSpanToNeighbours(clamped, activeItemId, resolvedRowId,
					new NeighbourConfig(config.allRegionSpans(), minItemDurationMs, totalMs)).span();
			if (config.hasOverlap().hasOverlap(clamped, activeItemId, resolvedRowId)) {
				return null;
			}
		}
		return new DragEnd(TimelineSpanResult.of(clamped), resolvedRowId);
	}

	/** A primary-track drag inserts into the sequence; it never searches for empty time. */
	private static TimelineSpanResult resolveClipSequenceDrag(TimelineRegionSpan activeItem,
			List<TimelineRegionSpan> siblings, double proposedStart) {
		double duration = activeItem.end() - activeItem.start();
		double center = proposedStart + duration / 2;
		boolean movingRight = proposedStart > activeItem.start();
		int sequenceIndex = 0;
		for (TimelineRegionSpan sibling : siblings) {
			double siblingCenter = (sibling.start() + sibling.end()) / 2;
			if (movingRight ? siblingCenter <= center : siblingCenter < center) {
				sequenceIndex++;
			}
		}
		double start = 0;
		for (int index = 0; index < sequenceIndex; index++) {
			start += siblings.get(index).end() - siblings.get(index).start();
		}
		return new TimelineSpanResult(start, start + duration, sequenceIndex);
	}

	private static TimelineRegionSpan find(List<TimelineRegionSpan> regions, String id) {
		for (TimelineRegionSpan region : regions) {
			if (region.id().equals(id)) {
				return region;
			}
		}
		return null;
	}
}
