package dev.gravaai.media.segmentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * The preview path's off-thread masking: {@code maskFor} must never block the render thread, must
 * keep returning the last good mask while a new one is in flight (an unsegmented frame would pop
 * the room back into focus), and must not build a backlog when it cannot keep up.
 */
class AsyncWebcamSegmenterTest {

	private static RgbaFrame frame(int width, int height, int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int pixel = 0; pixel < width * height; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) value);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	/** A segmenter whose inference blocks until the test releases it, counting requests. */
	private static final class GatedSegmenter implements WebcamSegmenter {
		private final CountDownLatch release = new CountDownLatch(1);
		private final CountDownLatch started = new CountDownLatch(1);
		private int calls;
		private int closed;

		@Override
		public RgbaFrame maskFor(RgbaFrame frame) {
			synchronized (this) {
				calls++;
			}
			started.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			return frame(frame.width(), frame.height(), 200);
		}

		void release() {
			release.countDown();
		}

		@Override
		public void close() {
			closed++;
		}

		synchronized int calls() {
			return calls;
		}

		synchronized int closes() {
			return closed;
		}
	}

	@Test
	@DisplayName("never blocks the caller, even while inference is stuck")
	void doesNotBlockCaller() throws Exception {
		GatedSegmenter delegate = new GatedSegmenter();
		try (AsyncWebcamSegmenter segmenter = new AsyncWebcamSegmenter(delegate)) {
			RgbaFrame input = frame(8, 8, 10);
			long started = System.nanoTime();
			// The worker parks on the gate, so this must return without waiting for it.
			assertNull(segmenter.maskFor(input), "no mask exists yet");
			assertNull(segmenter.maskFor(input), "still nothing completed");
			long elapsedMs = (System.nanoTime() - started) / 1_000_000;
			assertTrue(elapsedMs < 1000,
					"maskFor must not wait for inference, took " + elapsedMs + " ms");
			assertTrue(delegate.started.await(2, TimeUnit.SECONDS), "the worker must start");
			assertNull(segmenter.maskFor(input), "in flight still answers no mask");
		}
	}

	@Test
	@DisplayName("serves the last completed mask immediately on later frames")
	void servesLastMask() throws Exception {
		CountingSegmenter delegate = new CountingSegmenter();
		try (AsyncWebcamSegmenter segmenter = new AsyncWebcamSegmenter(delegate)) {
			RgbaFrame input = frame(8, 8, 10);
			assertNull(segmenter.maskFor(input), "first call has nothing to serve yet");
			assertTrue(awaitMask(segmenter), "the worker must produce a mask");
			assertEquals(1, delegate.calls(), "one request, one inference");

			// The next frames answer from the completed mask without waiting for their own.
			RgbaFrame served = segmenter.maskFor(frame(8, 8, 11));
			assertNotNull(served, "a completed mask must be served straight away");
			assertEquals(200, Byte.toUnsignedInt(served.pixelsTopFirstRgba().get(3)),
					"the served mask is the one the delegate produced");
		}
	}

	@Test
	@DisplayName("coalesces a burst of frames into at most one pending inference")
	void coalescesBurst() throws Exception {
		GatedSegmenter delegate = new GatedSegmenter();
		try (AsyncWebcamSegmenter segmenter = new AsyncWebcamSegmenter(delegate)) {
			// The worker takes the first frame and blocks; the rest must collapse into one slot.
			for (int i = 0; i < 20; i++) {
				segmenter.maskFor(frame(8, 8, i));
			}
			assertTrue(delegate.started.await(2, TimeUnit.SECONDS), "the worker must start");
			delegate.release();
			assertTrue(awaitMask(segmenter), "a mask must arrive after release");
			// The blocked first request plus at most one coalesced follow-up: a burst of 20 frames
			// must not queue 20 inferences.
			awaitIdle();
			assertTrue(delegate.calls() <= 2,
					"a 20-frame burst must coalesce, ran " + delegate.calls() + " inferences");
		}
	}

	@Test
	@DisplayName("closing stops the worker and closes the delegate exactly once")
	void closesDelegate() throws Exception {
		CountingSegmenter delegate = new CountingSegmenter();
		AsyncWebcamSegmenter segmenter = new AsyncWebcamSegmenter(delegate);
		segmenter.maskFor(frame(8, 8, 10));
		assertTrue(awaitMask(segmenter), "a mask must arrive before closing");
		segmenter.close();
		segmenter.close();
		assertEquals(1, delegate.closes(), "the delegate closes exactly once");
		assertNull(segmenter.maskFor(frame(8, 8, 12)), "a closed segmenter answers no mask");
	}

	private static boolean awaitMask(AsyncWebcamSegmenter segmenter) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (segmenter.hasMask()) {
				return true;
			}
			Thread.sleep(5);
		}
		return false;
	}

	private static void awaitIdle() throws InterruptedException {
		Thread.sleep(150);
	}

	/** A fast segmenter: completes immediately and counts calls. */
	private static final class CountingSegmenter implements WebcamSegmenter {
		private int calls;
		private int closed;

		@Override
		public RgbaFrame maskFor(RgbaFrame frame) {
			synchronized (this) {
				calls++;
			}
			return frame(frame.width(), frame.height(), 200);
		}

		@Override
		public void close() {
			synchronized (this) {
				closed++;
			}
		}

		synchronized int calls() {
			return calls;
		}

		synchronized int closes() {
			return closed;
		}
	}
}
