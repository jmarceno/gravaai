package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

/**
 * Port of {@code src/components/video-editor/videoPlayback/layoutUtils.ts} (plan §5.8 stage 2:
 * aspect, padding, crop). The maths is ported 1:1, including the padding scale factor and the
 * advanced unlinked vertical padding range; the TS tests are ported next to it.
 *
 * <p>Padding percentages are scaled by {@link #PADDING_SCALE_FACTOR} before they become canvas
 * fractions, so "20" means 4 % of the output edge, not 20 %.</p>
 */
public final class LayoutUtils {

	/** {@code PADDING_SCALE_FACTOR} from layoutUtils.ts. */
	public static final double PADDING_SCALE_FACTOR = 0.2;

	/** {@code BASE_PREVIEW_WIDTH} / {@code BASE_PREVIEW_HEIGHT} from layoutUtils.ts. */
	public static final int BASE_PREVIEW_WIDTH = 1920;

	/** {@code BASE_PREVIEW_HEIGHT} from layoutUtils.ts. */
	public static final int BASE_PREVIEW_HEIGHT = 1080;

	/** {@code ADVANCED_VERTICAL_PADDING_MAX} (types.ts, core Padding): 250 %. */
	public static final double ADVANCED_VERTICAL_PADDING_MAX = 250.0;

	private LayoutUtils() {}

	/**
	 * {@code scalePreviewBorderRadius}: the border radius in preview pixels, a percentage of
	 * the content's shorter side.
	 */
	public static double scalePreviewBorderRadius(int width, int height,
			double borderRadiusPercent) {
		return scalePreviewBorderRadius((double) width, (double) height, borderRadiusPercent);
	}

	/** Fractional-size variant used by the compositor (the TS function takes numbers). */
	public static double scalePreviewBorderRadius(double width, double height,
			double borderRadiusPercent) {
		if (width <= 0 || height <= 0) {
			return 0;
		}
		return (Math.min(width, height)
				* Math.min(50, Math.max(0, borderRadiusPercent))) / 100.0;
	}

	/** {@code isZeroPadding}. */
	public static boolean isZeroPadding(LayoutPadding padding) {
		return padding == null || padding.isZero();
	}

	/**
	 * {@code computePaddedLayout}: fits the cropped video inside the padded output area, with
	 * the TS centring and advanced-padding rules.
	 *
	 * @param width the output width in pixels
	 * @param height the output height in pixels
	 * @param padding the padding percentages, or {@code null} for none
	 * @param frameInsets the device-frame insets, or {@code null}
	 * @param cropRegion the crop region, or {@code null} for the full frame
	 * @param videoWidth the source video width in pixels
	 * @param videoHeight the source video height in pixels
	 */
	public static PaddedLayout computePaddedLayout(int width, int height, LayoutPadding padding,
			@Nullable FrameInsets frameInsets, LayoutCrop cropRegion, int videoWidth, int videoHeight) {
		LayoutPadding p = padding == null ? LayoutPadding.NONE : padding;
		LayoutCrop crop = cropRegion == null ? LayoutCrop.FULL : cropRegion;

		boolean isAdvancedPadding = !p.linked();
		double leftPercent = Math.min(100, Math.max(0, p.left()));
		double rightPercent = Math.min(100, Math.max(0, p.right()));
		double topPercent = Math.min(isAdvancedPadding ? ADVANCED_VERTICAL_PADDING_MAX : 100,
				Math.max(0, p.top()));
		double bottomPercent = Math.min(isAdvancedPadding ? ADVANCED_VERTICAL_PADDING_MAX : 100,
				Math.max(0, p.bottom()));
		double leftPadFrac = (leftPercent / 100.0) * PADDING_SCALE_FACTOR;
		double rightPadFrac = (rightPercent / 100.0) * PADDING_SCALE_FACTOR;
		double topPadFrac = (Math.min(topPercent, 100) / 100.0) * PADDING_SCALE_FACTOR;
		double bottomPadFrac = (Math.min(bottomPercent, 100) / 100.0) * PADDING_SCALE_FACTOR;

		double availableFracW = Math.max(0, 1.0 - leftPadFrac - rightPadFrac);
		double availableFracH = Math.max(0, 1.0 - topPadFrac - bottomPadFrac);
		double maxDisplayWidth = width * availableFracW;
		double maxDisplayHeight = height * availableFracH;

		double croppedVideoWidth = videoWidth * crop.width();
		double croppedVideoHeight = videoHeight * crop.height();

		double screenFracW = frameInsets == null ? 1 : 1 - frameInsets.left() - frameInsets.right();
		double screenFracH = frameInsets == null ? 1 : 1 - frameInsets.top() - frameInsets.bottom();
		double fullFrameVideoW = croppedVideoWidth / screenFracW;
		double fullFrameVideoH = croppedVideoHeight / screenFracH;

		double scale = Math.min(
				fullFrameVideoW > 0 ? maxDisplayWidth / fullFrameVideoW : 0,
				fullFrameVideoH > 0 ? maxDisplayHeight / fullFrameVideoH : 0);

		double fullVideoDisplayWidth = videoWidth * scale;
		double fullVideoDisplayHeight = videoHeight * scale;
		double croppedDisplayWidth = croppedVideoWidth * scale;
		double croppedDisplayHeight = croppedVideoHeight * scale;
		double fullFrameDisplayW = fullFrameVideoW * scale;
		double fullFrameDisplayH = fullFrameVideoH * scale;

		double availableCenterX = leftPadFrac * width + maxDisplayWidth / 2;
		double availableCenterY;
		if (isAdvancedPadding) {
			double verticalTravel = Math.max(0, height - fullFrameDisplayH);
			double centeredOffsetY = verticalTravel / 2;
			double directionalOffsetY = centeredOffsetY
					+ ((topPercent - bottomPercent) / ADVANCED_VERTICAL_PADDING_MAX)
							* centeredOffsetY;
			double frameOffsetY = Math.min(verticalTravel, Math.max(0, directionalOffsetY));
			availableCenterY = frameOffsetY + fullFrameDisplayH / 2;
		} else {
			availableCenterY = topPadFrac * height + maxDisplayHeight / 2;
		}

		double frameCenterX = availableCenterX - fullFrameDisplayW / 2;
		double frameCenterY = availableCenterY - fullFrameDisplayH / 2;
		double centerOffsetX = frameInsets == null
				? frameCenterX
				: frameCenterX + frameInsets.left() * fullFrameDisplayW;
		double centerOffsetY = frameInsets == null
				? frameCenterY
				: frameCenterY + frameInsets.top() * fullFrameDisplayH;
		double spriteX = centerOffsetX - crop.x() * fullVideoDisplayWidth;
		double spriteY = centerOffsetY - crop.y() * fullVideoDisplayHeight;

		return new PaddedLayout(scale, centerOffsetX, centerOffsetY, spriteX, spriteY,
				fullFrameDisplayW, fullFrameDisplayH, fullVideoDisplayWidth,
				fullVideoDisplayHeight, croppedDisplayWidth, croppedDisplayHeight,
				crop.x() * videoWidth, crop.y() * videoHeight);
	}
}
