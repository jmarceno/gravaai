package dev.gravaai.ui.editor.timeline.core;

/**
 * Timeline wheel/pan math (port of {@code resolveTimelineWheelPanDeltaPx}, {@code clampedRange} and
 * {@code panTimelineRange} in {@code hooks/useTimelineRange.ts}).
 */
public final class TimelineRangeMath {

	/** Port of the wheel input object. */
	public record WheelInput(double deltaX, double deltaY, int deltaMode, boolean shiftKey,
			boolean ctrlKey, boolean metaKey, boolean canScrollVertically) {
	}

	private TimelineRangeMath() {
	}

	/**
	 * Port of {@code resolveTimelineWheelPanDeltaPx}: ctrl/meta (without shift) is reserved for
	 * zoom; horizontal deltas pan; shift or a non-scrollable page turns vertical deltas into pans.
	 */
	public static double resolveWheelPanDeltaPx(WheelInput input) {
		if ((input.ctrlKey() || input.metaKey()) && !input.shiftKey()) {
			return 0;
		}
		if (Math.abs(input.deltaX()) > 0) {
			return TimelineScale.normalizeWheelDeltaToPixels(input.deltaX(), input.deltaMode());
		}
		if ((input.shiftKey() || !input.canScrollVertically()) && Math.abs(input.deltaY()) > 0) {
			return TimelineScale.normalizeWheelDeltaToPixels(input.deltaY(), input.deltaMode());
		}
		return 0;
	}

	/** Port of the {@code clampedRange} memo: keep the range inside the timeline. */
	public static Span clampedRange(Span range, double totalMs) {
		if (totalMs == 0) {
			return range;
		}
		return new Span(Math.max(0, Math.min(range.start(), totalMs)),
				Math.min(range.end(), totalMs));
	}

	/** Port of {@code panTimelineRange}: same visible span, clamped to the timeline. */
	public static Span panRange(Span range, double totalMs, double deltaMs) {
		if (!Double.isFinite(deltaMs) || deltaMs == 0 || totalMs <= 0) {
			return range;
		}
		double visibleSpan = Math.max(1, range.end() - range.start());
		double maxStart = Math.max(0, totalMs - visibleSpan);
		double nextStart = Math.max(0, Math.min(range.start() + deltaMs, maxStart));
		return new Span(nextStart, nextStart + visibleSpan);
	}

	/** Converts a horizontal wheel delta in pixels into a pan delta in milliseconds. */
	public static double wheelPanMs(double deltaPx, double containerWidthPx, double visibleRangeMs) {
		if (containerWidthPx <= 0 || visibleRangeMs <= 0 || deltaPx == 0) {
			return 0;
		}
		return (deltaPx / containerWidthPx) * visibleRangeMs;
	}
}
