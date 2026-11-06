package dev.gravaai.media.segmentation;

import java.nio.ByteBuffer;

/**
 * Pure mask math for the webcam background blur: temporal smoothing, halo-fix edge shaping and
 * the bilinear upscale to frame resolution. No inference runtime and no GL, so it is unit
 * testable on synthetic masks. All buffers are caller-owned scratch (the segmenter runs on one
 * thread per instance).
 */
final class MaskPostProcess {

	private MaskPostProcess() {}

	/**
	 * Averages the {@code count} mask buffers in {@code ring} into {@code target} (the temporal
	 * smoothing window; three frames remove shimmer, more adds visible lag).
	 */
	static void average(float[][] ring, int count, float[] target) {
		float scale = 1f / count;
		for (int i = 0; i < target.length; i++) {
			float sum = 0f;
			for (int frame = 0; frame < count; frame++) {
				sum += ring[frame][i];
			}
			target[i] = sum * scale;
		}
	}

	/** In-place max-filter dilation with a square kernel of the given radius (halo fix step 1). */
	static void dilate(float[] mask, float[] scratch, int width, int height, int radius) {
		// Horizontal pass into scratch, vertical pass back into mask.
		for (int y = 0; y < height; y++) {
			int row = y * width;
			for (int x = 0; x < width; x++) {
				float max = 0f;
				int from = Math.max(0, x - radius);
				int to = Math.min(width - 1, x + radius);
				for (int i = from; i <= to; i++) {
					float value = mask[row + i];
					if (value > max) {
						max = value;
					}
				}
				scratch[row + x] = max;
			}
		}
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				float max = 0f;
				int from = Math.max(0, y - radius);
				int to = Math.min(height - 1, y + radius);
				for (int i = from; i <= to; i++) {
					float value = scratch[i * width + x];
					if (value > max) {
						max = value;
					}
				}
				mask[y * width + x] = max;
			}
		}
	}

	/**
	 * In-place separable Gaussian blur with the given sigma (halo fix step 2: the soft mask edge
	 * that a steep remap then collapses).
	 */
	static void gaussianBlur(float[] mask, float[] scratch, int width, int height, double sigma) {
		int radius = Math.max(1, (int) Math.ceil(sigma * 3));
		float[] kernel = new float[2 * radius + 1];
		double sum = 0;
		for (int i = 0; i < kernel.length; i++) {
			double d = i - radius;
			kernel[i] = (float) Math.exp(-(d * d) / (2 * sigma * sigma));
			sum += kernel[i];
		}
		for (int i = 0; i < kernel.length; i++) {
			kernel[i] /= (float) sum;
		}
		// Horizontal pass into scratch.
		for (int y = 0; y < height; y++) {
			int row = y * width;
			for (int x = 0; x < width; x++) {
				float acc = 0f;
				for (int k = 0; k < kernel.length; k++) {
					int sx = Math.min(width - 1, Math.max(0, x + k - radius));
					acc += mask[row + sx] * kernel[k];
				}
				scratch[row + x] = acc;
			}
		}
		// Vertical pass back into mask.
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				float acc = 0f;
				for (int k = 0; k < kernel.length; k++) {
					int sy = Math.min(height - 1, Math.max(0, y + k - radius));
					acc += scratch[sy * width + x] * kernel[k];
				}
				mask[y * width + x] = acc;
			}
		}
	}

	/**
	 * Steep sigmoid remap {@code 1 / (1 + exp(-slope * (m - midpoint)))} in place: collapses the
	 * transition band to about a pixel, which is what keeps the composited edge halo-free.
	 */
	static void sigmoidRemap(float[] mask, double slope, double midpoint) {
		for (int i = 0; i < mask.length; i++) {
			mask[i] = (float) (1d / (1d + Math.exp(-slope * (mask[i] - midpoint))));
		}
	}

	/**
	 * Bilinear upscales the mask into a direct RGBA buffer (top-first, mask value in every
	 * channel), the format the compositor uploads.
	 */
	static void upscaleToRgba(float[] mask, int width, int height, int outWidth, int outHeight,
			ByteBuffer out) {
		double scaleX = (double) width / outWidth;
		double scaleY = (double) height / outHeight;
		for (int y = 0; y < outHeight; y++) {
			double sourceY = Math.min(height - 1.0, (y + 0.5) * scaleY - 0.5);
			int y0 = Math.max(0, (int) Math.floor(sourceY));
			int y1 = Math.min(height - 1, y0 + 1);
			double fy = sourceY - y0;
			for (int x = 0; x < outWidth; x++) {
				double sourceX = Math.min(width - 1.0, (x + 0.5) * scaleX - 0.5);
				int x0 = Math.max(0, (int) Math.floor(sourceX));
				int x1 = Math.min(width - 1, x0 + 1);
				double fx = sourceX - x0;
				float value = (float) ((mask[y0 * width + x0] * (1 - fx) + mask[y0 * width + x1] * fx)
						* (1 - fy)
						+ (mask[y1 * width + x0] * (1 - fx) + mask[y1 * width + x1] * fx) * fy);
				byte quantised = (byte) Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
				out.put(quantised).put(quantised).put(quantised).put(quantised);
			}
		}
		out.position(0);
		out.limit(outWidth * outHeight * 4);
	}
}
