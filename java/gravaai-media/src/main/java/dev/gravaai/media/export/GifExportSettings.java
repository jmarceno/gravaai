package dev.gravaai.media.export;

import dev.gravaai.core.editor.ExportPreferences.GifSizePreset;

/**
 * GIF size/frame-rate policy (port of the size-preset parts of
 * {@code src/lib/exporter/gifExporter.ts} and {@code src/lib/exporter/types.ts}, plan §5.10).
 */
public final class GifExportSettings {

	/** {@code GIF_SIZE_PRESETS} max heights; {@code original} means no cap. */
	private static final int MEDIUM_MAX_HEIGHT = 720;
	private static final int LARGE_MAX_HEIGHT = 1080;

	/** {@code VALID_GIF_FRAME_RATES}. */
	public static final int[] GIF_FRAME_RATES = {15, 20, 25, 30};

	/** Output size in pixels. */
	public record Size(int width, int height) {}

	private GifExportSettings() {}

	/** {@code isValidGifFrameRate}. */
	public static boolean isValidGifFrameRate(int rate) {
		for (int valid : GIF_FRAME_RATES) {
			if (valid == rate) {
				return true;
			}
		}
		return false;
	}

	/** {@code calculateOutputDimensions}. */
	public static Size calculateOutputDimensions(int sourceWidth, int sourceHeight,
			GifSizePreset sizePreset) {
		int maxHeight = switch (sizePreset == null ? GifSizePreset.MEDIUM : sizePreset) {
			case MEDIUM -> MEDIUM_MAX_HEIGHT;
			case LARGE -> LARGE_MAX_HEIGHT;
			case ORIGINAL -> Integer.MAX_VALUE;
		};
		boolean original = sizePreset == GifSizePreset.ORIGINAL;
		if (sourceHeight <= maxHeight || original) {
			return new Size(sourceWidth, sourceHeight);
		}
		double aspectRatio = sourceWidth / (double) sourceHeight;
		int newHeight = maxHeight;
		int newWidth = (int) Math.round(newHeight * aspectRatio);
		return new Size(newWidth % 2 == 0 ? newWidth : newWidth + 1,
				newHeight % 2 == 0 ? newHeight : newHeight + 1);
	}

	/** {@code getGifRepeat}: gif.js repeat 0 is infinite, 1 is play-once. */
	public static int gifRepeat(boolean loop) {
		return loop ? 0 : 1;
	}
}
