package dev.gravaai.media.compose;

import io.github.humbleui.types.Rect;

/**
 * The layout maths shared by preview and export (plan §5.8 stage 2/3; the full
 * {@code layoutUtils.ts} / {@code exportDimensions.ts} port grows here).
 *
 * <p>Already ported: the "cover fit" placement used by the background wallpaper and the video
 * layer — scale the source up until it covers the destination rectangle, then centre the
 * overflow and crop it. The result is applied as {@code translate(destinationX, destinationY)}
 * followed by {@code scale(scale)} before drawing the image at (0, 0), with the destination
 * rectangle as clip.</p>
 */
public final class LayoutMath {

	private LayoutMath() {}

	/**
	 * Where a source rectangle is drawn to cover a destination rectangle.
	 *
	 * @param scale uniform scale to apply to the source image
	 * @param destinationX x of the scaled image's top-left corner (negative when it overflows)
	 * @param destinationY y of the scaled image's top-left corner
	 * @param destinationWidth the scaled source width ({@code >= destination width})
	 * @param destinationHeight the scaled source height
	 */
	public record CoverFit(double scale, double destinationX, double destinationY,
			double destinationWidth, double destinationHeight) {
	}

	/**
	 * Computes the cover-fit placement of a source image inside a destination rectangle.
	 *
	 * @throws IllegalArgumentException when a source or destination dimension is not positive
	 */
	public static CoverFit coverFit(int sourceWidth, int sourceHeight, Rect destination) {
		if (sourceWidth <= 0 || sourceHeight <= 0) {
			throw new IllegalArgumentException(
					"invalid source size: " + sourceWidth + "x" + sourceHeight);
		}
		if (destination == null || destination.getWidth() <= 0 || destination.getHeight() <= 0) {
			throw new IllegalArgumentException("invalid destination rectangle: " + destination);
		}
		// Rect stores floats; widen before dividing so the maths stays in double.
		double destinationWidthPixels = destination.getWidth();
		double destinationHeightPixels = destination.getHeight();
		double scale = Math.max(destinationWidthPixels / sourceWidth,
				destinationHeightPixels / sourceHeight);
		double destinationWidth = sourceWidth * scale;
		double destinationHeight = sourceHeight * scale;
		double destinationX = destination._left
				+ (destinationWidthPixels - destinationWidth) / 2.0;
		double destinationY = destination._top + (destinationHeightPixels - destinationHeight) / 2.0;
		return new CoverFit(scale, destinationX, destinationY, destinationWidth, destinationHeight);
	}
}
