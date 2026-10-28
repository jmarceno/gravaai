package dev.gravaai.media.compose;

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
}
