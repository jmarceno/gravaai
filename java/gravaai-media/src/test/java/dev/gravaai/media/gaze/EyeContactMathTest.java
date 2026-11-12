package dev.gravaai.media.gaze;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The eye patch cut, the net inputs and the composite, without any model. */
class EyeContactMathTest {

	private static final int W = 320;
	private static final int H = 200;

	/** A six-point eye contour 40 px wide centred at (160, 100). */
	private static EyeContactMath.Patch patch() {
		float[] xs = {140, 153, 167, 180, 167, 153};
		float[] ys = {100, 94, 94, 100, 106, 106};
		return EyeContactMath.Patch.of(xs, ys);
	}

	private static ByteBuffer noise() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(W * H * 4);
		java.util.Random random = new java.util.Random(7);
		for (int i = 0; i < W * H * 4; i++) {
			buffer.put(i, (byte) random.nextInt(256));
		}
		return buffer;
	}

	@Test
	@DisplayName("the patch is cut like the warp nets' training data")
	void patchGeometry() {
		EyeContactMath.Patch patch = patch();
		// eyeLen 40 -> halfW 30, bh 45; centre (160, 100).
		assertEquals(130, patch.left());
		assertEquals(190, patch.right());
		assertEquals((int) (100 - 45 * 7.0 / 12), patch.top());
		assertEquals((int) (100 + 45 * 5.0 / 12), patch.bottom());
		assertTrue(patch.usable());
	}

	@Test
	@DisplayName("the anchor map holds integer offsets from each point, in the net's point order")
	void anchorMap() {
		EyeContactMath.Patch patch = patch();
		float[] left = EyeContactMath.anchorMap(patch, 0);
		float[] right = EyeContactMath.anchorMap(patch, 1);
		int rx0 = (patch.px()[0] - patch.left()) * 64 / patch.width();
		int ry0 = (patch.py()[0] - patch.top()) * 48 / patch.height();
		int rx3 = (patch.px()[3] - patch.left()) * 64 / patch.width();
		// Pixel (10, 20), channel pair 0: the right-eye net reads point 0, the left-eye net point 3.
		int o = (20 * 64 + 10) * 12;
		assertEquals(10 - rx0, right[o]);
		assertEquals(20 - ry0, right[o + 1]);
		assertEquals(10 - rx3, left[o]);
	}

	@Test
	@DisplayName("the feather leaves the border the nets do not touch and is full inside")
	void feather() {
		assertEquals(0, EyeContactMath.feather(2, 24));
		assertEquals(0, EyeContactMath.feather(32, 1));
		assertEquals(1, EyeContactMath.feather(32, 24));
		assertEquals(0.5, EyeContactMath.feather(6, 24), 1e-9);
	}

	@Test
	@DisplayName("frame pixels (alpha 1) are byte-identical, skin pixels move, outside is untouched")
	void compositeHoldsGlassesFrame() {
		ByteBuffer src = noise();
		ByteBuffer out = ByteBuffer.allocateDirect(W * H * 4);
		out.put(0, src, 0, W * H * 4);
		EyeContactMath.Patch patch = patch();
		float[] flow = new float[64 * 48 * 2];
		Arrays.fill(flow, 0.4f); // a strong shift: every warped pixel changes
		float[] light = new float[64 * 48 * 2];
		for (int i = 0; i < light.length; i += 2) {
			light[i] = 1;
		}
		float[] alpha = new float[patch.width() * patch.height()];
		// The upper half of the patch is "frame".
		for (int y = 0; y < patch.height() / 2; y++) {
			Arrays.fill(alpha, y * patch.width(), (y + 1) * patch.width(), 1f);
		}
		int written = EyeContactMath.composite(src, out, W, H, patch, flow, light, alpha);
		assertTrue(written > 300, "the lower half must be warped, wrote " + written);
		int changed = 0;
		for (int y = 0; y < H; y++) {
			for (int x = 0; x < W; x++) {
				int o = (y * W + x) * 4;
				byte[] a = new byte[4];
				byte[] b = new byte[4];
				src.get(o, a);
				out.get(o, b);
				boolean inPatch = x >= patch.left() && x < patch.right() && y >= patch.top()
						&& y < patch.bottom();
				boolean frame = inPatch && y - patch.top() < patch.height() / 2;
				if (!inPatch || frame) {
					assertArrayEquals(a, b, "pixel " + x + "," + y + " must be untouched");
				} else if (!Arrays.equals(a, b)) {
					changed++;
				}
				assertEquals(a[3], b[3], "alpha is never written");
			}
		}
		assertTrue(changed > 0);
		assertNotEquals(0, written);
	}

	@Test
	@DisplayName("the glasses alpha is dilated by one pixel and zero outside the face square")
	void glassesAlpha() {
		EyeContactMath.Patch patch = patch();
		float[] logits = new float[256 * 256];
		Arrays.fill(logits, -10);
		// Face square covers the whole frame at 1:1 scale from (0, 0), side 256: one hot logit.
		int hx = patch.left() + 10;
		int hy = patch.top() + 10;
		logits[hy * 256 + hx] = 5;
		float[] alpha = EyeContactMath.glassesAlpha(patch, logits, 0, 0, 256);
		assertEquals(1, alpha[10 * patch.width() + 10]);
		assertEquals(1, alpha[11 * patch.width() + 11], "the neighbour is held too");
		assertEquals(0, alpha[20 * patch.width() + 20]);
		float[] outside = EyeContactMath.glassesAlpha(patch, logits, 1000, 1000, 256);
		for (float value : outside) {
			assertEquals(0, value);
		}
	}
}
