package dev.gravaai.media.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;

/**
 * The GL orientation contract from plan §5.8: with a {@code SurfaceOrigin.TOP_LEFT} Skia
 * surface, {@code glReadPixels} must return row 0 = the visual top row, so the JavaFX
 * PixelBuffer and the YUV export path both get top-first data without a flip.
 */
class GlOrientationTest {

	private static final int WIDTH = 64;
	private static final int HEIGHT = 48;

	@Test
	@Timeout(60)
	@DisplayName("glReadPixels returns top-first rows for a TOP_LEFT surface")
	void readbackIsTopFirst() throws Exception {
		ByteBuffer frame = renderOneFrame((surface, frameIndex, timeSeconds) -> {
			Canvas canvas = surface.canvas();
			try (Paint paint = new Paint()) {
				paint.setColor(0xFF000000);
				canvas.drawColor(0xFF000000);
				paint.setColor(0xFFFF0000);
				canvas.drawRect(Rect.makeXYWH(0, 0, WIDTH / 2f, HEIGHT / 2f), paint);
				paint.setColor(0xFF00FF00);
				canvas.drawRect(Rect.makeXYWH(WIDTH / 2f, 0, WIDTH / 2f, HEIGHT / 2f), paint);
				paint.setColor(0xFF0000FF);
				canvas.drawRect(Rect.makeXYWH(0, HEIGHT / 2f, WIDTH / 2f, HEIGHT / 2f), paint);
				paint.setColor(0xFFFFFFFF);
				canvas.drawRect(Rect.makeXYWH(WIDTH / 2f, HEIGHT / 2f, WIDTH / 2f, HEIGHT / 2f),
						paint);
			}
		});
		assertPixel(frame, 2, 2, 255, 0, 0, "top-left quadrant must be red");
		assertPixel(frame, WIDTH - 3, 2, 0, 255, 0, "top-right quadrant must be green");
		assertPixel(frame, 2, HEIGHT - 3, 0, 0, 255, "bottom-left quadrant must be blue");
		assertPixel(frame, WIDTH - 3, HEIGHT - 3, 255, 255, 255,
				"bottom-right quadrant must be white");
	}

	/** Renders frames on a real gl-preview thread until the first read-back frame arrives. */
	private static ByteBuffer renderOneFrame(GlPreviewThread.FrameRenderer renderer)
			throws InterruptedException {
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA, renderer,
				(pixels, width, height, frameIndex) -> {
					ByteBuffer source = pixels.duplicate();
					source.position(0);
					source.limit(width * height * 4);
					snapshot.duplicate().put(source);
					received.countDown();
				}, Duration.ofSeconds(10));
		ByteBuffer result = null;
		try {
			preview.start();
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			assertNotNull(preview.glDescription());
			if (!received.await(10, TimeUnit.SECONDS)) {
				fail("no frame read back; failure=" + preview.failure());
			}
			result = snapshot;
		} finally {
			preview.close();
		}
		return result;
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
