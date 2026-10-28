package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/**
 * Stage 1 background rendering (plan §5.8): colour, vertical gradient, image cover fit and the
 * proportional blur, verified through the real GL readback path.
 */
class BackgroundRenderTest {

	private static final int WIDTH = 64;
	private static final int HEIGHT = 48;

	@TempDir
	Path tempDir;

	@Test
	@Timeout(60)
	@DisplayName("paints a flat colour background")
	void flatColourBackground() throws Exception {
		ByteBuffer frame = renderBackground(new Background.Colour(0xFF3366CC), 0);
		assertColour(frame, 4, 4, 0x33, 0x66, 0xCC);
		assertColour(frame, WIDTH - 5, HEIGHT - 5, 0x33, 0x66, 0xCC);
	}

	@Test
	@Timeout(60)
	@DisplayName("paints the vertical linear gradient the export uses")
	void verticalLinearGradient() throws Exception {
		ByteBuffer frame = renderBackground(
				Background.parse("linear-gradient(#ff0000, #0000ff)"), 0);
		// Top row should be red-dominant, bottom row blue-dominant (Skia interpolates in sRGB).
		assertTrue(Byte.toUnsignedInt(frame.get(0)) > 200,
				"top row is not red: " + pixel(frame, 1, 0));
		assertTrue(Byte.toUnsignedInt(frame.get(2)) < 60, "top row has too much blue");
		int bottom = (HEIGHT - 1) * WIDTH * 4 + WIDTH / 2 * 4;
		assertTrue(Byte.toUnsignedInt(frame.get(bottom + 2)) > 200,
				"bottom row is not blue: " + pixel(frame, WIDTH / 2, HEIGHT - 1));
		assertTrue(Byte.toUnsignedInt(frame.get(bottom)) < 60, "bottom row has too much red");
	}

	@Test
	@Timeout(60)
	@DisplayName("cover-fits an image wallpaper")
	void imageWallpaper() throws Exception {
		byte[] png = GoldenImages.encodePng(
				CompositorPipelineTest.solidFrame(4, 2, 0, 200, 0, 255));
		ByteBuffer frame = renderBackground(new Background.Image(png), 0);
		assertColour(frame, 2, 2, 0, 200, 0);
		assertColour(frame, WIDTH - 3, HEIGHT - 3, 0, 200, 0);
	}

	@Test
	@Timeout(60)
	@DisplayName("the proportional background blur keeps a flat colour flat")
	void blurredFlatColour() throws Exception {
		ByteBuffer frame = renderBackground(new Background.Colour(0xFFCC3311), 10);
		// Blurring a uniform colour must not change the colour or darken the edges.
		assertColour(frame, 1, 1, 0xCC, 0x33, 0x11);
		assertColour(frame, WIDTH / 2, HEIGHT / 2, 0xCC, 0x33, 0x11);
	}

	@Test
	@Timeout(60)
	@DisplayName("re-rendering with the cached background is stable")
	void cachedBackgroundIsStable() throws Exception {
		ByteBuffer frame = renderRepeatedBackground(new Background.Colour(0xFF102030));
		assertColour(frame, 10, 10, 0x10, 0x20, 0x30);
	}

	private ByteBuffer renderBackground(Background background, double blur) throws Exception {
		return renderFrames(FrameInput.background(0, 0.0, background, blur), 1);
	}

	private ByteBuffer renderRepeatedBackground(Background background) throws Exception {
		return renderFrames(FrameInput.background(0, 0.0, background, 0), 4);
	}

	/** Renders at least {@code frames} frames and returns the last delivered snapshot. */
	private ByteBuffer renderFrames(FrameInput input, int frames) throws Exception {
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch delivered = new CountDownLatch(frames);
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
					delivered.countDown();
				},
				Duration.ofSeconds(10));
		try {
			preview.start();
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!delivered.await(10, TimeUnit.SECONDS)) {
				fail("not enough frames read back; failure=" + preview.failure());
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

	private static void assertColour(ByteBuffer frame, int x, int y, int red, int green, int blue) {
		String actual = pixel(frame, x, y);
		assertEquals(red, Byte.toUnsignedInt(frame.get((y * WIDTH + x) * 4)),
				"red at " + x + "," + y + ": " + actual);
		assertEquals(green, Byte.toUnsignedInt(frame.get((y * WIDTH + x) * 4 + 1)),
				"green at " + x + "," + y + ": " + actual);
		assertEquals(blue, Byte.toUnsignedInt(frame.get((y * WIDTH + x) * 4 + 2)),
				"blue at " + x + "," + y + ": " + actual);
	}

	private static String pixel(ByteBuffer frame, int x, int y) {
		int offset = (y * WIDTH + x) * 4;
		return "rgba(" + Byte.toUnsignedInt(frame.get(offset)) + ","
				+ Byte.toUnsignedInt(frame.get(offset + 1)) + ","
				+ Byte.toUnsignedInt(frame.get(offset + 2)) + ","
				+ Byte.toUnsignedInt(frame.get(offset + 3)) + ")";
	}
}
