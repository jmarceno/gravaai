package dev.gravaai.ui.editor.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.gravaai.media.compose.Background;
import dev.gravaai.media.compose.FrameInput;
import dev.gravaai.media.compose.RgbaFrame;
import javafx.scene.image.Image;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;

/**
 * Deterministic filmstrip adapter test over recording's real {@code CompositorFrameAtTime} API
 * (media GL renderer, disposed by the provider). The input factory supplies a compositor
 * {@link FrameInput} fixture; if the GL context is unavailable the test aborts like media's own
 * CompositorFrameAtTimeTest instead of failing.
 */
@ExtendWith(ApplicationExtension.class)
class CompositorFilmstripProviderTest {

	private static final int WIDTH = 160;
	private static final int HEIGHT = 90;

	private static RgbaFrame solid(int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(WIDTH, HEIGHT, buffer);
	}

	@Test
	@Timeout(60)
	@DisplayName("renders a composited filmstrip frame and disposes the renderer")
	void rendersFrameAndDisposes() throws Exception {
		AtomicReference<Double> requestedTime = new AtomicReference<>();
		AtomicInteger renders = new AtomicInteger();
		CompositorFilmstripProvider provider;
		try {
			provider = new CompositorFilmstripProvider(WIDTH, HEIGHT, time -> {
				requestedTime.set(time);
				renders.incrementAndGet();
				return FrameInput.ofSource(0, time, solid(120), new Background.Colour(0xFF000000));
			});
		} catch (RuntimeException error) {
			Assumptions.abort("GL context unavailable: " + error.getMessage());
			return;
		}
		try (provider) {
			CountDownLatch ready = new CountDownLatch(1);
			provider.setOnFrameReady(ready::countDown);

			provider.requestFrame("clip-1", 1_500, WIDTH, HEIGHT);
			provider.requestFrame("clip-1", 1_500, WIDTH, HEIGHT);

			assertTrue(ready.await(20, TimeUnit.SECONDS),
					"filmstrip frame render timed out: " + provider.lastFailure());
			if (provider.lastFailure() != null
					&& provider.lastFailure().contains("GL context unavailable")) {
				Assumptions.abort("GL context unavailable: " + provider.lastFailure());
			}
			assertNull(provider.lastFailure(), "renderer failure: " + provider.lastFailure());
			assertEquals(1.5, requestedTime.get(), 1e-6, "the timeline time reaches the factory");
			assertEquals(1, renders.get(), "duplicate requests for the same time must be coalesced");

			Image frame = provider.frameAt("clip-1", 1_500, WIDTH, HEIGHT);
			assertTrue(frame != null, "rendered thumbnail must be cached");
			assertEquals(WIDTH, (int) frame.getWidth());
			assertEquals(HEIGHT, (int) frame.getHeight());
			int argb = frame.getPixelReader().getArgb(WIDTH / 2, HEIGHT / 2);
			assertEquals(120, (argb >> 16) & 0xFF, "source frame colour is composited through");
			assertEquals(1, provider.cachedFrameCount());
		}
		assertTrue(provider.isClosed(), "close() disposes the renderer");
		assertNull(provider.frameAt("clip-1", 1_500, WIDTH, HEIGHT), "cache is cleared on close");
		provider.close();
		assertTrue(provider.isClosed(), "close() is idempotent");
	}

	@Test
	@Timeout(60)
	@DisplayName("reports a failed render instead of retrying forever")
	void reportsRenderFailure() throws Exception {
		CompositorFilmstripProvider provider;
		try {
			provider = new CompositorFilmstripProvider(WIDTH, HEIGHT, time -> null);
		} catch (RuntimeException error) {
			Assumptions.abort("GL context unavailable: " + error.getMessage());
			return;
		}
		try (provider) {
			CountDownLatch ready = new CountDownLatch(1);
			provider.setOnFrameReady(ready::countDown);

			provider.requestFrame("clip-1", 500, WIDTH, HEIGHT);

			assertTrue(ready.await(20, TimeUnit.SECONDS),
					"failed render must still notify the repaint hook");
			if (provider.lastFailure() != null
					&& provider.lastFailure().contains("GL context unavailable")) {
				Assumptions.abort("GL context unavailable: " + provider.lastFailure());
			}
			assertTrue(provider.lastFailure() != null, "factory returning null must be reported");
			assertNull(provider.frameAt("clip-1", 500, WIDTH, HEIGHT));
		}
	}
}
