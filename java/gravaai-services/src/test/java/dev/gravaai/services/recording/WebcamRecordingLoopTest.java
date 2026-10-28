package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.media.capture.WebcamCapture;
import dev.gravaai.media.capture.WebcamDevice;
import dev.gravaai.media.capture.WebcamFormat;

/**
 * The webcam capture loop. In the packaged app the MKV came out header-only: the real capture
 * fills the buffer with absolute puts and the loop's {@code flip()} then made every frame zero
 * bytes. Stop also never ended the loop (it set a different flag) and closed the capture under
 * a running read.
 */
@Timeout(30)
class WebcamRecordingLoopTest {

	private static final WebcamFormat FORMAT = new WebcamFormat(4, 2, 30, "yuyv422");
	private static final int FRAME_BYTES = 4 * 2 * 4;

	@Test
	@DisplayName("every captured frame reaches the encoder whole, and stop ends the loop")
	void framesReachTheEncoderAndStopEndsTheLoop(@TempDir Path dir) throws Exception {
		FakeCapture capture = new FakeCapture(33);
		CountingEncoder encoder = new CountingEncoder();
		WebcamRecordingService service = new WebcamRecordingService(
				() -> dir, null, null, (path, format) -> encoder,
				() -> List.of(new WebcamDevice("/dev/video-test", "Test camera")),
				(devicePath, width, height, fps) -> capture);

		WebcamRecordingService.StartResult started =
				service.start(1, System.currentTimeMillis(), "/dev/video-test", null);
		assertTrue(started.success(), () -> "start failed: " + started.message());
		assertTrue(capture.fiveFrames.await(10, TimeUnit.SECONDS), "the loop never read five frames");

		assertNull(started.recording().stop());

		assertTrue(encoder.frames.get() >= 5, "frames written: " + encoder.frames.get());
		assertEquals(encoder.frames.get() * FRAME_BYTES, encoder.bytes.get(),
				"each frame must be written whole");
		assertTrue(encoder.finished.get(), "stop must finalise the encoder");
		assertTrue(capture.closed.get(), "the capture must be closed");
		assertEquals("gravaai-webcam", capture.closedOn.get(),
				"the capture thread owns the device and closes it after its last read");
		long framesAtStop = capture.reads.get();
		Thread.sleep(200);
		assertEquals(framesAtStop, capture.reads.get(), "the loop kept reading after stop");
	}

	@Test
	@DisplayName("a 15 fps camera still fills a 30 fps track as long as the recording")
	void slowCameraFillsTheThirtyFpsTimeline(@TempDir Path dir) throws Exception {
		// A real C920 delivered ~15 fps indoors; written 1:1 into the 30 fps stream, a 121 s
		// recording produced a 60 s webcam track.
		FakeCapture capture = new FakeCapture(66);
		CountingEncoder encoder = new CountingEncoder();
		WebcamRecordingService service = new WebcamRecordingService(
				() -> dir, null, null, (path, format) -> encoder,
				() -> List.of(new WebcamDevice("/dev/video-test", "Test camera")),
				(devicePath, width, height, fps) -> capture);
		long anchorMs = System.currentTimeMillis();
		WebcamRecordingService.StartResult started =
				service.start(1, anchorMs, "/dev/video-test", null);
		assertTrue(started.success(), () -> "start failed: " + started.message());
		Thread.sleep(1200);
		assertNull(started.recording().stop());

		// The track runs on the screen's clock from the anchor, not from the first camera frame.
		long spanMs = capture.lastReadMs.get() - anchorMs;
		long expected = spanMs * 30 / 1000 + 1;
		long written = encoder.frames.get();
		assertTrue(Math.abs(written - expected) <= 1,
				() -> "wrote " + written + " frames for " + spanMs + " ms; expected " + expected);
		assertTrue(written > capture.reads.get() * 3 / 2,
				() -> "late frames must be repeated: " + written + " written, " + capture.reads.get() + " read");
		assertEquals(written * FRAME_BYTES, encoder.bytes.get());
	}

	@Test
	@DisplayName("the webcam track matches the screen video: offset 0, pauses cut, exact length")
	void trackMatchesTheScreenVideo(@TempDir Path dir) throws Exception {
		FakeCapture capture = new FakeCapture(33);
		CountingEncoder encoder = new CountingEncoder();
		WebcamRecordingService service = new WebcamRecordingService(
				() -> dir, null, null, (path, format) -> encoder,
				() -> List.of(new WebcamDevice("/dev/video-test", "Test camera")),
				(devicePath, width, height, fps) -> capture);
		WebcamRecordingService.StartResult prepared =
				service.prepare(1, "/dev/video-test", null);
		assertTrue(prepared.success(), () -> "prepare failed: " + prepared.message());
		WebcamRecordingService.WebcamRecording recording = prepared.recording();
		assertTrue(recording.awaitFirstFrame(5_000), "the camera never warmed up");
		Thread.sleep(200);
		assertEquals(0, encoder.frames.get(), "nothing is stored before the screen starts");

		long anchor = System.currentTimeMillis();
		recording.begin(anchor);
		Thread.sleep(300);
		recording.pause(System.currentTimeMillis());
		Thread.sleep(400);
		long writtenWhilePaused = encoder.frames.get();
		Thread.sleep(200);
		assertEquals(writtenWhilePaused, encoder.frames.get(), "a paused take stores no frames");
		recording.resume(System.currentTimeMillis());
		Thread.sleep(300);
		recording.endAt(System.currentTimeMillis());
		Thread.sleep(200);

		// A screen video of 0.7 s needs exactly 21 webcam frames at 30 fps.
		assertNull(recording.finish(700));
		assertEquals(0, recording.timeOffsetMs());
		assertTrue(encoder.frames.get() >= 21, "the track must cover the screen video: "
				+ encoder.frames.get());
		assertTrue(encoder.frames.get() <= 21 + 2, "the paused span must be cut: "
				+ encoder.frames.get());
	}

	/** Writes frames the way {@code V4l2WebcamCapture} does: absolute puts, position untouched. */
	private static final class FakeCapture implements WebcamCapture {

		final CountDownLatch fiveFrames = new CountDownLatch(5);
		final int periodMs;
		final AtomicLong firstReadMs = new AtomicLong();
		final AtomicLong lastReadMs = new AtomicLong();

		FakeCapture(int periodMs) {
			this.periodMs = periodMs;
		}
		final AtomicLong reads = new AtomicLong();
		final AtomicBoolean closed = new AtomicBoolean();
		final AtomicReference<String> closedOn = new AtomicReference<>();

		@Override
		public WebcamFormat format() {
			return FORMAT;
		}

		@Override
		public boolean readFrame(ByteBuffer rgbaTopFirst) {
			if (closed.get()) {
				throw new IllegalStateException("read after close");
			}
			try {
				Thread.sleep(periodMs);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				return false;
			}
			for (int i = 0; i < FRAME_BYTES; i++) {
				rgbaTopFirst.put(i, (byte) i);
			}
			long now = System.currentTimeMillis();
			firstReadMs.compareAndSet(0, now);
			lastReadMs.set(now);
			reads.incrementAndGet();
			fiveFrames.countDown();
			return true;
		}

		@Override
		public long frameCount() {
			return reads.get();
		}

		@Override
		public void close() {
			closedOn.set(Thread.currentThread().getName());
			closed.set(true);
		}
	}

	private static final class CountingEncoder implements WebcamRecordingService.WebcamVideoEncoder {

		final AtomicLong frames = new AtomicLong();
		final AtomicLong bytes = new AtomicLong();
		final AtomicBoolean finished = new AtomicBoolean();

		@Override
		public void writeFrame(ByteBuffer rgbaTopFirst) {
			frames.incrementAndGet();
			bytes.addAndGet(rgbaTopFirst.remaining());
			rgbaTopFirst.position(rgbaTopFirst.limit());
		}

		@Override
		public void finish() {
			finished.set(true);
		}

		@Override
		public void close() {
		}
	}
}
