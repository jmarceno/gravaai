package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/** Stage 7 real-GL coverage: webcam position, mirror, shadow, visible ranges and reactToZoom. */
class CompositorWebcamTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 200;
	private static final Background BACKGROUND = new Background.Colour(0xFF000000);
	private static final RgbaFrame SOURCE = solid(WIDTH, HEIGHT, 20, 20, 20);

	private static RgbaFrame solid(int width, int height, int red, int green, int blue) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int pixel = 0; pixel < width * height; pixel++) {
			buffer.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static RgbaFrame splitWebcam(int leftRed, int leftGreen, int leftBlue, int rightRed,
			int rightGreen, int rightBlue) {
		int width = 80;
		int height = 40;
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean left = x < width / 2;
				buffer.put((byte) (left ? leftRed : rightRed))
						.put((byte) (left ? leftGreen : rightGreen))
						.put((byte) (left ? leftBlue : rightBlue))
						.put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	/**
	 * A striped left half (blurred background candidate) and a solid right half (person): blur
	 * must visibly mix the stripes but leave the solid half alone.
	 */
	private static RgbaFrame stripedWebcam() {
		int width = 80;
		int height = 40;
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean left = x < width / 2;
				boolean blueStripe = (x / 4) % 2 == 0;
				int red = 0;
				int green = left ? (blueStripe ? 0 : 255) : 255;
				int blue = left ? (blueStripe ? 255 : 0) : 0;
				buffer.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	/** Person mask covering the right half only (mask value in every channel). */
	private static RgbaFrame rightHalfMask(int width, int height) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				byte value = (byte) (x < width / 2 ? 0 : 255);
				buffer.put(value).put(value).put(value).put(value);
			}
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static WebcamSpec spec(boolean enabled, String preset, double size, boolean mirror,
			double shadow, List<WebcamSpec.VisibleRange> ranges, boolean reactToZoom) {
		double x = "custom".equals(preset) ? 1 : 1;
		double y = 1;
		return new WebcamSpec(enabled, preset, "bottom-right", x, y, size, size, size, mirror,
				0, shadow, 0, reactToZoom, LayoutCrop.FULL, ranges, 0);
	}

	private static FrameInput input(double timeSeconds, RgbaFrame webcamFrame, WebcamSpec webcam,
			ZoomSpec zoom) {
		return new FrameInput(0, timeSeconds, SOURCE, webcamFrame, List.of(), BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, zoom, FrameStyle.DEFAULT, CursorSpec.DEFAULT, webcam);
	}

	private static ByteBuffer render(FrameInput input) throws Exception {
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					try (Compositor compositor = new Compositor(WIDTH, HEIGHT)) {
						compositor.render(surface, input);
					}
				},
				(pixels, width, height, frameIndex) -> {
					ByteBuffer from = pixels.duplicate();
					from.position(0);
					from.limit(width * height * 4);
					snapshot.duplicate().put(from);
					received.countDown();
				}, Duration.ofSeconds(10));
		try {
			preview.start();
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!received.await(15, TimeUnit.SECONDS)) {
				Assumptions.abort("no frame read back: " + preview.failure());
			}
		} finally {
			preview.close();
		}
		return snapshot;
	}

	private static int[] pixel(ByteBuffer frame, int x, int y) {
		int offset = (y * WIDTH + x) * 4;
		return new int[] {
				Byte.toUnsignedInt(frame.get(offset)),
				Byte.toUnsignedInt(frame.get(offset + 1)),
				Byte.toUnsignedInt(frame.get(offset + 2))};
	}

	private static boolean isRed(int[] rgb) {
		return rgb[0] > 200 && rgb[1] < 60 && rgb[2] < 60;
	}

	@Test
	@Timeout(60)
	@DisplayName("draws the webcam at the bottom-right preset")
	void drawsAtBottomRight() throws Exception {
		WebcamSpec webcam = spec(true, "bottom-right", 25, false, 0, List.of(), false);
		ByteBuffer frame = render(input(0.0, solid(40, 40, 255, 0, 0), webcam, ZoomSpec.NONE));
		assertTrue(isRed(pixel(frame, WIDTH - 2, HEIGHT - 2)), "webcam missing bottom-right");
		assertTrue(!isRed(pixel(frame, 3, 3)), "webcam leaked to top-left");
	}

	@Test
	@Timeout(60)
	@DisplayName("mirrors the webcam frame horizontally")
	void mirrorsHorizontally() throws Exception {
		RgbaFrame webcamFrame = splitWebcam(0, 0, 255, 0, 255, 0);
		WebcamSpec notMirrored = spec(true, "center", 50, false, 0, List.of(), false);
		ByteBuffer plain = render(input(0.0, webcamFrame, notMirrored, ZoomSpec.NONE));
		int[] leftPlain = pixel(plain, 170, 100);
		int[] rightPlain = pixel(plain, 230, 100);
		assertTrue(leftPlain[2] > 200 && leftPlain[1] < 60, "left half should be blue");
		assertTrue(rightPlain[1] > 200 && rightPlain[2] < 60, "right half should be green");

		WebcamSpec mirrored = spec(true, "center", 50, true, 0, List.of(), false);
		ByteBuffer flipped = render(input(0.0, webcamFrame, mirrored, ZoomSpec.NONE));
		int[] leftFlipped = pixel(flipped, 170, 100);
		int[] rightFlipped = pixel(flipped, 230, 100);
		assertTrue(leftFlipped[1] > 200, "mirrored left half should be green");
		assertTrue(rightFlipped[2] > 200, "mirrored right half should be blue");
	}

	@Test
	@Timeout(60)
	@DisplayName("draws the webcam drop shadow under the overlay")
	void drawsShadow() throws Exception {
		WebcamSpec noShadow = spec(true, "center", 25, false, 0, List.of(), false);
		WebcamSpec withShadow = spec(true, "center", 25, false, 0.5, List.of(), false);
		ByteBuffer plain = render(input(0.0, solid(40, 40, 255, 0, 0), noShadow, ZoomSpec.NONE));
		ByteBuffer shadowed = render(
				input(0.0, solid(40, 40, 255, 0, 0), withShadow, ZoomSpec.NONE));
		// The 56 px overlay ends at y=128: the shadow hugs its edge...
		int[] plainPixel = pixel(plain, 200, 130);
		int[] shadowPixel = pixel(shadowed, 200, 130);
		assertTrue(shadowPixel[0] < plainPixel[0],
				"shadow did not darken below the webcam: " + shadowPixel[0] + " vs "
						+ plainPixel[0]);
		// ...and does not spread a halo across the scene (it reached ~40% of the overlay size).
		assertEquals(pixel(plain, 200, 150)[0], pixel(shadowed, 200, 150)[0],
				"the webcam shadow must stay close to the overlay");
	}

	@Test
	@Timeout(60)
	@DisplayName("hides the webcam outside its visible ranges")
	void hidesOutsideVisibleRanges() throws Exception {
		WebcamSpec webcam = spec(true, "bottom-right", 25, false, 0,
				List.of(new WebcamSpec.VisibleRange(0, 500)), false);
		ByteBuffer hidden = render(input(1.0, solid(40, 40, 255, 0, 0), webcam, ZoomSpec.NONE));
		assertTrue(!isRed(pixel(hidden, WIDTH - 2, HEIGHT - 2)), "webcam visible outside range");
		ByteBuffer shown = render(input(0.25, solid(40, 40, 255, 0, 0), webcam, ZoomSpec.NONE));
		assertTrue(isRed(pixel(shown, WIDTH - 2, HEIGHT - 2)), "webcam hidden inside range");
	}

	@Test
	@Timeout(60)
	@DisplayName("reactToZoom counter-scales the overlay while the camera zooms")
	void reactsToZoom() throws Exception {
		ZoomRegionSpec hold = new ZoomRegionSpec("zoom", 0, 4000, ZoomDepth.D2,
				new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL);
		ZoomSpec zoom = new ZoomSpec(List.of(hold), 0.5, false, true, null, null, 1, 1, 1, 0);
		WebcamSpec reacting = spec(true, "bottom-right", 60, false, 0, List.of(), true);
		WebcamSpec fixed = spec(true, "bottom-right", 60, false, 0, List.of(), false);
		ByteBuffer reactingFrame = render(
				input(2.5, solid(40, 40, 255, 0, 0), reacting, zoom));
		ByteBuffer fixedFrame = render(input(2.5, solid(40, 40, 255, 0, 0), fixed, zoom));
		int reactingRed = countRed(reactingFrame);
		int fixedRed = countRed(fixedFrame);
		assertTrue(fixedRed > 0 && reactingRed > 0, "no webcam pixels drawn");
		assertTrue(reactingRed < fixedRed,
				"reactToZoom should shrink the overlay: " + reactingRed + " vs " + fixedRed);
	}

	private static int countRed(ByteBuffer frame) {
		int count = 0;
		for (int index = 0; index < WIDTH * HEIGHT; index++) {
			int offset = index * 4;
			if (Byte.toUnsignedInt(frame.get(offset)) > 200
					&& Byte.toUnsignedInt(frame.get(offset + 1)) < 60
					&& Byte.toUnsignedInt(frame.get(offset + 2)) < 60) {
				count++;
			}
		}
		return count;
	}

	@Test
	@Timeout(60)
	@DisplayName("blurs the webcam background and keeps the masked person sharp")
	void blursBackgroundThroughMask() throws Exception {
		// Frame: striped left half (background), solid green right half (person); mask keeps only
		// the right half. Center preset at 50% puts a 100x100 card at (150,50) (half the smaller
		// stage side); the 80x40 frame cover-fits at 2.5x (200x100, cropped to the card), so
		// source (50,20) lands at canvas (225,100) (person) and (26,20) at (165,100) (mid blue
		// background stripe).
		RgbaFrame webcamFrame = stripedWebcam();
		WebcamSpec webcam = spec(true, "center", 50, false, 0, List.of(), false);
		ByteBuffer plain = render(input(0.0, webcamFrame, webcam, ZoomSpec.NONE));

		ByteBuffer blurred = render(input(0.0, webcamFrame, webcam, ZoomSpec.NONE)
				.withWebcamMask(rightHalfMask(80, 40)));

		// The person half stays sharp and identical to the unblurred render.
		int[] plainPerson = pixel(plain, 225, 100);
		int[] blurredPerson = pixel(blurred, 225, 100);
		assertTrue(blurredPerson[1] > 200 && blurredPerson[2] < 60,
				"person half must stay sharp: " + blurredPerson[0] + "," + blurredPerson[1]
						+ "," + blurredPerson[2]);
		assertArrayEquals(plainPerson, blurredPerson, "the mask must not touch the person");

		// The striped background half is visibly mixed by the blur.
		int[] plainBackground = pixel(plain, 165, 100);
		int[] blurredBackground = pixel(blurred, 165, 100);
		assertTrue(plainBackground[2] > 200, "sample pixel must be a blue stripe: "
				+ plainBackground[0] + "," + plainBackground[1] + "," + plainBackground[2]);
		assertTrue(blurredBackground[2] < plainBackground[2] - 25
						&& blurredBackground[1] > plainBackground[1] + 10,
				"background half must be blurred: " + blurredBackground[0] + ","
						+ blurredBackground[1] + "," + blurredBackground[2]);
	}

	@Test
	@Timeout(60)
	@DisplayName("a mask below frame resolution still lands in register over the person")
	void acceptsLowerResolutionMask() throws Exception {
		// The segmenter produces the mask at model resolution (256x256 for the bundled model), not
		// at frame resolution: that upscale is the GPU's job through the mask matrix. A mask that
		// covers the whole frame at a smaller size must therefore composite exactly like a
		// full-resolution one.
		RgbaFrame webcamFrame = stripedWebcam();
		WebcamSpec webcam = spec(true, "center", 50, false, 0, List.of(), false);
		ByteBuffer fullSize = render(input(0.0, webcamFrame, webcam, ZoomSpec.NONE)
				.withWebcamMask(rightHalfMask(80, 40)));
		ByteBuffer smallMask = render(input(0.0, webcamFrame, webcam, ZoomSpec.NONE)
				.withWebcamMask(rightHalfMask(40, 20)));

		// Same sharp person and the same blurred background: the mask is a coverage map, so the
		// only difference a smaller mask can introduce is resampling noise at its hard edge.
		int[] fullPerson = pixel(fullSize, 225, 100);
		int[] smallPerson = pixel(smallMask, 225, 100);
		assertTrue(smallPerson[1] > 200 && smallPerson[2] < 60,
				"person half must stay sharp with a smaller mask: " + smallPerson[0] + ","
						+ smallPerson[1] + "," + smallPerson[2]);
		for (int channel = 0; channel < 3; channel++) {
			assertTrue(Math.abs(fullPerson[channel] - smallPerson[channel]) <= 8,
					"person pixel must match the full-size mask within resampling noise: "
							+ fullPerson[channel] + " vs " + smallPerson[channel]);
		}
		int[] fullBackground = pixel(fullSize, 165, 100);
		int[] smallBackground = pixel(smallMask, 165, 100);
		for (int channel = 0; channel < 3; channel++) {
			assertTrue(Math.abs(fullBackground[channel] - smallBackground[channel]) <= 8,
					"background pixel must match the full-size mask within resampling noise: "
							+ fullBackground[channel] + " vs " + smallBackground[channel]);
		}
	}

	private static byte[] toArray(ByteBuffer frame) {
		ByteBuffer duplicate = frame.duplicate();
		duplicate.position(0);
		byte[] bytes = new byte[duplicate.remaining()];
		duplicate.get(bytes);
		return bytes;
	}
}
