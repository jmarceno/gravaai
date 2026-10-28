package dev.gravaai.ui.editor.timeline.core;

/** Generic span math (port of {@code timeline/core/spans.ts}). */
public final class TimelineSpans {

	/** Port of the params object of {@code normalizeRegionSpan}. */
	public record RegionSpanRequest(double startMs, double endMs, double totalMs, double minDurationMs) {
	}

	private TimelineSpans() {
	}

	/** Port of {@code spansOverlap}: adjacent spans do not overlap. */
	public static boolean spansOverlap(Span left, Span right) {
		return left.end() > right.start() && left.start() < right.end();
	}

	/** Port of {@code normalizeRegionSpan}: clamp to the timeline and enforce a minimum duration. */
	public static Span normalizeRegionSpan(RegionSpanRequest request) {
		double safeTotalMs = Math.max(0, request.totalMs());
		double safeMinDurationMs = Math.max(0, Math.min(request.minDurationMs(), safeTotalMs));
		double clampedStart = Math.max(0, Math.min(request.startMs(), safeTotalMs));
		double normalizedStart = Math.max(0, Math.min(clampedStart, safeTotalMs - safeMinDurationMs));
		double normalizedEnd = Math.min(safeTotalMs,
				Math.max(request.endMs(), normalizedStart + safeMinDurationMs));
		return new Span(normalizedStart, normalizedEnd);
	}
}
