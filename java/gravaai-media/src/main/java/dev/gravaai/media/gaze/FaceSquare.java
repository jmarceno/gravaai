package dev.gravaai.media.gaze;

import java.nio.ByteBuffer;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * The square face region the face mesh and the glasses segmenter read: {@code (x, y)} is the
 * top-left corner in frame pixels, {@code side} its edge. Built from a face box enlarged 1.5x
 * around its centre, the cut both models were verified with.
 */
public record FaceSquare(double x, double y, double side) {

	public FaceSquare {
		if (!(side > 0) || !Double.isFinite(x) || !Double.isFinite(y)) {
			throw new IllegalArgumentException("invalid face square " + x + "," + y + " " + side);
		}
	}

	/** The square around a face box of {@code (bx, by, bw, bh)}. */
	public static FaceSquare around(double bx, double by, double bw, double bh) {
		double side = Math.max(bw, bh) * 1.5;
		return new FaceSquare(bx + bw / 2 - side / 2, by + bh / 2 - side / 2, side);
	}

	/**
	 * The square resampled to {@code size x size}, RGB 0..1 (bilinear). {@code planar} lays the
	 * channels out NCHW and applies the ImageNet mean/std (the glasses segmenter); otherwise NHWC,
	 * unnormalised (the face mesh).
	 */
	float[] sample(RgbaFrame frame, int size, boolean planar) {
		ByteBuffer rgba = frame.pixelsTopFirstRgba();
		float[] out = new float[size * size * 3];
		double[] c = new double[3];
		double[] mean = {0.485, 0.456, 0.406};
		double[] std = {0.229, 0.224, 0.225};
		int plane = size * size;
		for (int py = 0; py < size; py++) {
			for (int px = 0; px < size; px++) {
				EyeContactMath.bilinear(rgba, frame.width(), frame.height(),
						x + (px + 0.5) * side / size, y + (py + 0.5) * side / size, c);
				if (planar) {
					for (int ch = 0; ch < 3; ch++) {
						out[ch * plane + py * size + px] = (float) ((c[ch] / 255 - mean[ch]) / std[ch]);
					}
				} else {
					int o = (py * size + px) * 3;
					out[o] = (float) (c[0] / 255);
					out[o + 1] = (float) (c[1] / 255);
					out[o + 2] = (float) (c[2] / 255);
				}
			}
		}
		return out;
	}
}
