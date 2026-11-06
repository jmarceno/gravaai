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
	 * In-place min-filter erosion with a square kernel of the given radius: the mask shrinks, so
	 * the blurred room creeps towards the subject (a negative edge offset).
	 */
	static void erode(float[] mask, float[] scratch, int width, int height, int radius) {
		for (int y = 0; y < height; y++) {
			int row = y * width;
			for (int x = 0; x < width; x++) {
				float min = 1f;
				int from = Math.max(0, x - radius);
				int to = Math.min(width - 1, x + radius);
				for (int i = from; i <= to; i++) {
					float value = mask[row + i];
					if (value < min) {
						min = value;
					}
				}
				scratch[row + x] = min;
			}
		}
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				float min = 1f;
				int from = Math.max(0, y - radius);
				int to = Math.min(height - 1, y + radius);
				for (int i = from; i <= to; i++) {
					float value = scratch[i * width + x];
					if (value < min) {
						min = value;
					}
				}
				mask[y * width + x] = min;
			}
		}
	}

	/**
	 * Moves the mask edge by {@code radius} pixels: positive grows the person past the model's
	 * outline (fixing hair and collar the model clipped), negative pulls it in. Zero is a no-op.
	 */
	static void shiftEdge(float[] mask, float[] scratch, int width, int height, int radius) {
		if (radius > 0) {
			dilate(mask, scratch, width, height, radius);
		} else if (radius < 0) {
			erode(mask, scratch, width, height, -radius);
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
	 * Packs the mask into an RGBA buffer (top-first, mask value in every channel), the format the
	 * compositor uploads. The mask keeps its own resolution: the shader samples it through a
	 * matrix that maps it onto the frame, so upscaling it here (a scalar bilinear over 65k source
	 * pixels per 720p frame) only duplicated work the GPU does for free.
	 */
	static ByteBuffer toRgba(float[] mask, int width, int height) {
		ByteBuffer out = ByteBuffer.allocateDirect(width * height * 4);
		for (int i = 0; i < mask.length; i++) {
			byte value = (byte) Math.round(Math.max(0f, Math.min(1f, mask[i])) * 255f);
			out.put(value).put(value).put(value).put(value);
		}
		out.position(0);
		out.limit(width * height * 4);
		return out;
	}
}
