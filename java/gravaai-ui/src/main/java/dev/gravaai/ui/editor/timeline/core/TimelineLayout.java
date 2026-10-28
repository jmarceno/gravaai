package dev.gravaai.ui.editor.timeline.core;

/** Vertical layout of the timeline (port of {@code timeline/timelineLayout.ts}). */
public final class TimelineLayout {

	public static final double TIMELINE_AXIS_HEIGHT_PX = 20;
	public static final double TIMELINE_ROW_MIN_HEIGHT_PX = 32;
	public static final double TIMELINE_CLIP_ROW_HEIGHT_PX = TIMELINE_ROW_MIN_HEIGHT_PX * 2;

	private TimelineLayout() {
	}

	private static long normalizeRowCount(double rowCount) {
		if (!Double.isFinite(rowCount)) {
			return 0;
		}
		return Math.max(0, (long) Math.floor(rowCount));
	}

	/** Port of {@code getTimelineRowsMinHeightPx}. */
	public static double getTimelineRowsMinHeightPx(double rowCount) {
		long count = normalizeRowCount(rowCount);
		return count == 0
				? 0
				: TIMELINE_CLIP_ROW_HEIGHT_PX
						+ (count == 2 ? 2 : count - 1) * TIMELINE_ROW_MIN_HEIGHT_PX
						+ count * 2;
	}

	/** Port of {@code getTimelineContentMinHeightPx}. */
	public static double getTimelineContentMinHeightPx(double rowCount) {
		return TIMELINE_AXIS_HEIGHT_PX + getTimelineRowsMinHeightPx(rowCount);
	}
}
