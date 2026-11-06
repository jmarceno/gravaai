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
	@DisplayName("erosion shrinks a solid region by the kernel radius and never goes below 0")
	void erodesRegion() {
		int size = 32;
		float[] mask = new float[size * size];
		float[] scratch = new float[size * size];
		// A 10x10 block of 1 in the middle.
		for (int y = 11; y < 21; y++) {
			for (int x = 11; x < 21; x++) {
				mask[y * size + x] = 1f;
			}
		}
		MaskPostProcess.erode(mask, scratch, size, size, 2);
		// The region shrinks by 2 px on each side.
		assertEquals(0f, mask[12 * size + 16], 1e-6, "the top edge must move in by the radius");
		assertEquals(0f, mask[16 * size + 12], 1e-6, "the left edge must move in by the radius");
		assertEquals(1f, mask[13 * size + 16], 1e-6, "inside the shrunken region stays 1");
		assertEquals(1f, mask[18 * size + 18], 1e-6, "the centre stays 1");
		for (float value : mask) {
			assertTrue(value >= 0f, "erosion must not go below 0");
		}
	}

	@Test
	@DisplayName("edge shifting dilates, erodes or does nothing depending on the sign")
	void shiftsEdgeBySign() {
		int size = 16;
		float[] grown = new float[size * size];
		float[] shrunk = new float[size * size];
		float[] untouched = new float[size * size];
		for (int i = 0; i < size * size; i++) {
			grown[i] = 1f;
			shrunk[i] = 1f;
			untouched[i] = 1f;
		}
		// Punch a hole so both directions have an edge to move.
		for (int y = 6; y < 10; y++) {
			for (int x = 6; x < 10; x++) {
				grown[y * size + x] = 0f;
				shrunk[y * size + x] = 0f;
				untouched[y * size + x] = 0f;
			}
		}
		MaskPostProcess.shiftEdge(grown, new float[size * size], size, size, 2);
		MaskPostProcess.shiftEdge(shrunk, new float[size * size], size, size, -2);
		MaskPostProcess.shiftEdge(untouched, new float[size * size], size, size, 0);
		// The hole spans 6..9; dilating fills it back by the radius, eroding widens it by the same.
		assertEquals(1f, grown[7 * size + 7], 1e-6, "a positive radius must fill the hole back");
		assertEquals(0f, shrunk[5 * size + 7], 1e-6, "a negative radius must grow the hole");
		assertEquals(1f, shrunk[3 * size + 3], 1e-6, "far from the hole the mask stays solid");
		assertEquals(0f, untouched[7 * size + 7], 1e-6, "radius 0 must leave the mask alone");
		assertEquals(1f, untouched[5 * size + 7], 1e-6, "radius 0 must leave the hole alone");
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
	@DisplayName("packs the mask value into every RGBA channel at the mask's own resolution")
	void packsRgba() {
		// The mask keeps model resolution: the compositor's shader does the upscale, so this only
		// has to widen each value into four channels.
		float[] mask = {0f, 1f, 0f, 0.5f};
		ByteBuffer out = MaskPostProcess.toRgba(mask, 2, 2);
		int first = 0;
		assertEquals(0, Byte.toUnsignedInt(out.get(first)), "0 must quantise to 0");
		assertEquals(255, Byte.toUnsignedInt(out.get(first + 4)), "1 must quantise to 255");
		assertEquals(128, Byte.toUnsignedInt(out.get(first + 12)), "0.5 must quantise to 128");
		for (int channel = 0; channel < 4; channel++) {
			assertEquals(out.get(first + 4), out.get(first + 4 + channel),
					"the mask value must sit in every channel");
		}
		// Position 0 rewound, limit exact: the buffer is ready for a direct upload.
		assertEquals(0, out.position());
		assertEquals(2 * 2 * 4, out.limit());
	}

	@Test
	@DisplayName("clamps out-of-range mask values before packing")
	void packsClamped() {
		ByteBuffer out = MaskPostProcess.toRgba(new float[] {-0.5f, 1.5f}, 2, 1);
		assertEquals(0, Byte.toUnsignedInt(out.get(0)), "negative values clamp to 0");
		assertEquals(255, Byte.toUnsignedInt(out.get(4)), "values above 1 clamp to 255");
	}
}
