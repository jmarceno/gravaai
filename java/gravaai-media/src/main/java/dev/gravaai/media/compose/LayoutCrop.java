package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

/**
 * Crop region as fractions of the source frame (port of the {@code CropRegion} type in
 * {@code src/components/video-editor/types.ts} and {@code dev.gravaai.core.project.CropRegion}).
 *
 * @param x left edge, 0-1
 * @param y top edge, 0-1
 * @param width cropped width fraction, 0-1
 * @param height cropped height fraction, 0-1
 */
public record LayoutCrop(@Nullable double x, @Nullable double y, double width, double height) {

	/** No crop: the whole frame. */
	public static final LayoutCrop FULL = new LayoutCrop(0, 0, 1, 1);

	public LayoutCrop {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
				|| !Double.isFinite(height)) {
			throw new IllegalArgumentException("crop values must be finite: " + x + "," + y + ","
					+ width + "," + height);
		}
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException(
					"crop width/height must be positive: " + width + "x" + height);
		}
	}
}
