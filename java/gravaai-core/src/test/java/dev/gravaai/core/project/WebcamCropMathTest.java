package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.WebcamCropMath.Handle;

/** Port of the pure crop helpers in {@code WebcamCropControl.tsx}. */
class WebcamCropMathTest {

	private static CropRegion crop(double x, double y, double width, double height) {
		return new CropRegion(x, y, width, height);
	}

	private static void assertCrop(CropRegion expected, CropRegion actual) {
		assertEquals(expected.x(), actual.x(), 1e-9, "x");
		assertEquals(expected.y(), actual.y(), 1e-9, "y");
		assertEquals(expected.width(), actual.width(), 1e-9, "width");
		assertEquals(expected.height(), actual.height(), 1e-9, "height");
	}

	@Test
	@DisplayName("normalize clamps x/y to 0..0.99 and keeps at least 1% of the source")
	void normalizes() {
		assertCrop(crop(0, 0, 1, 1), WebcamCropMath.normalize(null));
		// Size is clamped to what remains after x/y, exactly like normalizeWebcamCropRegion.
		assertCrop(crop(0.5, 0.5, 0.5, 0.5),
				WebcamCropMath.normalize(crop(0.5, 0.5, 1, 1)));
		assertCrop(crop(0.99, 0.99, 0.01, 0.01), WebcamCropMath.normalize(crop(2, 2, 5, 5)));
		assertCrop(crop(0, 0, 0.01, 0.01),
				WebcamCropMath.normalize(crop(Double.NaN, Double.NaN, 0, 0)));
	}

	@Test
	@DisplayName("isDefault is true only for the whole source")
	void defaultDetection() {
		assertTrue(WebcamCropMath.isDefault(CropRegion.DEFAULT));
		assertTrue(WebcamCropMath.isDefault(null));
		assertFalse(WebcamCropMath.isDefault(crop(0, 0, 0.5, 1)));
		assertFalse(WebcamCropMath.isDefault(crop(0.1, 0, 0.9, 1)));
	}

	@Test
	@DisplayName("flipCropHorizontally mirrors around the source centre")
	void flipsHorizontally() {
		assertCrop(crop(0.6, 0, 0.4, 1), WebcamCropMath.flipHorizontally(crop(0, 0, 0.4, 1)));
		assertCrop(crop(0, 0, 1, 1), WebcamCropMath.flipHorizontally(crop(0, 0, 1, 1)));
		// Flip twice is the identity.
		CropRegion once = WebcamCropMath.flipHorizontally(crop(0.2, 0.1, 0.3, 0.5));
		assertCrop(crop(0.2, 0.1, 0.3, 0.5), WebcamCropMath.flipHorizontally(once));
	}

	@Test
	@DisplayName("move stays inside the source")
	void moveClamps() {
		assertCrop(crop(0.1, 0.1, 0.5, 0.5),
				WebcamCropMath.resize(crop(0, 0, 0.5, 0.5), Handle.MOVE, 0.1, 0.1));
		assertCrop(crop(0.5, 0.5, 0.5, 0.5),
				WebcamCropMath.resize(crop(0.5, 0.5, 0.5, 0.5), Handle.MOVE, 0.4, 0.4));
		assertCrop(crop(0, 0, 0.5, 0.5),
				WebcamCropMath.resize(crop(0.5, 0.5, 0.5, 0.5), Handle.MOVE, -0.9, -0.9));
	}

	@Test
	@DisplayName("nw/sw move the left edge, ne/se the right edge, clamped to an 8% minimum")
	void resizesCorners() {
		CropRegion base = crop(0.2, 0.2, 0.6, 0.6);
		assertCrop(crop(0.1, 0.1, 0.7, 0.7),
				WebcamCropMath.resize(base, Handle.NW, -0.1, -0.1));
		assertCrop(crop(0.2, 0.2, 0.7, 0.7),
				WebcamCropMath.resize(base, Handle.SE, 0.1, 0.1));
		assertCrop(crop(0.2, 0.1, 0.6, 0.7),
				WebcamCropMath.resize(base, Handle.NE, 0, -0.1));
		assertCrop(crop(0.1, 0.2, 0.7, 0.6),
				WebcamCropMath.resize(base, Handle.SW, -0.1, 0));
		// The right edge stops 8% after the left edge.
		assertCrop(crop(0.2, 0.2, WebcamCropMath.MIN_CROP_SIZE, 0.6),
				WebcamCropMath.resize(base, Handle.SE, -0.9, 0));
		// The left edge stops 8% before the right edge.
		assertCrop(crop(0.8 - WebcamCropMath.MIN_CROP_SIZE, 0.2,
				WebcamCropMath.MIN_CROP_SIZE, 0.6),
				WebcamCropMath.resize(base, Handle.NW, 0.9, 0));
	}

	@Test
	@DisplayName("growth is clamped to the source bounds")
	void resizeClampsToSource() {
		assertCrop(crop(0.2, 0.2, 0.8, 0.8),
				WebcamCropMath.resize(crop(0.2, 0.2, 0.6, 0.6), Handle.SE, 0.5, 0.5));
		assertCrop(crop(0, 0, 0.8, 0.8),
				WebcamCropMath.resize(crop(0.2, 0.2, 0.6, 0.6), Handle.NW, -0.5, -0.5));
	}

	@Test
	@DisplayName("keyboard deltas are 1% and 5% with shift")
	void keyboardDeltas() {
		assertEquals(-0.01, WebcamCropMath.keyboardDelta("Left", false)[0], 1e-12);
		assertEquals(0.05, WebcamCropMath.keyboardDelta("Right", true)[0], 1e-12);
		assertEquals(-0.01, WebcamCropMath.keyboardDelta("Up", false)[1], 1e-12);
		assertEquals(0.05, WebcamCropMath.keyboardDelta("Down", true)[1], 1e-12);
		assertNull(WebcamCropMath.keyboardDelta("Enter", false));
	}
}
