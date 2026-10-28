package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

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

import dev.gravaai.media.gl.GlContext;
import dev.gravaai.media.gl.GlFramebuffer;
import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;
import dev.gravaai.media.gl.SkiaGlSurface;

/**
 * The compositor pipeline end to end on a real GL thread: background, padded/cropped video
 * layer placement and the top-first PBO readback (plan §5.8 stages 1, 2 and 4).
 */
class CompositorPipelineTest {

	private static final int WIDTH = 200;
	private static final int HEIGHT = 100;
	private static final Background BACKGROUND = new Background.Colour(0xFFFF0000);

	@Test
	@Timeout(60)
	@DisplayName("renders the background and a padded video layer")
	void rendersPaddedVideoLayer() throws Exception {
		RgbaFrame source = solidFrame(20, 10, 0, 0, 255, 255);
		// linked padding 25% -> 5% of each edge -> video 180x90 at (10, 5)
		LayoutSpec layout = new LayoutSpec(LayoutPadding.linked(25), LayoutCrop.FULL);
		FrameInput input = FrameInput.ofSource(0, 0.0, source, BACKGROUND, 0, layout);
		ByteBuffer frame = renderFrame(input);
		assertPixel(frame, 2, 2, 255, 0, 0, "background top-left corner");
		assertPixel(frame, WIDTH - 3, HEIGHT - 3, 255, 0, 0, "background bottom-right corner");
		assertPixel(frame, 12, 7, 0, 0, 255, "video layer top-left");
		assertPixel(frame, WIDTH / 2, HEIGHT / 2, 0, 0, 255, "video layer centre");
		assertPixel(frame, WIDTH - 12, HEIGHT - 8, 0, 0, 255, "video layer bottom-right");
	}

	@Test
	@Timeout(60)
	@DisplayName("renders only the cropped source region")
	void rendersCroppedSourceRegion() throws Exception {
		// Left half white, right half blue; crop keeps the right half only.
		RgbaFrame source = splitFrame(20, 10);
		LayoutSpec layout = new LayoutSpec(LayoutPadding.NONE,
				new LayoutCrop(0.5, 0.0, 0.5, 1.0));
		FrameInput input = FrameInput.ofSource(0, 0.0, source, BACKGROUND, 0, layout);
		ByteBuffer frame = renderFrame(input);
		assertPixel(frame, 10, HEIGHT / 2, 255, 0, 0, "background left of the cropped content");
		assertPixel(frame, WIDTH / 2, HEIGHT / 2, 0, 0, 255, "cropped right half of the source");
		assertPixel(frame, WIDTH - 10, HEIGHT / 2, 255, 0, 0,
				"background right of the cropped content");
	}

	@Test
	@Timeout(60)
	@DisplayName("a mismatched render target size is rejected")
	void mismatchedTargetRejected() {
		GlContext context;
		try {
			context = GlContext.create();
		} catch (IllegalStateException | UnsatisfiedLinkError unavailable) {
			Assumptions.abort("GL unavailable: " + unavailable.getMessage());
			return;
		}
		try (context; GlFramebuffer framebuffer = new GlFramebuffer(100, 100);
				SkiaGlSurface surface = SkiaGlSurface.create(framebuffer);
				Compositor compositor = new Compositor(200, 100)) {
			FrameInput input = FrameInput.background(0, 0.0, BACKGROUND);
			IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
					() -> compositor.render(surface, input));
			assertNotNull(failure.getMessage());
		}
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
					ByteBuffer source = pixels.duplicate();
					source.position(0);
					source.limit(width * height * 4);
					snapshot.duplicate().put(source);
					received.countDown();
				},
				Duration.ofSeconds(10));
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

	static RgbaFrame solidFrame(int width, int height, int red, int green, int blue,
			int alpha) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int i = 0; i < width * height; i++) {
			buffer.put((byte) red).put((byte) green).put((byte) blue).put((byte) alpha);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static RgbaFrame splitFrame(int width, int height) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean rightHalf = x >= width / 2;
				buffer.put((byte) (rightHalf ? 0 : 255))
						.put((byte) 0)
						.put((byte) (rightHalf ? 255 : 0))
						.put((byte) 255);
			}
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
		assertEquals(255, Byte.toUnsignedInt(frame.get(offset + 3)), message + " (alpha)");
	}
}
