package dev.gravaai.media.export;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Deterministic frame producer used by the export tests and as a development fallback (plan §5.10).
 * Draws a dark gradient, a moving high-contrast rectangle and a colour bar so that geometry, motion
 * and colour can be checked from the encoded file.
 */
public final class SyntheticFrameProvider implements ExportFrameProvider {

	private final int width;
	private final int height;
	private final int frameRate;

	public SyntheticFrameProvider(int width, int height, int frameRate) {
		if (width <= 0 || height <= 0 || frameRate <= 0) {
			throw new IllegalArgumentException("invalid synthetic frame size or frame rate");
		}
		this.width = width;
		this.height = height;
		this.frameRate = frameRate;
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return height;
	}

	@Override
	public int frameRate() {
		return frameRate;
	}

	@Override
	public void renderFrame(long frameIndex, double timeSeconds, ByteBuffer rgba) {
		Objects.requireNonNull(rgba, "rgba");
		if (rgba.capacity() < width * height * 4) {
			throw new IllegalArgumentException("RGBA buffer too small for the synthetic frame");
		}
		double phase = (timeSeconds * 0.5) % 1.0;
		int boxWidth = Math.max(2, width / 6);
		int boxHeight = Math.max(2, height / 6);
		int boxX = (int) Math.round((width - boxWidth) * (0.1 + 0.8 * phase));
		int boxY = height / 3;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int index = (y * width + x) * 4;
				int red = 16 + (x * 32) / width;
				int green = 24 + (y * 40) / height;
				int blue = 48 + ((x + y) * 24) / (width + height);
				if (x >= boxX && x < boxX + boxWidth && y >= boxY && y < boxY + boxHeight) {
					red = 255;
					green = 255;
					blue = 255;
				} else if (x < width / 20) {
					// Left colour bar: red, green, blue thirds, useful for range checks.
					red = y < height / 3 ? 255 : 16;
					green = y >= height / 3 && y < (2 * height) / 3 ? 255 : 16;
					blue = y >= (2 * height) / 3 ? 255 : 16;
				}
				rgba.put(index, (byte) red);
				rgba.put(index + 1, (byte) green);
				rgba.put(index + 2, (byte) blue);
				rgba.put(index + 3, (byte) 255);
			}
		}
	}
}
