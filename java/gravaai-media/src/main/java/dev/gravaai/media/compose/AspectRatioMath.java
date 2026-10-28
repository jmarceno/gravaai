package dev.gravaai.media.compose;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the numeric parts of {@code src/utils/aspectRatioUtils.ts}: preset values, custom
 * {@code W:H} parsing and the output dimensions for a fixed width. The compositor uses this to
 * size render targets for a project aspect ratio (preview and export).
 */
public final class AspectRatioMath {

	/** {@code ASPECT_RATIOS} from aspectRatioUtils.ts. */
	public static final List<String> ASPECT_RATIOS = List.of(
			"native", "16:9", "9:16", "1:1", "4:3", "4:5", "16:10", "10:16");

	private static final Pattern CUSTOM = Pattern.compile("^(\\d+):(\\d+)$");

	private AspectRatioMath() {}

	/** {@code isCustomAspectRatio}. */
	public static boolean isCustomAspectRatio(String aspectRatio) {
		if (aspectRatio == null || ASPECT_RATIOS.contains(aspectRatio)) {
			return false;
		}
		Matcher matcher = CUSTOM.matcher(aspectRatio);
		if (!matcher.matches()) {
			return false;
		}
		long width = Long.parseLong(matcher.group(1));
		long height = Long.parseLong(matcher.group(2));
		return width > 0 && height > 0;
	}

	/** {@code getAspectRatioValue(aspectRatio, 16 / 9)}. */
	public static double aspectRatioValue(String aspectRatio) {
		return aspectRatioValue(aspectRatio, 16.0 / 9.0);
	}

	/** {@code getAspectRatioValue}. */
	public static double aspectRatioValue(String aspectRatio, double nativeAspectRatio) {
		if (aspectRatio == null) {
			return nativeAspectRatio > 0 ? nativeAspectRatio : 16.0 / 9.0;
		}
		return switch (aspectRatio) {
			case "native" -> nativeAspectRatio > 0 ? nativeAspectRatio : 16.0 / 9.0;
			case "16:9" -> 16.0 / 9.0;
			case "9:16" -> 9.0 / 16.0;
			case "1:1" -> 1.0;
			case "4:3" -> 4.0 / 3.0;
			case "4:5" -> 4.0 / 5.0;
			case "16:10" -> 16.0 / 10.0;
			case "10:16" -> 10.0 / 16.0;
			default -> parseCustom(aspectRatio);
		};
	}

	private static double parseCustom(String aspectRatio) {
		if (!isCustomAspectRatio(aspectRatio)) {
			return 16.0 / 9.0;
		}
		String[] parts = aspectRatio.split(":");
		double width = Double.parseDouble(parts[0]);
		double height = Double.parseDouble(parts[1]);
		if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
			return 16.0 / 9.0;
		}
		return width / height;
	}

	/**
	 * {@code getAspectRatioDimensions} rounded to whole output pixels: width fixed, height =
	 * round(width / ratio), at least 1.
	 */
	public static RenderTargetSize dimensionsForWidth(String aspectRatio, int width) {
		return dimensionsForWidth(aspectRatio, width, 16.0 / 9.0);
	}

	/** {@code getAspectRatioDimensions} for a fixed width. */
	public static RenderTargetSize dimensionsForWidth(String aspectRatio, int width,
			double nativeAspectRatio) {
		double ratio = aspectRatioValue(aspectRatio, nativeAspectRatio);
		int height = Math.max(1, (int) Math.round(width / ratio));
		return new RenderTargetSize(width, height);
	}
}
