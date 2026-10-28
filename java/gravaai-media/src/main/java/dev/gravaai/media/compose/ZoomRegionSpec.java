package dev.gravaai.media.compose;

/**
 * A zoom region as the compositor consumes it (media-local port of the {@code ZoomRegion} type
 * in {@code video-editor/types.ts}).
 *
 * @param id the region id
 * @param startMs start on the project timeline, milliseconds
 * @param endMs end on the project timeline, milliseconds
 * @param depth the zoom depth level
 * @param focus the normalised focus point
 * @param mode auto/manual, or {@code null} when unspecified (TS optional field)
 */
public record ZoomRegionSpec(String id, int startMs, int endMs, ZoomDepth depth, ZoomFocus focus,
		ZoomMode mode) {

	public ZoomRegionSpec {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("zoom region id must not be blank");
		}
		if (endMs < startMs) {
			throw new IllegalArgumentException(
					"zoom region ends before it starts: " + startMs + " -> " + endMs);
		}
		if (depth == null || focus == null) {
			throw new IllegalArgumentException("zoom region needs a depth and a focus");
		}
	}

	/** The region scale from its depth. */
	public double scale() {
		return depth.scale();
	}

	/** A copy with a replaced focus (used by the resolved-focus path). */
	public ZoomRegionSpec withFocus(ZoomFocus resolvedFocus) {
		return new ZoomRegionSpec(id, startMs, endMs, depth, resolvedFocus, mode);
	}
}
