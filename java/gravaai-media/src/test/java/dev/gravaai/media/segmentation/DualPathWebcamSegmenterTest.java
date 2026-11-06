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
 * The two-path hand-off: the preview runs the fast model while playing and the precise one when
 * stopped, and a switch must never blank the card while the new model is still working.
 *
 * <p>Runs on fakes: no inference runtime, no model files.</p>
 */
class DualPathWebcamSegmenterTest {

	private static RgbaFrame frame(int width, int height, int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int pixel = 0; pixel < width * height; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) value);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	/** A segmenter that records its calls and can be told to answer nothing. */	private static final class RecordingSegmenter implements WebcamSegmenter {
		private final int maskValue;
		private boolean answering = true;
		private int calls;
		private int closes;
		private @org.jspecify.annotations.Nullable Runnable readyListener;

		RecordingSegmenter(int maskValue) {
			this.maskValue = maskValue;
		}

		@Override
		public @org.jspecify.annotations.Nullable RgbaFrame maskFor(RgbaFrame frame,
				MaskTuning tuning) {
			synchronized (this) {
				calls++;
			}
			return answering ? frame(frame.width(), frame.height(), maskValue) : null;
		}

		synchronized void stopAnswering() {
			answering = false;
		}

		synchronized int calls() {
			return calls;
		}

		synchronized int closes() {
			return closes;
		}

		@Override
		public void setMaskReadyListener(
				@org.jspecify.annotations.Nullable Runnable listener) {
			this.readyListener = listener;
		}

		@Override
		public void close() {
			synchronized (this) {
				closes++;
			}
		}
	}

	@Test
	@DisplayName("runs the fast model while the timeline plays")
	void usesFastPathByDefault() {
		RecordingSegmenter fast = new RecordingSegmenter(10);
		RecordingSegmenter precise = new RecordingSegmenter(20);
		try (DualPathWebcamSegmenter segmenter = new DualPathWebcamSegmenter(fast, precise,
				WebcamMaskQuality.FAST)) {
			RgbaFrame mask = segmenter.maskFor(frame(8, 8, 0));
			assertNotNull(mask);
			assertEquals(10, Byte.toUnsignedInt(mask.pixelsTopFirstRgba().get(3)));
			assertEquals(1, fast.calls());
			assertEquals(0, precise.calls(), "a playing timeline must not start the precise model");
		}
	}

	@Test
	@DisplayName("switches to the precise model once stopped")
	void switchesToPrecise() {
		RecordingSegmenter fast = new RecordingSegmenter(10);
		RecordingSegmenter precise = new RecordingSegmenter(20);
		try (DualPathWebcamSegmenter segmenter = new DualPathWebcamSegmenter(fast, precise,
				WebcamMaskQuality.FAST)) {
			segmenter.maskFor(frame(8, 8, 0));
			segmenter.setQuality(WebcamMaskQuality.PRECISE);
			assertEquals(WebcamMaskQuality.PRECISE, segmenter.quality());
			RgbaFrame mask = segmenter.maskFor(frame(8, 8, 0));
			assertNotNull(mask);
			assertEquals(20, Byte.toUnsignedInt(mask.pixelsTopFirstRgba().get(3)));
			assertEquals(1, precise.calls());
			assertEquals(1, fast.calls(), "the fast model must not run again once stopped");
		}
	}

	@Test
	@DisplayName("keeps the previous path's mask while the new path has none yet")
	void carriesMaskAcrossSwitch() {
		RecordingSegmenter fast = new RecordingSegmenter(10);
		RecordingSegmenter precise = new RecordingSegmenter(20);
		try (DualPathWebcamSegmenter segmenter = new DualPathWebcamSegmenter(fast, precise,
				WebcamMaskQuality.FAST)) {
			RgbaFrame fastMask = segmenter.maskFor(frame(8, 8, 0));
			assertNotNull(fastMask);
			// The precise model is still opening/warming: answer the fast mask rather than null, so
			// pausing does not flash an unsegmented card.
			precise.stopAnswering();
			segmenter.setQuality(WebcamMaskQuality.PRECISE);
			RgbaFrame served = segmenter.maskFor(frame(8, 8, 0));
			assertNotNull(served, "the carried fast mask must bridge the switch");
			assertEquals(10, Byte.toUnsignedInt(served.pixelsTopFirstRgba().get(3)));
		}
	}

	@Test
	@DisplayName("answers nothing before either path has produced a mask")
	void answersNullBeforeAnyMask() {
		RecordingSegmenter fast = new RecordingSegmenter(10);
		RecordingSegmenter precise = new RecordingSegmenter(20);
		fast.stopAnswering();
		try (DualPathWebcamSegmenter segmenter = new DualPathWebcamSegmenter(fast, precise,
				WebcamMaskQuality.FAST)) {
			assertNull(segmenter.maskFor(frame(8, 8, 0)),
					"with no mask from either path the card stays unsegmented");
		}
	}

	@Test
	@DisplayName("the async wrapper forwards the switch to the dual-path selector")
	void asyncWrapperForwardsQuality() throws Exception {
		// The preview puts AsyncWebcamSegmenter on top of the dual-path selector, so the switch has
		// to travel through the wrapper: a switch the wrapper swallowed would leave the preview on
		// the fast model forever while paused.
		RecordingSegmenter fast = new RecordingSegmenter(10);
		RecordingSegmenter precise = new RecordingSegmenter(20);
		DualPathWebcamSegmenter dual = new DualPathWebcamSegmenter(fast, precise,
				WebcamMaskQuality.FAST);
		try (AsyncWebcamSegmenter async = new AsyncWebcamSegmenter(dual)) {
			async.maskFor(frame(8, 8, 0), MaskTuning.DEFAULT);
			assertTrue(awaitMask(async), "the fast path must produce a mask");
			assertEquals(WebcamMaskQuality.FAST, dual.quality());

			async.setQuality(WebcamMaskQuality.PRECISE);
			assertEquals(WebcamMaskQuality.PRECISE, dual.quality(),
					"the wrapper must forward the path switch to the selector");

			async.maskFor(frame(8, 8, 0), MaskTuning.DEFAULT);
			// Let the worker pick the new request up; the precise model is the one that answers.
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline && precise.calls() == 0) {
				Thread.sleep(5);
			}
			assertTrue(precise.calls() > 0, "the precise path must run after the switch");
		}
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

	@Test
	@DisplayName("forwards the mask-ready listener to both paths and closes both")
	void forwardsListenerAndCloses() throws Exception {
		RecordingSegmenter fast = new RecordingSegmenter(10);
		RecordingSegmenter precise = new RecordingSegmenter(20);
		CountDownLatch fired = new CountDownLatch(2);
		DualPathWebcamSegmenter segmenter = new DualPathWebcamSegmenter(fast, precise,
				WebcamMaskQuality.FAST);
		segmenter.setMaskReadyListener(fired::countDown);
		assertNotNull(fast.readyListener, "the fast path must get the listener");
		assertNotNull(precise.readyListener, "the precise path must get the listener");
		fast.readyListener.run();
		precise.readyListener.run();
		assertTrue(fired.await(1, TimeUnit.SECONDS), "both listeners must fire");
		segmenter.close();
		assertEquals(1, fast.closes());
		assertEquals(1, precise.closes());
	}

	@Test
	@DisplayName("a quality change does not restart a model already selected")
	void idempotentQualityChange() {
		RecordingSegmenter fast = new RecordingSegmenter(10);
		RecordingSegmenter precise = new RecordingSegmenter(20);
		try (DualPathWebcamSegmenter segmenter = new DualPathWebcamSegmenter(fast, precise,
				WebcamMaskQuality.PRECISE)) {
			segmenter.setQuality(WebcamMaskQuality.PRECISE);
			segmenter.maskFor(frame(8, 8, 0));
			assertEquals(1, precise.calls());
			assertEquals(0, fast.calls());
		}
	}
}
