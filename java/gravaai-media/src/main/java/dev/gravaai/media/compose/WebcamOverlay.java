package dev.gravaai.media.compose;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of {@code src/components/video-editor/webcamOverlay.ts} + the webcam shadow profiles from
 * {@code shadowProfile.ts} (plan §5.8 stage 7): overlay size/position/crop/roundness/shadow math.
 *
 * <p>Values are authored against a 1920 px reference viewport; the compositor scales them to its
 * output width exactly like the TS preview. {@code reactToZoom} counter-scales the overlay by the
 * camera scale so the webcam keeps a stable screen size while the scene zooms.</p>
 */
public final class WebcamOverlay {

	public static final int MIN_OVERLAY_SIZE_PX = 56;
	public static final int REFERENCE_VIEWPORT_WIDTH = 1920;
	private static final int REFERENCE_VIEWPORT_HEIGHT = 1080;

	/** A scaled overlay size. */
	public record Size(double width, double height) {
	}

	/** A screen-space overlay origin. */
	public record Position(double x, double y) {
	}

	/** A source crop rectangle in pixels. */
	public record CropRect(double sx, double sy, double sw, double sh) {
	}

	/** One webcam drop-shadow layer. */
	public record ShadowLayer(double offsetY, double blur, double alpha) {
	}

	private static final double[][] SHADOW_PROFILES = {
			{0.06, 0.9, 0.22},
			{0.025, 0.8, 0.07}};

	private WebcamOverlay() {
	}

	private static double clamp(double value, double min, double max) {
		return Math.min(max, Math.max(min, value));
	}

	/** {@code scaleWebcamOverlayPixels}. */
	public static double scalePixels(double value, double containerWidth) {
		double safeValue = Double.isFinite(value) ? Math.max(0, value) : 0;
		double safeWidth = Double.isFinite(containerWidth) ? Math.max(0, containerWidth) : 0;
		return safeValue * (safeWidth / REFERENCE_VIEWPORT_WIDTH);
	}

	/** {@code getWebcamCornerRadiusPx}: roundness percent to a squircle corner radius. */
	public static double cornerRadiusPx(double roundnessPercent, double width, double height) {
		double safeRoundness = Double.isFinite(roundnessPercent)
				? clamp(roundnessPercent, 0, 100)
				: 0;
		double strengthenedRoundness = Math.sqrt(safeRoundness / 100);
		return (Math.max(0, Math.min(width, height)) / 2) * strengthenedRoundness;
	}

	/** {@code getWebcamPositionForPreset}. */
	public static Position positionForPreset(String preset) {
		return switch (preset == null ? "" : preset) {
			case "top-left" -> new Position(0, 0);
			case "top-center" -> new Position(0.5, 0);
			case "top-right" -> new Position(1, 0);
			case "center-left" -> new Position(0, 0.5);
			case "center" -> new Position(0.5, 0.5);
			case "center-right" -> new Position(1, 0.5);
			case "bottom-left" -> new Position(0, 1);
			case "bottom-center" -> new Position(0.5, 1);
			case "custom" -> new Position(1, 1);
			case "bottom-right" -> new Position(1, 1);
			default -> new Position(1, 1);
		};
	}

	/** {@code resolveWebcamCorner}. */
	public static String resolveCorner(String preset, String legacyCorner) {
		if ("top-left".equals(preset) || "top-right".equals(preset)
				|| "bottom-left".equals(preset) || "bottom-right".equals(preset)) {
			return preset;
		}
		return legacyCorner;
	}

	/** {@code getWebcamOverlayScale}. */
	public static double overlayScale(double zoomScale, boolean reactToZoom) {
		double safeZoom = Double.isFinite(zoomScale) && zoomScale > 0 ? zoomScale : 1;
		return reactToZoom ? 1 / safeZoom : 1;
	}

	/** {@code getWebcamOverlaySizePx}. */
	public static double sizePx(double containerWidth, double containerHeight,
			double sizePercent, double margin, double zoomScale, boolean reactToZoom) {
		double minDimension = Math.min(containerWidth, containerHeight);
		double clampedSizePercent = clamp(sizePercent, 10, 100);
		double safeMargin = Math.max(0, margin);
		double maxSize = Math.max(MIN_OVERLAY_SIZE_PX, minDimension - safeMargin * 2);
		double scaledSize = minDimension * (clampedSizePercent / 100)
				* overlayScale(zoomScale, reactToZoom);
		return Math.min(maxSize, Math.max(MIN_OVERLAY_SIZE_PX, scaledSize));
	}

	/** {@code getWebcamOverlayDimensionsPx}. */
	public static Size dimensionsPx(double containerWidth, double containerHeight,
			double widthPercent, double heightPercent, double margin, double zoomScale,
			boolean reactToZoom) {
		return new Size(
				sizePx(containerWidth, containerHeight, widthPercent, margin, zoomScale,
						reactToZoom),
				sizePx(containerWidth, containerHeight, heightPercent, margin, zoomScale,
						reactToZoom));
	}

	/** {@code getWebcamOverlayPosition}. */
	public static Position position(double containerWidth, double containerHeight, double width,
			double height, double margin, String positionPreset, double positionX,
			double positionY, String legacyCorner) {
		double safeMargin = Math.max(0, margin);
		double overlayWidth = Math.max(0, width);
		double overlayHeight = Math.max(0, height);
		double availableWidth = Math.max(0, containerWidth - overlayWidth - safeMargin * 2);
		double availableHeight = Math.max(0, containerHeight - overlayHeight - safeMargin * 2);
		Position presetPosition = "custom".equals(positionPreset)
				? new Position(clamp(positionX, 0, 1), clamp(positionY, 0, 1))
				: positionForPreset(positionPreset == null || positionPreset.isEmpty()
						? legacyCorner
						: positionPreset);
		return new Position(safeMargin + availableWidth * presetPosition.x(),
				safeMargin + availableHeight * presetPosition.y());
	}

	/** {@code normalizeWebcamCropRegion}. */
	public static LayoutCrop normalizeCrop(LayoutCrop cropRegion) {
		LayoutCrop candidate = cropRegion == null ? LayoutCrop.FULL : cropRegion;
		double rawX = Double.isFinite(candidate.x()) ? candidate.x() : 0;
		double rawY = Double.isFinite(candidate.y()) ? candidate.y() : 0;
		double x = clamp(rawX, 0, 0.99);
		double y = clamp(rawY, 0, 0.99);
		double width = clamp(Double.isFinite(candidate.width()) ? candidate.width() : 1, 0.01,
				1 - x);
		double height = clamp(Double.isFinite(candidate.height()) ? candidate.height() : 1, 0.01,
				1 - y);
		return new LayoutCrop(x, y, width, height);
	}

	/** {@code isWebcamCropRegionDefault}. */
	public static boolean isDefaultCrop(LayoutCrop cropRegion) {
		LayoutCrop crop = normalizeCrop(cropRegion);
		return crop.x() <= 0 && crop.y() <= 0 && crop.width() >= 1 && crop.height() >= 1;
	}

	/** {@code getWebcamCropSourceRect}. */
	public static CropRect cropSourceRect(LayoutCrop cropRegion, double sourceWidth,
			double sourceHeight) {
		LayoutCrop crop = normalizeCrop(cropRegion);
		double safeWidth = Math.max(1, sourceWidth);
		double safeHeight = Math.max(1, sourceHeight);
		double sx = clamp(crop.x() * safeWidth, 0, safeWidth - 1);
		double sy = clamp(crop.y() * safeHeight, 0, safeHeight - 1);
		double sw = clamp(crop.width() * safeWidth, 1, safeWidth - sx);
		double sh = clamp(crop.height() * safeHeight, 1, safeHeight - sy);
		return new CropRect(sx, sy, sw, sh);
	}

	/**
	 * {@code getCropMatchedWebcamHeightPercent}: when width and height match, derive the height
	 * from the crop aspect so the webcam is not stretched.
	 */
	public static double cropMatchedHeightPercent(double widthPercent, double heightPercent,
			Double sourceWidth, Double sourceHeight, LayoutCrop cropRegion) {
		double safeWidthPercent = Double.isFinite(widthPercent) ? widthPercent : 40;
		double safeHeightPercent = Double.isFinite(heightPercent)
				? heightPercent
				: safeWidthPercent;
		if (Math.abs(safeWidthPercent - safeHeightPercent) > 0.001) {
			return clamp(safeHeightPercent, 10, 100);
		}
		LayoutCrop crop = normalizeCrop(cropRegion);
		if (crop.x() <= 0 && crop.y() <= 0 && crop.width() >= 1 && crop.height() >= 1) {
			return clamp(safeHeightPercent, 10, 100);
		}
		double sourceAspect = sourceWidth != null && sourceHeight != null
				&& sourceWidth > 0 && sourceHeight > 0
						? sourceWidth / sourceHeight
						: 1;
		double cropAspect = (crop.width() * sourceAspect) / Math.max(0.001, crop.height());
		if (!Double.isFinite(cropAspect) || cropAspect <= 0) {
			return clamp(safeHeightPercent, 10, 100);
		}
		return clamp(safeWidthPercent / cropAspect, 10, 100);
	}

	/** {@code getWebcamShadowStrength}: the authored 0..1 value doubled and clamped. */
	public static double shadowStrength(double value) {
		return Math.min(1, Math.max(0, Double.isFinite(value) ? value * 2 : 0));
	}

	/** {@code applyWebcamLayout} shadow layers for a webcam box size and authored shadow value. */
	public static List<ShadowLayer> shadowLayers(double shadowSize, double shadowValue) {
		double strength = shadowStrength(shadowValue);
		if (strength <= 0) {
			return List.of();
		}
		double size = Math.max(0, shadowSize);
		List<ShadowLayer> layers = new ArrayList<>(SHADOW_PROFILES.length);
		for (double[] profile : SHADOW_PROFILES) {
			layers.add(new ShadowLayer(size * profile[0] * strength,
					Math.max(0, size * profile[2] * strength),
					Math.min(1, profile[1] * strength)));
		}
		return List.copyOf(layers);
	}

	/** Reference viewport height, kept for parity tests. */
	public static int referenceViewportHeight() {
		return REFERENCE_VIEWPORT_HEIGHT;
	}
}
