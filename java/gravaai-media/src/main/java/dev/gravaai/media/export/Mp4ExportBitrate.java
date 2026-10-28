package dev.gravaai.media.export;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;

/**
 * MP4 delivery bitrate policy (port of {@code src/lib/exporter/exportBitrate.ts}, plan §5.10).
 *
 * <p>{@code useModernNativeStaticLayout} is accepted for signature parity with the TS caller but has
 * no effect: the TS policy deliberately keeps every backend on the same delivery bitrate policy.
 */
public final class Mp4ExportBitrate {

	public static final int MIN_MP4_BITRATE = 2_000_000;
	public static final int REFERENCE_FRAME_RATE = 30;
	private static final int HD_PIXELS = 1280 * 720;
	private static final int FULL_HD_PIXELS = 1920 * 1080;
	private static final int UHD_PIXELS = 3840 * 2160;

	private Mp4ExportBitrate() {}

	/** {@code getEncodingModeBitrateMultiplier}. */
	public static double getEncodingModeBitrateMultiplier(ExportEncodingMode encodingMode) {
		return switch (encodingMode) {
			case FAST -> 0.5;
			case QUALITY -> 1.0;
			case BALANCED -> 0.8;
		};
	}

	/** {@code getSourceQualityBitrate}. */
	public static int getSourceQualityBitrate(int width, int height) {
		long totalPixels = (long) width * height;
		if (totalPixels <= HD_PIXELS) {
			return 8_000_000;
		}
		if (totalPixels <= FULL_HD_PIXELS) {
			return 12_000_000;
		}
		if (totalPixels >= UHD_PIXELS) {
			return 45_000_000;
		}
		return interpolateBitrate(totalPixels, FULL_HD_PIXELS, UHD_PIXELS, 12_000_000, 45_000_000);
	}

	/** {@code getMp4ExportBitrate}. */
	public static int getMp4ExportBitrate(int width, int height, int frameRate, ExportQuality quality,
			ExportEncodingMode encodingMode) {
		int requestedBitrate = (int) Math.round(getBaseMp4ExportBitrate(width, height, quality)
				* getFrameRateBitrateMultiplier(frameRate)
				* getEncodingModeBitrateMultiplier(encodingMode));
		return Math.max(MIN_MP4_BITRATE, requestedBitrate);
	}

	/** TS signature parity: the native-static-layout flag does not change the policy. */
	public static int getMp4ExportBitrate(int width, int height, int frameRate, ExportQuality quality,
			ExportEncodingMode encodingMode, boolean useModernNativeStaticLayout) {
		return getMp4ExportBitrate(width, height, frameRate, quality, encodingMode);
	}

	private static int getBaseMp4ExportBitrate(int width, int height, ExportQuality quality) {
		if (quality == ExportQuality.SOURCE) {
			return getSourceQualityBitrate(width, height);
		}
		long totalPixels = (long) width * height;
		if (totalPixels <= HD_PIXELS) {
			return 5_000_000;
		}
		if (totalPixels <= FULL_HD_PIXELS) {
			return 8_000_000;
		}
		if (totalPixels >= UHD_PIXELS) {
			return 35_000_000;
		}
		return interpolateBitrate(totalPixels, FULL_HD_PIXELS, UHD_PIXELS, 8_000_000, 35_000_000);
	}

	private static double getFrameRateBitrateMultiplier(int frameRate) {
		// Only scales above REFERENCE_FRAME_RATE, so 24 and 30 fps share the same multiplier.
		return Math.sqrt(Math.max(1, frameRate / (double) REFERENCE_FRAME_RATE));
	}

	private static int interpolateBitrate(long totalPixels, int startPixels, int endPixels,
			int startBitrate, int endBitrate) {
		double progress = Math.max(0,
				Math.min(1, (totalPixels - (double) startPixels) / (endPixels - (double) startPixels)));
		return (int) Math.round(startBitrate + (endBitrate - startBitrate) * progress);
	}
}
