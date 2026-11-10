package dev.gravaai.media.superres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.compose.ZoomTransform;
import dev.gravaai.media.superres.SuperResolution.Crop;
import io.github.humbleui.types.Rect;

/**
 * The crop math and the window policy behind the deep-zoom upscaler. No ONNX and no GL here: these
 * pin that the network receives exactly the source window the camera shows, that the padded window
 * still contains it, and that packing round-trips pixels without channel swaps.
 */
class SuperResolutionTest {

	/** A 1080p source drawn onto a 1920x1080 box, i.e. a 1:1 screen layer. */
	private static final Rect SOURCE = Rect.makeXYWH(0, 0, 1920, 1080);
	private static final Rect BOX = Rect.makeXYWH(0, 0, 1920, 1080);

	private static ZoomTransform.AppliedTransform camera(double scale, double x, double y) {
		return new ZoomTransform.AppliedTransform(scale, x, y);
	}

	@Test
	@DisplayName("a mild zoom is not upscaled")
	void mildZoomNotUpscaled() {
		assertNull(SuperResolution.cropFor(SOURCE, BOX, camera(1.0, 0, 0), 1920, 1080));
		assertNull(SuperResolution.cropFor(SOURCE, BOX, camera(2.4, 0, 0), 1920, 1080));
		assertFalse(SuperResolution.shouldUpscale(3.4));
		assertTrue(SuperResolution.shouldUpscale(3.5));
		assertTrue(SuperResolution.shouldUpscale(5.0));
	}

	@Test
	@DisplayName("a 5x zoom anchored on the frame centre shows a fifth of the source")
	void centredZoomCropsAFifth() {
		// The compositor applies canvas.scale(s) then canvas.translate(t), i.e. canvas = s*box + t.
		// To keep the box centre on the canvas centre the translation is -(s-1)*centre.
		ZoomTransform.AppliedTransform centred = camera(5, -4 * 960, -4 * 540);
		Crop crop = SuperResolution.cropFor(SOURCE, BOX, centred, 1920, 1080);
		assertNotNull(crop);
		// 1920/5 x 1080/5, centred on the frame: exactly a fifth of the source.
		assertEquals(384, crop.width());
		assertEquals(216, crop.height());
		assertEquals(768, crop.x());
		assertEquals(432, crop.y());
		assertEquals(1152, crop.right());
		assertEquals(648, crop.bottom());
	}

	@Test
	@DisplayName("the camera translation moves the window, clamped to the source")
	void translationMovesAndClamps() {
		// canvas = 5*box + t, so the window's left edge sits at -t/5 in source pixels: a negative
		// translation shows the part of the source left of the origin, a positive one the part
		// right of it.
		Crop shifted = SuperResolution.cropFor(SOURCE, BOX, camera(5, -500, 0), 1920, 1080);
		assertNotNull(shifted);
		assertEquals(100, shifted.x());
		assertEquals(384, shifted.width());

		Crop atOrigin = SuperResolution.cropFor(SOURCE, BOX, camera(5, 0, 0), 1920, 1080);
		assertNotNull(atOrigin);
		assertEquals(0, atOrigin.x(), "a zero translation anchors the zoom at the top-left");

		// A window that runs off the right edge is clipped to it, not dropped.
		Crop clamped = SuperResolution.cropFor(SOURCE, BOX, camera(5, -9000, 0), 1920, 1080);
		assertNotNull(clamped);
		assertEquals(1800, clamped.x());
		assertEquals(1920, clamped.right(), "the window never leaves the source");
		assertEquals(120, clamped.width(), "only the part still on frame is upscaled");

		// A window entirely outside the source has nothing to upscale.
		assertNull(SuperResolution.cropFor(SOURCE, BOX, camera(5, -100000, 0), 1920, 1080));
	}

	@Test
	@DisplayName("magnification is screen pixels per source pixel")
	void magnificationIsScreenPerSource() {
		// Half-size box at 5x: 960 * 5 / 1920 = 2.5 screen px per source px.
		Rect halfBox = Rect.makeXYWH(0, 0, 960, 540);
		assertEquals(2.5, SuperResolution.magnification(SOURCE, halfBox, camera(5, 0, 0)), 1e-9);
		assertFalse(SuperResolution.shouldUpscale(
				SuperResolution.magnification(SOURCE, halfBox, camera(5, 0, 0))));
	}

	@Test
	@DisplayName("the padded window contains the visible one and respects the cap")
	void paddingContainsVisibleAndRespectsCap() {
		Crop visible = new Crop(768, 432, 384, 216);
		Crop padded = SuperResolution.padded(visible, SOURCE);
		assertTrue(padded.contains(visible), "the padded window must cover the visible one");
		assertTrue(padded.width() >= visible.width());
		assertTrue(padded.height() >= visible.height());
		assertTrue(padded.pixels() <= SuperResolution.MAX_INFERENCE_PIXELS,
				"the padded window must fit one inference: " + padded.pixels());

		// The padding is real (a moving camera gets reuse out of it).
		assertTrue(padded.width() > visible.width());

		// An already-maximal window stays itself instead of being padded past the cap.
		Crop huge = new Crop(0, 0, SuperResolution.MAX_INFERENCE_PIXELS, 1);
		Crop unchanged = SuperResolution.padded(huge, Rect.makeXYWH(0, 0, 4096, 1));
		assertEquals(huge, unchanged);
	}

	@Test
	@DisplayName("padding clamps at the source edge instead of leaving it")
	void paddingClampsAtEdge() {
		Crop corner = new Crop(0, 0, 384, 216);
		Crop padded = SuperResolution.padded(corner, SOURCE);
		assertEquals(0, padded.x(), "no negative origin");
		assertEquals(0, padded.y());
		assertTrue(padded.contains(corner));
	}

	@Test
	@DisplayName("the upscaled window lands where the source crop would have been drawn")
	void destinationMatchesTheSourceMapping() {
		Rect box = Rect.makeXYWH(100, 50, 960, 540);
		Crop crop = new Crop(480, 270, 192, 108);
		Rect destination = SuperResolution.destinationOf(crop, SOURCE, box);
		// The same ratio the compositor uses to stretch the source onto the box.
		double scaleX = box.getWidth() / SOURCE.getWidth();
		double scaleY = box.getHeight() / SOURCE.getHeight();
		assertEquals(box._left + crop.x() * scaleX, destination._left, 1e-4);
		assertEquals(box._top + crop.y() * scaleY, destination._top, 1e-4);
		assertEquals(crop.width() * scaleX, destination.getWidth(), 1e-4);
		assertEquals(crop.height() * scaleY, destination.getHeight(), 1e-4);
	}

	@Test
	@DisplayName("window packing round-trips RGB without a channel swap")
	void packingRoundTripsPixels() {
		// A 2x2 frame with distinct channels, so any plane/lane mix-up is visible.
		ByteBuffer frame = ByteBuffer.allocateDirect(2 * 2 * 4);
		frame.put(new byte[] {(byte) 10, (byte) 20, (byte) 30, (byte) 255,
				(byte) 40, (byte) 50, (byte) 60, (byte) 255,
				(byte) 70, (byte) 80, (byte) 90, (byte) 255,
				(byte) 100, (byte) 110, (byte) 120, (byte) 255});
		frame.flip();

		ByteBuffer window = SuperResolution.copyWindow(frame, 2, new Crop(0, 0, 2, 2));
		assertEquals(16, window.remaining());

		float[] chw = SuperResolution.toChw(window, 2, 2);
		assertEquals(3 * 4, chw.length);
		assertEquals(10 / 255f, chw[0], 1e-6, "R of the first pixel");
		assertEquals(20 / 255f, chw[4], 1e-6, "G of the first pixel");
		assertEquals(30 / 255f, chw[8], 1e-6, "B of the first pixel");
		assertEquals(100 / 255f, chw[3], 1e-6, "R of the last pixel");

		ByteBuffer back = SuperResolution.fromChw(chw, 2, 2);
		assertEquals(16, back.remaining());
		assertEquals(10, Byte.toUnsignedInt(back.get(0)));
		assertEquals(20, Byte.toUnsignedInt(back.get(1)));
		assertEquals(30, Byte.toUnsignedInt(back.get(2)));
		assertEquals(255, Byte.toUnsignedInt(back.get(3)), "the upscaled window is opaque");
		assertEquals(100, Byte.toUnsignedInt(back.get(12)));
	}

	@Test
	@DisplayName("a sub-window copy reads the right rows")
	void copyWindowReadsTheRightRows() {
		// 4x3 frame, pixel value = x + 10 * y in the red channel.
		int width = 4;
		ByteBuffer frame = ByteBuffer.allocateDirect(width * 3 * 4);
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < width; x++) {
				frame.put((byte) (x + 10 * y)).put((byte) 0).put((byte) 0).put((byte) 255);
			}
		}
		frame.flip();
		ByteBuffer window = SuperResolution.copyWindow(frame, width, new Crop(1, 1, 2, 2));
		assertEquals(2 * 2 * 4, window.remaining());
		// (1,1), (2,1), (1,2), (2,2) -> 11, 12, 21, 22
		assertEquals(11, Byte.toUnsignedInt(window.get(0)));
		assertEquals(12, Byte.toUnsignedInt(window.get(4)));
		assertEquals(21, Byte.toUnsignedInt(window.get(8)));
		assertEquals(22, Byte.toUnsignedInt(window.get(12)));
	}

	@Test
	@DisplayName("the network input overshoot is clamped, not wrapped")
	void outputClampsInsteadOfWrapping() {
		float[] chw = {2.5f, -0.5f, 0.5f, 1.0f, 0.0f, 0.5f, 1.0f, 0.0f, 0.5f, 0.5f, 0.25f, 0.75f};
		// 2x2 window: plane is 4 values, so the values above are R plane, G plane, B plane.
		ByteBuffer out = SuperResolution.fromChw(chw, 2, 2);
		assertEquals(255, Byte.toUnsignedInt(out.get(0)), "2.5 clamps to full");
		assertEquals(0, Byte.toUnsignedInt(out.get(4)), "-0.5 clamps to zero");
	}

	@Test
	@DisplayName("a cropped layout upscales the cropped window, not the top-left of the frame")
	void croppedSourceUsesItsOrigin() {
		// The layout crop takes the middle 50% of a 1920x1080 frame and stretches it onto the box.
		Rect cropped = Rect.makeXYWH(480, 270, 960, 540);
		Rect box = Rect.makeXYWH(0, 0, 1920, 1080);
		// 5x centred on the box: the visible window is a fifth of the cropped rect.
		Crop crop = SuperResolution.cropFor(cropped, box, camera(5, -4 * 960, -4 * 540),
				1920, 1080);
		assertNotNull(crop);
		assertEquals(192, crop.width());
		assertEquals(108, crop.height());
		// The crop origin is added, never dropped: a centred zoom stays centred in the crop.
		assertEquals(480 + 384, crop.x());
		assertEquals(270 + 216, crop.y());
		assertTrue(crop.x() >= cropped._left && crop.right() <= cropped.getRight(),
				"the window stays inside the cropped rect");
	}

	@Test
	@DisplayName("a cropped window maps back to the same place it was drawn")
	void croppedDestinationRoundTrips() {
		Rect cropped = Rect.makeXYWH(480, 270, 960, 540);
		Rect box = Rect.makeXYWH(100, 50, 800, 450);
		Crop crop = new Crop(900, 400, 96, 54);
		Rect destination = SuperResolution.destinationOf(crop, cropped, box);
		double scaleX = box.getWidth() / cropped.getWidth();
		double scaleY = box.getHeight() / cropped.getHeight();
		assertEquals(box._left + (crop.x() - cropped._left) * scaleX, destination._left, 1e-3);
		assertEquals(box._top + (crop.y() - cropped._top) * scaleY, destination._top, 1e-3);
	}

	@Test
	@DisplayName("a cropped layout clamps the padded window to the crop, so nothing draws outside the box")
	void paddedStaysInsideTheCroppedSource() {
		// A layout crop of the middle 50%: any padded window that reached past it would map
		// outside the video box and paint over the background.
		Rect cropped = Rect.makeXYWH(480, 270, 960, 540);
		Crop visible = SuperResolution.cropFor(cropped, Rect.makeXYWH(0, 0, 1920, 1080),
				camera(5, -4 * 960, -4 * 540), 1920, 1080);
		assertNotNull(visible);
		Crop padded = SuperResolution.padded(visible, cropped);
		assertTrue(padded.contains(visible));
		assertTrue(padded.x() >= 480 && padded.right() <= 1440,
				"the padded window left the cropped rect horizontally: " + padded);
		assertTrue(padded.y() >= 270 && padded.bottom() <= 810,
				"the padded window left the cropped rect vertically: " + padded);
	}

	@Test
	@DisplayName("degenerate inputs are rejected rather than producing a bad window")
	void rejectsDegenerateInputs() {
		assertThrows(IllegalArgumentException.class, () -> new Crop(0, 0, 0, 10));
		assertThrows(IllegalArgumentException.class, () -> new Crop(-1, 0, 10, 10));
		assertNull(SuperResolution.cropFor(SOURCE, BOX, camera(Double.NaN, 0, 0), 1920, 1080));
		assertNull(SuperResolution.cropFor(Rect.makeXYWH(0, 0, 0, 0), BOX, camera(5, 0, 0),
				1920, 1080));
	}
}
