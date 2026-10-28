package dev.gravaai.media.export;

import java.nio.file.Path;

/** One GIF encode request (plan §5.10; two-pass palettegen/paletteuse). */
public record GifExportRequest(int width, int height, int frameRate, double durationSeconds,
		boolean loop, Path outputPath) {

	public GifExportRequest {
		if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
			throw new IllegalArgumentException("GIF export needs even positive dimensions");
		}
		if (frameRate <= 0 || !Double.isFinite(durationSeconds) || durationSeconds <= 0) {
			throw new IllegalArgumentException("GIF export needs a positive frame rate and duration");
		}
		if (outputPath == null) {
			throw new IllegalArgumentException("GIF export needs an output path");
		}
	}

	/** Number of frames each pass renders. */
	public long totalFrames() {
		return (long) Math.ceil(durationSeconds * frameRate);
	}
}
