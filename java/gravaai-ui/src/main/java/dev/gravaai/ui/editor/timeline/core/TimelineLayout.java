package dev.gravaai.ui.editor.timeline.core;

/**
 * Vertical layout of the timeline, restyled to the Instrument mock
 * ({@code docs/mocks/editor-instrument.html}): a 22px ruler over uniform 44px lanes separated by
 * 1px hairlines instead of gaps.
 */
public final class TimelineLayout {

	/** Mock {@code .ruler} height; the track-header corner cell matches it. */
	public static final double TIMELINE_AXIS_HEIGHT_PX = 22;

	/** Mock {@code .lane} height — every lane, clip row included, is 44px tall. */
	public static final double TIMELINE_ROW_MIN_HEIGHT_PX = 44;

	/** Legacy alias: the clip row is one lane like every other row now. */
	public static final double TIMELINE_CLIP_ROW_HEIGHT_PX = TIMELINE_ROW_MIN_HEIGHT_PX;

	private TimelineLayout() {
	}

	private static long normalizeRowCount(double rowCount) {
		if (!Double.isFinite(rowCount)) {
			return 0;
		}
		return Math.max(0, (long) Math.floor(rowCount));
	}

	/** Port of {@code getTimelineRowsMinHeightPx}: lanes stack with no gaps. */
	public static double getTimelineRowsMinHeightPx(double rowCount) {
		long count = normalizeRowCount(rowCount);
		return count * TIMELINE_ROW_MIN_HEIGHT_PX;
	}

	/** Port of {@code getTimelineContentMinHeightPx}. */
	public static double getTimelineContentMinHeightPx(double rowCount) {
		return TIMELINE_AXIS_HEIGHT_PX + getTimelineRowsMinHeightPx(rowCount);
	}
}
