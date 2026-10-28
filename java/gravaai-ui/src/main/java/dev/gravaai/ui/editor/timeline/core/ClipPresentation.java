package dev.gravaai.ui.editor.timeline.core;

import java.util.List;

/**
 * Clip/caption/region presentation on the timeline (port of
 * {@code timeline/core/clipPresentation.ts}).
 *
 * <p>Split clips get a decorative seam gutter in screen space; the media timeline itself never
 * gains a gap. These helpers map between the authored media time and the displayed span, and add
 * the tiny screen-space magnet that snaps region edges to cuts/edges.
 */
public final class ClipPresentation {

	/** Port of {@code CLIP_SEAM_GAP_PX}. */
	public static final double CLIP_SEAM_GAP_PX = 24;

	/** Port of the {@code ClipPresentation} type: media span plus its displayed span. */
	public record Presentation(Span span, Span displaySpan) {
	}

	/** Which side of a span snaps/seeks ({@code "start"} / {@code "end"} in the TS code). */
	public enum Edge {
		START,
		END
	}

	private ClipPresentation() {
	}

	/** Port of {@code getClipDisplaySpan}: visual gutters never become gaps in media time. */
	public static Span getClipDisplaySpan(Span span, List<Span> allClips, double msPerPixel) {
		if (!(msPerPixel > 0)) {
			return span;
		}
		boolean hasBefore = false;
		boolean hasAfter = false;
		for (Span other : allClips) {
			if (other == span) {
				continue;
			}
			if (other.end() <= span.start()) {
				hasBefore = true;
			}
			if (other.start() >= span.end()) {
				hasAfter = true;
			}
		}
		// Keep a visible body even for clips smaller than the normal seam gutter.
		double inset = Math.min((CLIP_SEAM_GAP_PX / 2) * msPerPixel, (span.end() - span.start()) / 3);
		return new Span(span.start() + (hasBefore ? inset : 0),
				span.end() - (hasAfter ? inset : 0));
	}

	/** Port of {@code getEmbeddedCaptionSpan}: clip caption previews to the owning filmstrip. */
	public static Span getEmbeddedCaptionSpan(Span span, List<Presentation> clips) {
		Presentation clip = null;
		for (Presentation candidate : clips) {
			if (span.start() < candidate.span().end() && span.end() > candidate.span().start()) {
				clip = candidate;
				break;
			}
		}
		if (clip == null) {
			return null;
		}
		double start = mapSpanTime(Math.max(span.start(), clip.span().start()), clip.span(),
				clip.displaySpan());
		double end = mapSpanTime(Math.min(span.end(), clip.span().end()), clip.span(),
				clip.displaySpan());
		return end > start ? new Span(start, end) : null;
	}

	/**
	 * Port of {@code getPlayheadDisplayTime}: intentionally skips across split-clip gutters,
	 * following iMovie-like logic (gaps consume zero timeline time).
	 */
	public static double getPlayheadDisplayTime(double time, List<Presentation> clips) {
		return getPlayheadDisplayTime(time, clips, Edge.START);
	}

	public static double getPlayheadDisplayTime(double time, List<Presentation> clips, Edge edge) {
		if (edge == Edge.END) {
			Presentation ending = find(clips, presentation -> time > presentation.span().start()
					&& time <= presentation.span().end());
			if (ending != null) {
				return mapSpanTime(time, ending.span(), ending.displaySpan());
			}
		}
		Presentation clip = find(clips, presentation -> time >= presentation.span().start()
				&& time < presentation.span().end());
		if (clip == null) {
			clip = find(clips, presentation -> time == presentation.span().end());
		}
		if (clip == null) {
			return time;
		}
		return mapSpanTime(time, clip.span(), clip.displaySpan());
	}

	/** Port of {@code getTimeAtClipSeam}: scrubbing a shared gutter selects the cut. */
	public static double getTimeAtClipSeam(double time, List<Presentation> clips) {
		for (Presentation left : clips) {
			Presentation right = find(clips, candidate -> candidate.span().start() == left.span().end());
			if (right != null && time >= left.displaySpan().end() && time <= right.displaySpan().start()) {
				return right.span().start();
			}
		}
		Presentation clip = find(clips, presentation -> time >= presentation.displaySpan().start()
				&& time <= presentation.displaySpan().end());
		return clip == null ? time : mapSpanTime(time, clip.displaySpan(), clip.span());
	}

	/** Port of {@code getRegionDisplaySpan}: both cut sides share a timestamp, different edges. */
	public static Span getRegionDisplaySpan(Span span, List<Presentation> clips) {
		return new Span(getPlayheadDisplayTime(span.start(), clips, Edge.START),
				getPlayheadDisplayTime(span.end(), clips, Edge.END));
	}

	/** Port of {@code snapRegionSpan}: a small screen-space magnet. */
	public static Span snapRegionSpan(Span span, List<Double> targets, List<Presentation> clips,
			double msPerPixel) {
		return snapRegionSpan(span, targets, clips, msPerPixel, null);
	}

	public static Span snapRegionSpan(Span span, List<Double> targets, List<Presentation> clips,
			double msPerPixel, Edge edge) {
		if (!(msPerPixel > 0)) {
			return span;
		}
		double best = 8 * msPerPixel;
		double delta = 0;
		Edge[] edges = edge != null ? new Edge[] {edge} : new Edge[] {Edge.START, Edge.END};
		for (Edge side : edges) {
			double position = getPlayheadDisplayTime(side == Edge.START ? span.start() : span.end(), clips,
					side);
			for (double target : targets) {
				double distance = Math.abs(position - getPlayheadDisplayTime(target, clips, side));
				if (distance <= best) {
					best = distance;
					delta = target - (side == Edge.START ? span.start() : span.end());
				}
			}
		}
		if (edge == Edge.START) {
			return new Span(span.start() + delta, span.end());
		}
		if (edge == Edge.END) {
			return new Span(span.start(), span.end() + delta);
		}
		return new Span(span.start() + delta, span.end() + delta);
	}

	private interface PresentationFilter {
		boolean matches(Presentation presentation);
	}

	private static Presentation find(List<Presentation> clips, PresentationFilter filter) {
		for (Presentation presentation : clips) {
			if (filter.matches(presentation)) {
				return presentation;
			}
		}
		return null;
	}

	private static double mapSpanTime(double time, Span from, Span to) {
		if (from.end() <= from.start()) {
			return to.start();
		}
		return to.start() + ((time - from.start()) / (from.end() - from.start()))
				* (to.end() - to.start());
	}
}
