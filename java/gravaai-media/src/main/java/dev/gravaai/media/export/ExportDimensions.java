package dev.gravaai.media.export;

import dev.gravaai.core.editor.ExportPreferences.ExportQuality;

/**
 * Output-size policy (port of {@code src/components/video-editor/exportDimensions.ts}, plan §5.10).
 * All dimensions are even pixel counts, matching the TS normalisation.
 */
public final class ExportDimensions {

	private static final int SHARED_VIDEO_LONG_SIDE_MAX = 1920;
	private static final int SHARED_VIDEO_SHORT_SIDE_MAX = 1080;

	/** Crop bounds as fractions of the source frame ({@code cropRegion.width/height}). */
	public record CropBounds(double width, double height) {
		public static final CropBounds FULL = new CropBounds(1, 1);
	}

	/** Size in pixels. */
	public record Size(int width, int height) {}

	/** {@code Mp4SupportProbeSnapshot}. */
	public record ProbeSnapshot(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight,
			String aspectRatio, int frameRate) {}

	private ExportDimensions() {}

	/** {@code shouldDebounceMp4SupportProbe}. */
	public static boolean shouldDebounceMp4SupportProbe(ProbeSnapshot previous, ProbeSnapshot current) {
		if (previous == null || !"native".equals(current.aspectRatio())
				|| !current.aspectRatio().equals(previous.aspectRatio())
				|| current.frameRate() != previous.frameRate()
				|| current.sourceWidth() != previous.sourceWidth()
				|| current.sourceHeight() != previous.sourceHeight()) {
			return false;
		}
		return current.targetWidth() != previous.targetWidth()
				|| current.targetHeight() != previous.targetHeight();
	}

	/** {@code calculateMp4SourceDimensions}. */
	public static Size calculateMp4SourceDimensions(int sourceWidth, int sourceHeight,
			String aspectRatio) {
		return calculateMp4SourceDimensions(sourceWidth, sourceHeight, aspectRatio, CropBounds.FULL);
	}

	/** {@code calculateMp4SourceDimensions}. */
	public static Size calculateMp4SourceDimensions(int sourceWidth, int sourceHeight,
			String aspectRatio, CropBounds cropRegion) {
		boolean useCroppedBounds = "native".equals(aspectRatio);
		CropBounds crop = cropRegion == null ? CropBounds.FULL : cropRegion;
		int safeSourceWidth = normalizeEvenDimension(sourceWidth * (useCroppedBounds ? crop.width() : 1));
		int safeSourceHeight = normalizeEvenDimension(sourceHeight * (useCroppedBounds ? crop.height() : 1));
		double sourceAspectRatio = safeSourceHeight > 0 ? (double) safeSourceWidth / safeSourceHeight : 16.0 / 9;
		double aspectRatioValue = aspectRatioValue(aspectRatio, sourceAspectRatio);

		if ("native".equals(aspectRatio)) {
			return new Size(safeSourceWidth, safeSourceHeight);
		}
		int longSide = Math.max(safeSourceWidth, safeSourceHeight);
		int shortSide = Math.min(safeSourceWidth, safeSourceHeight);
		int maxWidth = aspectRatioValue >= 1 ? longSide : shortSide;
		int maxHeight = aspectRatioValue >= 1 ? shortSide : longSide;
		return fitAspectRatioWithinBounds(maxWidth, maxHeight, aspectRatioValue);
	}

	/** {@code calculateMp4ExportDimensions}. */
	public static Size calculateMp4ExportDimensions(int baseWidth, int baseHeight, ExportQuality quality) {
		if (quality == ExportQuality.SOURCE) {
			return new Size(normalizeEvenDimension(baseWidth), normalizeEvenDimension(baseHeight));
		}
		double qualityScale = switch (quality) {
			case MEDIUM -> 0.6;
			case GOOD -> 0.75;
			case HIGH -> 0.9;
			case SOURCE -> 1;
		};
		return new Size(normalizeEvenDimension(baseWidth * qualityScale),
				normalizeEvenDimension(baseHeight * qualityScale));
	}

	/** {@code capMp4ShareDimensions}. */
	public static Size capMp4ShareDimensions(int width, int height) {
		int normalizedWidth = normalizeEvenDimension(width);
		int normalizedHeight = normalizeEvenDimension(height);
		boolean landscape = normalizedWidth >= normalizedHeight;
		int maxWidth = landscape ? SHARED_VIDEO_LONG_SIDE_MAX : SHARED_VIDEO_SHORT_SIDE_MAX;
		int maxHeight = landscape ? SHARED_VIDEO_SHORT_SIDE_MAX : SHARED_VIDEO_LONG_SIDE_MAX;
		double scale = Math.min(1, Math.min(maxWidth / (double) normalizedWidth,
				maxHeight / (double) normalizedHeight));
		return new Size(normalizeEvenDimension(normalizedWidth * scale),
				normalizeEvenDimension(normalizedHeight * scale));
	}

	/** {@code getAspectRatioValue} of {@code src/utils/aspectRatioUtils.ts}. */
	public static double aspectRatioValue(String aspectRatio, double nativeAspectRatio) {
		if (aspectRatio == null) {
			return safeNative(nativeAspectRatio);
		}
		return switch (aspectRatio) {
			case "native" -> safeNative(nativeAspectRatio);
			case "16:9" -> 16.0 / 9;
			case "9:16" -> 9.0 / 16;
			case "1:1" -> 1;
			case "4:3" -> 4.0 / 3;
			case "4:5" -> 4.0 / 5;
			case "16:10" -> 16.0 / 10;
			case "10:16" -> 10.0 / 16;
			default -> parseCustomAspectRatio(aspectRatio);
		};
	}

	private static double safeNative(double nativeAspectRatio) {
		return nativeAspectRatio > 0 && Double.isFinite(nativeAspectRatio) ? nativeAspectRatio : 16.0 / 9;
	}

	private static double parseCustomAspectRatio(String aspectRatio) {
		String[] parts = aspectRatio.split(":", -1);
		if (parts.length != 2) {
			return 16.0 / 9;
		}
		try {
			double width = Double.parseDouble(parts[0]);
			double height = Double.parseDouble(parts[1]);
			if (Double.isFinite(width) && Double.isFinite(height) && width > 0 && height > 0) {
				return width / height;
			}
		} catch (NumberFormatException ignored) {
			// fall through
		}
		return 16.0 / 9;
	}

	private static Size fitAspectRatioWithinBounds(int maxWidth, int maxHeight, double aspectRatioValue) {
		int safeMaxWidth = normalizeEvenDimension(maxWidth);
		int safeMaxHeight = normalizeEvenDimension(maxHeight);
		double safeAspectRatio = Double.isFinite(aspectRatioValue) && aspectRatioValue > 0
				? aspectRatioValue
				: 16.0 / 9;
		if (safeMaxWidth / (double) safeMaxHeight > safeAspectRatio) {
			int height = safeMaxHeight;
			int width = normalizeEvenDimension(height * safeAspectRatio);
			return new Size(Math.min(width, safeMaxWidth), height);
		}
		int width = safeMaxWidth;
		int height = normalizeEvenDimension(width / safeAspectRatio);
		return new Size(width, Math.min(height, safeMaxHeight));
	}

	private static int normalizeEvenDimension(double value) {
		return Math.max(2, (int) (Math.floor(value / 2) * 2));
	}
}
