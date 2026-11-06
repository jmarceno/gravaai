package dev.gravaai.media.segmentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic coverage of the mask math (no inference runtime): temporal smoothing, the halo
 * fix (dilate + Gaussian + sigmoid) and the bilinear upscale to frame resolution.
 */
class MaskPostProcessTest {

	@Test
	@DisplayName("averages the smoothing window into a new mask")
	void averagesRing() {
		float[][] ring = {
				new float[] {0f, 1f},
				new float[] {1f, 0f},
				new float[] {1f, 1f}};
		float[] target = new float[2];
		MaskPostProcess.average(ring, 3, target);
		assertEquals(2f / 3f, target[0], 1e-6);
		assertEquals(2f / 3f, target[1], 1e-6);
		// A partially filled ring averages only what exists.
		MaskPostProcess.average(ring, 1, target);
		assertEquals(0f, target[0], 1e-6);
		assertEquals(1f, target[1], 1e-6);
	}

	@Test
	@DisplayName("dilation grows a solid region by the kernel radius and never exceeds 1")
	void dilatesRegion() {
		int size = 32;
		float[] mask = new float[size * size];
		float[] scratch = new float[size * size];
		// A 10x10 block of 1 in the middle.
		for (int y = 11; y < 21; y++) {
			for (int x = 11; x < 21; x++) {
				mask[y * size + x] = 1f;
			}
		}
		MaskPostProcess.dilate(mask, scratch, size, size, 2);
		// The region grows by 2 px on each side.
		assertEquals(1f, mask[9 * size + 16], 1e-6, "top edge must grow by the radius");
		assertEquals(1f, mask[16 * size + 9], 1e-6, "left edge must grow by the radius");
		assertEquals(0f, mask[8 * size + 16], 1e-6, "the growth must stop at the radius");
		assertEquals(0f, mask[0], 1e-6, "far background stays 0");
		for (float value : mask) {
			assertTrue(value <= 1f, "dilation must not exceed 1");
		}
	}

	@Test
	@DisplayName("gaussian blur smooths a step edge and preserves the mean")
	void blursStepEdge() {
		int size = 64;
		float[] mask = new float[size * size];
		float[] scratch = new float[size * size];
		float sumBefore = 0;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				mask[y * size + x] = x < size / 2 ? 1f : 0f;
				sumBefore += mask[y * size + x];
			}
		}
		MaskPostProcess.gaussianBlur(mask, scratch, size, size, 2.0);
		float sumAfter = 0;
		float minLeft = 1f;
		float maxRight = 0f;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				sumAfter += mask[y * size + x];
				if (x < size / 2 - 8) {
					minLeft = Math.min(minLeft, mask[y * size + x]);
				}
				if (x > size / 2 + 8) {
					maxRight = Math.max(maxRight, mask[y * size + x]);
				}
			}
		}
		assertEquals(sumBefore, sumAfter, size * 0.05f, "blur must preserve the mean");
		assertTrue(minLeft > 0.95f, "far inside must stay sharp: " + minLeft);
		assertTrue(maxRight < 0.05f, "far outside must stay 0: " + maxRight);
	}

	@Test
	@DisplayName("the sigmoid remap collapses the transition band around 0.5")
	void sharpensTransition() {
		float[] mask = {0.4f, 0.5f, 0.6f};
		MaskPostProcess.sigmoidRemap(mask, 12.0, 0.5);
		// Slope 12 maps the ±0.1 band to roughly 0.23/0.77.
		assertTrue(mask[0] < 0.25, "0.4 must remap low: " + mask[0]);
		assertEquals(0.5f, mask[1], 1e-6, "the midpoint is a fixed point");
		assertTrue(mask[2] > 0.75, "0.6 must remap high: " + mask[2]);
	}

	@Test
	@DisplayName("upscale maps the mask value into every RGBA channel")
	void upscalesToRgba() {
		// 2x2 mask (0, 1 / 0, 1) into a 4x4 output: quadrant mapping.
		float[] mask = {0f, 1f, 0f, 1f};
		ByteBuffer out = ByteBuffer.allocateDirect(4 * 4 * 4);
		MaskPostProcess.upscaleToRgba(mask, 2, 2, 4, 4, out);
		int leftTop = (0 * 4 + 0) * 4;
		int rightTop = (0 * 4 + 3) * 4;
		assertEquals(0, Byte.toUnsignedInt(out.get(leftTop)), "left quadrant must be 0");
		assertEquals(255, Byte.toUnsignedInt(out.get(rightTop + 3)),
				"right quadrant must be 255");
		for (int channel = 0; channel < 4; channel++) {
			assertEquals(out.get(rightTop), out.get(rightTop + channel),
					"the mask value must sit in every channel");
		}
		// Position 0 rewound, limit exact: the buffer is ready for a direct upload.
		assertEquals(0, out.position());
		assertEquals(4 * 4 * 4, out.limit());
	}
}
