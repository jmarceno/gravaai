package dev.gravaai.core.project;

/** Normalized zoom focus point (0-1). */
public record ZoomFocus(double cx, double cy) {

	/** {@code clampFocusToDepth}: clamps the focus into the frame (NaN becomes the centre). */
	public ZoomFocus clampToDepth(ZoomDepth depth) {
		return new ZoomFocus(clamp(cx), clamp(cy));
	}

	private static double clamp(double value) {
		if (Double.isNaN(value)) {
			return 0.5;
		}
		return Math.min(1, Math.max(0, value));
	}
}
