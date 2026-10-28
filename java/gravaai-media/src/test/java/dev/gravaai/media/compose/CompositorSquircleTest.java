package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/**
 * Stage 4 wiring (plan §5.8): the squircle mask clips the video layer with the ported geometry,
 * and the three-layer drop shadow from {@link SceneEffects#shadowLayers} is drawn behind it.
 */
class CompositorSquircleTest {

	private static final int WIDTH = 128;
	private static final int HEIGHT = 72;

	@Test
	@Timeout(60)
	@DisplayName("the squircle mask clips the video layer corners")
	void squircleMaskClipsCorners() throws Exception {
		RgbaFrame source = solid(WIDTH, HEIGHT, 255, 0, 0);
		FrameStyle style = new FrameStyle(50, false, 0);
		FrameInput input = FrameInput.ofSource(0, 0.0, source, Background.black(), 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, style);
		ByteBuffer frame = renderFrame(input);
		// radius = min(128, 72) * 50 / 100 = 36 (clamped to 36).
		assertPixel(frame, 1, 1, 0, 0, 0, "top-left corner is masked");
		assertPixel(frame, WIDTH - 2, 1, 0, 0, 0, "top-right corner is masked");
		assertPixel(frame, 1, HEIGHT - 2, 0, 0, 0, "bottom-left corner is masked");
		assertPixel(frame, WIDTH / 2, HEIGHT / 2, 255, 0, 0, "centre stays visible");
		assertPixel(frame, WIDTH / 2, 1, 255, 0, 0, "top edge centre stays visible");
		assertPixel(frame, 1, HEIGHT / 2, 255, 0, 0, "left edge centre stays visible");
	}

	@Test
	@Timeout(60)
	@DisplayName("the drop shadow is drawn behind the video layer and can be disabled")
	void shadowLayersAreDrawn() throws Exception {
		RgbaFrame source = solid(WIDTH, HEIGHT, 255, 0, 0);
		// linked padding 60% -> 12% per edge -> video 97.28 x 54.72 at (15.36, 8.64).
		LayoutSpec layout = new LayoutSpec(LayoutPadding.linked(60), LayoutCrop.FULL);
		Background background = new Background.Colour(0xFF808080);
		FrameInput withShadow = FrameInput.ofSource(0, 0.0, source, background, 0, layout,
				ZoomSpec.NONE, new FrameStyle(0, true, 1.0));
		ByteBuffer shadowFrame = renderFrame(withShadow);
		int belowVideo = (68 * WIDTH + WIDTH / 2) * 4;
		int shadowRed = Byte.toUnsignedInt(shadowFrame.get(belowVideo));
		assertTrue(shadowRed < 0x70, "expected a visible shadow below the video, got " + shadowRed);

		FrameInput withoutShadow = FrameInput.ofSource(0, 0.0, source, background, 0, layout,
				ZoomSpec.NONE, new FrameStyle(0, false, 0));
		ByteBuffer plainFrame = renderFrame(withoutShadow);
		assertEquals(0x80, Byte.toUnsignedInt(plainFrame.get(belowVideo)),
				"without the shadow the background colour must show");
	}

	private static ByteBuffer renderFrame(FrameInput input) throws InterruptedException {
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		AtomicReference<Compositor> compositorRef = new AtomicReference<>();
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					Compositor compositor = compositorRef.get();
					if (compositor == null) {
						compositor = new Compositor(WIDTH, HEIGHT);
						compositorRef.set(compositor);
					}
					compositor.render(surface, input);
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
			if (!received.await(10, TimeUnit.SECONDS)) {
				fail("no frame read back; failure=" + preview.failure());
			}
		} finally {
			preview.close();
			Compositor compositor = compositorRef.get();
			if (compositor != null) {
				compositor.close();
			}
		}
		return snapshot;
	}

	private static RgbaFrame solid(int width, int height, int red, int green, int blue) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int i = 0; i < width * height; i++) {
			buffer.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static void assertPixel(ByteBuffer frame, int x, int y, int red, int green, int blue,
			String message) {
		int offset = (y * WIDTH + x) * 4;
		assertEquals(red, Byte.toUnsignedInt(frame.get(offset)), message + " (red)");
		assertEquals(green, Byte.toUnsignedInt(frame.get(offset + 1)), message + " (green)");
		assertEquals(blue, Byte.toUnsignedInt(frame.get(offset + 2)), message + " (blue)");
	}
}
