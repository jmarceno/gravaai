package dev.gravaai.ui.editor.timeline.core;

/**
 * Ruler scale helpers (port of {@code timeline/core/time.ts}).
 *
 * <p>Everything here is pure geometry/labels: the axis tick interval and grid step for a visible
 * range, the item-size defaults, wheel normalization and the ruler/playhead labels.
 */
public final class TimelineScale {

	/** Port of the {@code SCALE_CANDIDATES} table, in the same order. */
	private record Candidate(double intervalSeconds, double gridSeconds) {
	}

	private static final Candidate[] SCALE_CANDIDATES = {
		new Candidate(0.05, 0.01),
		new Candidate(0.1, 0.02),
		new Candidate(0.25, 0.05),
		new Candidate(0.5, 0.1),
		new Candidate(1, 0.25),
		new Candidate(2, 0.5),
		new Candidate(5, 1),
		new Candidate(10, 2),
		new Candidate(15, 3),
		new Candidate(30, 5),
		new Candidate(60, 10),
		new Candidate(120, 20),
		new Candidate(300, 30),
		new Candidate(600, 60),
		new Candidate(900, 120),
		new Candidate(1800, 180),
		new Candidate(3600, 300),
	};

	/** Port of the {@code TimelineScaleConfig} interface. */
	public record Config(long minItemDurationMs, long defaultItemDurationMs, long minVisibleRangeMs) {
	}

	/** Port of the {@code { intervalMs, gridMs }} return object. */
	public record AxisScale(long intervalMs, long gridMs) {
	}

	private TimelineScale() {
	}

	/** Port of {@code calculateAxisScale}. */
	public static AxisScale calculateAxisScale(double visibleRangeMs, Double widthPx) {
		double visibleSeconds = visibleRangeMs / 1000;
		int markerCount = widthPx != null
				? Math.max(2, (int) Math.floor(widthPx / 80))
				: TimelineConstants.TARGET_MARKER_COUNT;
		Candidate candidate = SCALE_CANDIDATES[SCALE_CANDIDATES.length - 1];
		for (Candidate scaleCandidate : SCALE_CANDIDATES) {
			if (visibleSeconds <= 0 || visibleSeconds / scaleCandidate.intervalSeconds() <= markerCount) {
				candidate = scaleCandidate;
				break;
			}
		}
		return new AxisScale(Math.round(candidate.intervalSeconds() * 1000),
				Math.round(candidate.gridSeconds() * 1000));
	}

	/** Port of {@code calculateTimelineScale}. */
	public static Config calculateTimelineScale(double durationSeconds) {
		double totalMs = Math.max(0, Math.round(durationSeconds * 1000));
		long minItemDurationMs = 100;
		long defaultItemDurationMs = totalMs > 0
				? Math.max(minItemDurationMs, Math.min(Math.round(totalMs * 0.05), 30000))
				: Math.max(minItemDurationMs, 1000);
		return new Config(minItemDurationMs, defaultItemDurationMs, 300);
	}

	/** Port of {@code createInitialRange}. */
	public static Span createInitialRange(double totalMs) {
		if (totalMs > 0) {
			return new Span(0, totalMs);
		}
		return new Span(0, TimelineConstants.FALLBACK_RANGE_MS);
	}

	/** Port of {@code normalizeWheelDeltaToPixels}. */
	public static double normalizeWheelDeltaToPixels(double delta, int deltaMode) {
		if (deltaMode == 1) {
			return delta * 16;
		}
		if (deltaMode == 2) {
			return delta * 240;
		}
		return delta;
	}

	/**
	 * Ruler labels (mock {@code .ruler span}): zero-padded {@code MM:SS}, centiseconds only when
	 * the tick interval drops under a second, {@code HH:MM:SS} past an hour.
	 */
	public static String formatTimeLabel(double milliseconds, double intervalMs) {
		int digits = intervalMs < 250 ? 2 : intervalMs < 1000 ? 1 : 0;
		double factor = Math.pow(10, digits);
		double safeMs = Double.isFinite(milliseconds) ? Math.max(0, milliseconds) : 0;
		double total = (digits > 0 ? Math.round((safeMs / 1000) * factor) : Math.floor(safeMs / 1000))
				/ factor;
		long hours = (long) Math.floor(total / 3600);
		long minutes = (long) Math.floor(total / 60) % 60;
		// Zero-pad the seconds to a fixed width; the decimal point only exists with digits.
		int secondsWidth = 2 + digits + (digits > 0 ? 1 : 0);
		String seconds = String.format(java.util.Locale.ROOT, "%0" + secondsWidth + "." + digits + "f",
				total % 60);
		return hours != 0
				? String.format(java.util.Locale.ROOT, "%02d:%02d:%s", hours, minutes, seconds)
				: String.format(java.util.Locale.ROOT, "%02d:%s", minutes, seconds);
	}

	/** Playhead tag over the ruler (mock {@code .playhead .tag}): {@code HH:MM:SS}, truncated. */
	public static String formatPlayheadTime(double ms) {
		double safeMs = Double.isFinite(ms) ? Math.max(0, ms) : 0;
		long total = (long) Math.floor(safeMs / 1000);
		return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
				total / 3600, (total % 3600) / 60, total % 60);
	}
}
