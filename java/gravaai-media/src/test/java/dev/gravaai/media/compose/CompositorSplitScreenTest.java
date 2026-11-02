package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/**
 * Split screen end to end through the compositor: the camera card sits inset in its strip beside or
 * above/below the content, the content fits the remaining region, and a hidden camera hands the
 * whole frame back to the content.
 */
class CompositorSplitScreenTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 200;
	private static final Background BACKGROUND = new Background.Colour(0xFF000000);
	private static final RgbaFrame SOURCE = solid(WIDTH, HEIGHT, 220, 20, 20);
	private static final RgbaFrame WEBCAM = solid(80, 40, 20, 200, 20);

	private static RgbaFrame solid(int width, int height, int red, int green, int blue) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int pixel = 0; pixel < width * height; pixel++) {
			buffer.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static WebcamSpec splitSpec(String preset, double sharePercent) {
		return new WebcamSpec(true, preset, "bottom-right", 1, 1, sharePercent, sharePercent,
				sharePercent, false, 0, 0.3, 24, false, LayoutCrop.FULL, List.of(), 0);
	}

	private static FrameInput input(double timeSeconds, WebcamSpec webcam) {
		return new FrameInput(0, timeSeconds, SOURCE, WEBCAM, List.of(), BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT,
				webcam);
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
		return rgb[0] > 180 && rgb[1] < 70 && rgb[2] < 70;
	}

	private static boolean isGreen(int[] rgb) {
		return rgb[1] > 160 && rgb[0] < 70 && rgb[2] < 70;
	}

	@Test
	@Timeout(60)
	@DisplayName("split-right fills a camera card on the right with the content beside it")
	void splitsRight() throws Exception {
		// 30% of the 400 px width is the camera strip: x >= 280. The 24 px margin (5 px at this
		// size) insets the card to 285..395 x 5..195. The 400x200 source fits its 280x200
		// content region at scale 0.7, so it spans the full width, vertically centred.
		ByteBuffer frame = render(input(0.0, splitSpec("split-right", 30)));
		assertTrue(isGreen(pixel(frame, 390, 100)), "camera strip missing on the right");
		assertTrue(isGreen(pixel(frame, 290, 10)) && isGreen(pixel(frame, 290, 190)),
				"camera card does not span its strip");
		assertTrue(!isGreen(pixel(frame, 282, 100)) && !isGreen(pixel(frame, 390, 2)),
				"camera card is not inset by the margin");
		assertTrue(isRed(pixel(frame, 100, 100)), "content missing beside the camera");
		assertTrue(isRed(pixel(frame, 270, 100)), "content does not reach the split");
	}

	@Test
	@Timeout(60)
	@DisplayName("split-left mirrors the strip to the left edge")
	void splitsLeft() throws Exception {
		ByteBuffer frame = render(input(0.0, splitSpec("split-left", 30)));
		assertTrue(isGreen(pixel(frame, 10, 100)), "camera strip missing on the left");
		assertTrue(isRed(pixel(frame, 300, 100)), "content missing beside the camera");
	}

	@Test
	@Timeout(60)
	@DisplayName("split-bottom stacks the camera below the content")
	void splitsBottom() throws Exception {
		// 40% of the 200 px height is the camera strip: y >= 120. The 400x200 source fits its
		// 400x120 content region at scale 0.6, so it is centred horizontally (x 80..320).
		ByteBuffer frame = render(input(0.0, splitSpec("split-bottom", 40)));
		assertTrue(isGreen(pixel(frame, 200, 190)), "camera strip missing at the bottom");
		assertTrue(isRed(pixel(frame, 200, 100)), "content missing above the camera");
		assertTrue(isRed(pixel(frame, 100, 100)) && isRed(pixel(frame, 300, 100)),
				"letterboxed content is not centred in its region");
		assertTrue(!isRed(pixel(frame, 20, 100)),
				"content must not spill past its region (contain fit)");
	}

	@Test
	@Timeout(60)
	@DisplayName("outside the webcam's visible ranges the content takes the whole frame back")
	void hiddenCameraExpandsContent() throws Exception {
		WebcamSpec hidden = new WebcamSpec(true, "split-right", "bottom-right", 1, 1, 30, 30, 30,
				false, 0, 0.3, 24, false, LayoutCrop.FULL,
				List.of(new WebcamSpec.VisibleRange(1000, 2000)), 0);
		ByteBuffer frame = render(input(0.0, hidden));
		assertTrue(isRed(pixel(frame, 390, 100)), "content did not expand to the full frame");
		assertTrue(!isGreen(pixel(frame, 390, 100)), "camera drawn outside its visible range");
	}
}
