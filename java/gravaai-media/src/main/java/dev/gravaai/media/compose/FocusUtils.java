package dev.gravaai.media.compose;

/**
 * Port of the parts of {@code src/components/video-editor/videoPlayback/focusUtils.ts} the zoom
 * region logic uses: {@code clampFocusToScale}.
 */
public final class FocusUtils {

	private FocusUtils() {}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double marginForScale(double zoomScale) {
		return 1 / (2 * zoomScale);
	}

	/** {@code clampFocusToScale}: keep the focus inside the visible zoomed bounds. */
	public static ZoomFocus clampFocusToScale(ZoomFocus focus, double zoomScale) {
		double margin = marginForScale(zoomScale);
		double minX = margin;
		double maxX = 1 - margin;
		double minY = margin;
		double maxY = 1 - margin;
		return new ZoomFocus(
				clamp(clamp(focus.cx(), 0, 1), minX, maxX),
				clamp(clamp(focus.cy(), 0, 1), minY, maxY));
	}
}
