package dev.gravaai.media.gaze;

import java.nio.ByteBuffer;

/**
 * The geometry and pixel arithmetic of the eye-contact correction, free of any model so it can be
 * tested on its own.
 *
 * <p>The warp nets were trained on a fixed eye patch cut around the six dlib eye-contour points
 * (Hsu et al.): {@link Patch#of} reproduces that cut exactly, including the integer rounding of
 * the points, because the nets learned on it. Their output is a flow field and a two-channel light
 * map at 64x48; {@link #composite} applies both at the frame's full resolution (sampling the
 * original frame, never the 64x48 thumbnail, so the eye stays sharp) and feathers past the border
 * the nets were trained to leave alone.</p>
 *
 * <p>The glasses rule lives here too: every pixel is blended back toward the original by the
 * glasses-frame alpha, so where the frame segmenter says "frame" the output byte <em>is</em> the
 * input byte. A frame can never bend.</p>
 */
public final class EyeContactMath {

	/** Warp-net patch size. */
	public static final int PATCH_W = 64;
	public static final int PATCH_H = 48;
	/** Square side of the face-mesh and glasses-segmenter inputs. */
	public static final int FACE_INPUT = 256;

	/**
	 * Face-mesh indices of the six contour points of each eye, in dlib order (outer/inner corner,
	 * two upper-lid points, inner/outer corner, two lower-lid points). Eye 0 is dlib 42..47 (the
	 * subject's left eye, on the right of the image), eye 1 is dlib 36..41.
	 */
	static final int[][] EYE_POINTS = {{362, 385, 387, 263, 373, 380}, {33, 160, 158, 133, 153, 144}};
	/** Which contour point feeds anchor channel pair k of each eye's net (dlib's order per net). */
	static final int[][] ANCHOR_ORDER = {{3, 2, 1, 0, 5, 4}, {0, 1, 2, 3, 4, 5}};
	/** Face-mesh iris centres of eye 0 and eye 1. */
	static final int[] IRIS_POINTS = {473, 468};
	/** Face-mesh nose tip. */
	static final int NOSE_TIP = 1;

	/** Feather band, in patch pixels: the nets leave a 4 px (x) / 3 px (y) border untouched. */
	private static final double FEATHER_X = 4;
	private static final double FEATHER_Y = 3;

	private EyeContactMath() {}

	/**
	 * The frame rectangle one eye's net reads, and the integer contour points it was cut from.
	 *
	 * @param px contour x, cast to int like the training data
	 * @param py contour y, cast to int
	 */
	public record Patch(int left, int top, int right, int bottom, int[] px, int[] py) {

		public int width() {
			return right - left;
		}

		public int height() {
			return bottom - top;
		}

		/** Large enough for the net to say anything useful. */
		public boolean usable() {
			return width() >= 12 && height() >= 9;
		}

		/** The patch cut of one eye from its six contour points (frame pixels). */
		public static Patch of(float[] xs, float[] ys) {
			int[] px = new int[6];
			int[] py = new int[6];
			double cx = 0;
			double cy = 0;
			for (int k = 0; k < 6; k++) {
				px[k] = (int) xs[k];
				py[k] = (int) ys[k];
				cx += px[k] / 6.0;
				cy += py[k] / 6.0;
			}
			double eyeLen = Math.abs(px[3] - px[0]);
			double halfW = eyeLen * 3 / 4;
			double bh = 1.5 * halfW;
			return new Patch((int) (cx - halfW), (int) (cy - bh * 7 / 12), (int) (cx + halfW),
					(int) (cy + bh * 5 / 12), px, py);
		}

		/** Patch-model x of frame pixel column {@code x}. */
		double u(int x) {
			return (x + 0.5 - left) / width() * PATCH_W - 0.5;
		}

		/** Patch-model y of frame pixel row {@code y}. */
		double v(int y) {
			return (y + 0.5 - top) / height() * PATCH_H - 0.5;
		}
	}

	/**
	 * The net's image input: the patch resampled to 64x48, BGR, 0..1, NHWC.
	 */
	public static float[] patchImage(ByteBuffer rgba, int width, int height, Patch patch) {
		float[] img = new float[PATCH_H * PATCH_W * 3];
		double[] c = new double[3];
		for (int y = 0; y < PATCH_H; y++) {
			for (int x = 0; x < PATCH_W; x++) {
				bilinear(rgba, width, height, patch.left() + (x + 0.5) * patch.width() / PATCH_W,
						patch.top() + (y + 0.5) * patch.height() / PATCH_H, c);
				int o = (y * PATCH_W + x) * 3;
				img[o] = (float) (c[2] / 255);
				img[o + 1] = (float) (c[1] / 255);
				img[o + 2] = (float) (c[0] / 255);
			}
		}
		return img;
	}

	/**
	 * The net's anchor input: for each of the six points (in the net's order), the integer offset
	 * of every patch pixel from the point, NHWC with 12 channels.
	 */
	public static float[] anchorMap(Patch patch, int eye) {
		float[] fp = new float[PATCH_H * PATCH_W * 12];
		int[] order = ANCHOR_ORDER[eye];
		for (int k = 0; k < 6; k++) {
			int p = order[k];
			int rx = (patch.px()[p] - patch.left()) * PATCH_W / patch.width();
			int ry = (patch.py()[p] - patch.top()) * PATCH_H / patch.height();
			for (int y = 0; y < PATCH_H; y++) {
				for (int x = 0; x < PATCH_W; x++) {
					int o = (y * PATCH_W + x) * 12 + k * 2;
					fp[o] = x - rx;
					fp[o + 1] = y - ry;
				}
			}
		}
		return fp;
	}

	/** The feather weight (0..1) of patch-model coordinates (u, v). */
	public static double feather(double u, double v) {
		double fx = (Math.min(u, PATCH_W - 1 - u) - FEATHER_X) / FEATHER_X;
		double fy = (Math.min(v, PATCH_H - 1 - v) - FEATHER_Y) / FEATHER_Y;
		return Math.clamp(Math.min(fx, fy), 0, 1);
	}

	/** Glasses-frame alpha from a segmenter logit: logit -3 and below is skin, -1 and above frame. */
	public static double frameAlpha(double logit) {
		return Math.clamp((logit + 3.0) / 2.0, 0, 1);
	}

	/**
	 * The glasses-frame alpha over the patch rectangle (plus a one-pixel ring), dilated by one
	 * pixel so the frame edge is held too. {@code logits} is the segmenter's 256x256 output over
	 * the face square {@code (roiX, roiY, roiSide)}; outside that square the alpha is 0.
	 *
	 * @return {@code (patch.width()) x (patch.height())} alphas, row-major from (left, top)
	 */
	public static float[] glassesAlpha(Patch patch, float[] logits, double roiX, double roiY,
			double roiSide) {
		int gw = patch.width() + 2;
		int gh = patch.height() + 2;
		float[] raw = new float[gw * gh];
		for (int gy = 0; gy < gh; gy++) {
			int y = patch.top() - 1 + gy;
			if (y < roiY || y >= roiY + roiSide) {
				continue;
			}
			for (int gx = 0; gx < gw; gx++) {
				int x = patch.left() - 1 + gx;
				if (x < roiX || x >= roiX + roiSide) {
					continue;
				}
				double lx = (x + 0.5 - roiX) / roiSide * FACE_INPUT - 0.5;
				double ly = (y + 0.5 - roiY) / roiSide * FACE_INPUT - 0.5;
				raw[gy * gw + gx] = (float) frameAlpha(
						sampleChannel(logits, FACE_INPUT, FACE_INPUT, 1, 0, lx, ly));
			}
		}
		float[] alpha = new float[patch.width() * patch.height()];
		for (int y = 0; y < patch.height(); y++) {
			for (int x = 0; x < patch.width(); x++) {
				float m = 0;
				for (int dy = 0; dy <= 2; dy++) {
					for (int dx = 0; dx <= 2; dx++) {
						m = Math.max(m, raw[(y + dy) * gw + x + dx]);
					}
				}
				alpha[y * patch.width() + x] = m;
			}
		}
		return alpha;
	}

	/**
	 * Writes one corrected eye into {@code out} (a copy of {@code src} of the same size): every
	 * patch pixel samples the original frame through the net's flow (tanh, half-patch units),
	 * relights it with the light map ({@code warped * w0 + white * w1}) and blends toward the
	 * original by the feather and the glasses alpha. A pixel whose blend weight is 0 is not
	 * written, so it stays bit-identical to the source.
	 *
	 * @param flow  64x48x2 raw flow
	 * @param lcm   64x48x2 light weights
	 * @param alpha glasses alpha from {@link #glassesAlpha}, patch-sized
	 * @return the number of pixels written
	 */
	public static int composite(ByteBuffer src, ByteBuffer out, int width, int height, Patch patch,
			float[] flow, float[] lcm, float[] alpha) {
		int written = 0;
		double[] c = new double[3];
		int ow = patch.width();
		int oh = patch.height();
		for (int y = Math.max(0, patch.top()); y < Math.min(height, patch.bottom()); y++) {
			double v = patch.v(y);
			for (int x = Math.max(0, patch.left()); x < Math.min(width, patch.right()); x++) {
				double u = patch.u(x);
				double w = feather(u, v) * (1 - alpha[(y - patch.top()) * ow + x - patch.left()]);
				if (w <= 0) {
					continue;
				}
				double dx = Math.tanh(sampleChannel(flow, PATCH_W, PATCH_H, 2, 0, u, v));
				double dy = Math.tanh(sampleChannel(flow, PATCH_W, PATCH_H, 2, 1, u, v));
				double w0 = sampleChannel(lcm, PATCH_W, PATCH_H, 2, 0, u, v);
				double w1 = sampleChannel(lcm, PATCH_W, PATCH_H, 2, 1, u, v);
				bilinear(src, width, height, x + 0.5 + dx * 0.5 * ow, y + 0.5 + dy * 0.5 * oh, c);
				int o = (y * width + x) * 4;
				for (int ch = 0; ch < 3; ch++) {
					double corrected = c[ch] * w0 + 255 * w1;
					int orig = Byte.toUnsignedInt(src.get(o + ch));
					long value = Math.round(orig + w * (corrected - orig));
					out.put(o + ch, (byte) Math.clamp(value, 0, 255));
				}
				written++;
			}
		}
		return written;
	}

	/**
	 * Bilinear RGB sample of a packed RGBA buffer at frame coordinates {@code (x, y)} (pixel
	 * centres at +0.5), clamped to the edge, 0..255 per channel into {@code out}.
	 */
	public static void bilinear(ByteBuffer rgba, int width, int height, double x, double y,
			double[] out) {
		double sx = x - 0.5;
		double sy = y - 0.5;
		int x0 = (int) Math.floor(sx);
		int y0 = (int) Math.floor(sy);
		double fx = sx - x0;
		double fy = sy - y0;
		int xa = Math.clamp(x0, 0, width - 1);
		int xb = Math.clamp(x0 + 1, 0, width - 1);
		int ya = Math.clamp(y0, 0, height - 1);
		int yb = Math.clamp(y0 + 1, 0, height - 1);
		int p00 = (ya * width + xa) * 4;
		int p10 = (ya * width + xb) * 4;
		int p01 = (yb * width + xa) * 4;
		int p11 = (yb * width + xb) * 4;
		for (int c = 0; c < 3; c++) {
			double top = (1 - fx) * Byte.toUnsignedInt(rgba.get(p00 + c))
					+ fx * Byte.toUnsignedInt(rgba.get(p10 + c));
			double bottom = (1 - fx) * Byte.toUnsignedInt(rgba.get(p01 + c))
					+ fx * Byte.toUnsignedInt(rgba.get(p11 + c));
			out[c] = (1 - fy) * top + fy * bottom;
		}
	}

	/** Bilinear sample of channel {@code ch} of an interleaved float map, clamped to the edge. */
	static double sampleChannel(float[] map, int w, int h, int channels, int ch, double x,
			double y) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		double fx = x - x0;
		double fy = y - y0;
		int xa = Math.clamp(x0, 0, w - 1);
		int xb = Math.clamp(x0 + 1, 0, w - 1);
		int ya = Math.clamp(y0, 0, h - 1);
		int yb = Math.clamp(y0 + 1, 0, h - 1);
		double top = (1 - fx) * map[(ya * w + xa) * channels + ch]
				+ fx * map[(ya * w + xb) * channels + ch];
		double bottom = (1 - fx) * map[(yb * w + xa) * channels + ch]
				+ fx * map[(yb * w + xb) * channels + ch];
		return (1 - fy) * top + fy * bottom;
	}
}
