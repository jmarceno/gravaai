package dev.gravaai.core.project;

import java.util.List;
import java.util.Optional;

/**
 * A stretch of the timeline where the camera uses {@code layout} instead of the project's webcam
 * settings (the Camera layout track), with optional {@code style} overrides of the webcam look.
 * Segments never overlap; the gaps between them use the project layout. {@code transition} is
 * how the camera arrives at this segment's start and {@code transitionOut} how it leaves at its
 * end when a gap follows (when another segment touches the end, that segment's {@code transition}
 * governs): an instant cut or a smooth move.
 */
public record CameraLayoutSegment(String id, long startMs, long endMs, CameraLayout layout,
		CameraLayoutStyle style, Transition transition, Transition transitionOut) {

	/** How the camera gets from the previous layout to this one, or leaves to the next one. */
	public enum Transition {
		CUT("cut"), SMOOTH("smooth");

		private final String wire;

		Transition(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

		public static Transition fromWire(String value) {
			return "smooth".equals(value) ? SMOOTH : CUT;
		}
	}

	/** Length of a smooth switch. */
	public static final long SMOOTH_MS = 300;

	public CameraLayoutSegment {
		if (id.isBlank()) {
			throw new IllegalArgumentException("camera layout segment id must not be blank");
		}
		if (endMs < startMs) {
			endMs = startMs;
		}
	}

	public CameraLayoutSegment(String id, long startMs, long endMs, CameraLayout layout) {
		this(id, startMs, endMs, layout, CameraLayoutStyle.INHERIT, Transition.CUT);
	}

	public CameraLayoutSegment(String id, long startMs, long endMs, CameraLayout layout,
			CameraLayoutStyle style, Transition transition) {
		this(id, startMs, endMs, layout, style, transition, Transition.CUT);
	}

	public CameraLayoutSegment withSpan(long nextStartMs, long nextEndMs) {
		return new CameraLayoutSegment(id, nextStartMs, nextEndMs, layout, style, transition,
				transitionOut);
	}

	public CameraLayoutSegment withLayout(CameraLayout nextLayout) {
		return new CameraLayoutSegment(id, startMs, endMs, nextLayout, style, transition,
				transitionOut);
	}

	public CameraLayoutSegment withStyle(CameraLayoutStyle nextStyle) {
		return new CameraLayoutSegment(id, startMs, endMs, layout, nextStyle, transition,
				transitionOut);
	}

	public CameraLayoutSegment withTransition(Transition nextTransition) {
		return new CameraLayoutSegment(id, startMs, endMs, layout, style, nextTransition,
				transitionOut);
	}

	public CameraLayoutSegment withTransitionOut(Transition nextTransitionOut) {
		return new CameraLayoutSegment(id, startMs, endMs, layout, style, transition,
				nextTransitionOut);
	}

	public boolean contains(double timeMs) {
		return timeMs >= startMs && timeMs < endMs;
	}

	/**
	 * {@code true} when no segment starts where this one ends: the stretch after it is a gap and
	 * the project layout applies, so {@code transitionOut} decides how the camera leaves.
	 */
	public boolean followedByGap(List<CameraLayoutSegment> segments) {
		return segments.stream()
				.noneMatch(other -> !other.id().equals(id) && other.startMs() == endMs);
	}

	/** The segment covering a timeline time (segments are kept in time order, non-overlapping). */
	public static Optional<CameraLayoutSegment> at(List<CameraLayoutSegment> segments,
			double timeMs) {
		for (CameraLayoutSegment segment : segments) {
			if (segment.contains(timeMs)) {
				return Optional.of(segment);
			}
		}
		return Optional.empty();
	}
}
