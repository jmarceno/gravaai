package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.audio.PulseRecordStream;
import dev.gravaai.media.audio.PulseSource;

/** Tests the level loop with a fake PulseAudio stream (the libpulse binding is media-native's). */
class MicLevelMeterServiceTest {

	private static final class FakeStream implements PulseRecordStream {

		private int reads;
		private volatile boolean closed;

		@Override
		public int read(float[] mono48k) {
			if (closed) {
				return -1;
			}
			if (reads < 3) {
				reads++;
				for (int index = 0; index < mono48k.length; index++) {
					mono48k[index] = (float) (0.5 * Math.sin(2 * Math.PI * 1000 * index / 48_000.0));
				}
				return mono48k.length;
			}
			// Like a real pa_simple_read: keep delivering frames (silence here) instead of
			// blocking forever, so the service can stop the pump between reads.
			try {
				Thread.sleep(5);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				return -1;
			}
			java.util.Arrays.fill(mono48k, 0.0f);
			return mono48k.length;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	@Test
	@DisplayName("publishes smoothed levels while the meter is open and zero when closed")
	void publishesLevelsAndZeroOnClose() throws InterruptedException {
		List<Double> levels = java.util.Collections.synchronizedList(new ArrayList<>());
		FakeStream stream = new FakeStream();
		MicLevelMeterService service = new MicLevelMeterService(
				levels::add,
				0.8,
				sourceName -> stream,
				() -> List.of(new PulseSource("mic", "Microphone")));

		assertEquals(1, service.listSources().size());
		service.open("mic");

		long deadline = System.currentTimeMillis() + 2000;
		while (levels.size() < 3 && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertTrue(levels.size() >= 3);
		assertTrue(levels.stream().anyMatch(level -> level > 0));

		service.close();
		assertEquals(0.0, levels.get(levels.size() - 1));
		assertTrue(stream.closed);
	}

	/** Stream that blocks inside read until released, recording any close that races the read. */
	private static final class BlockingStream implements PulseRecordStream {
		private final java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
		private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		volatile boolean closed;
		volatile boolean closedWhileReading;

		@Override
		public int read(float[] mono48k) {
			reading.countDown();
			boolean wasClosed;
			try {
				release.await(5, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			}
			wasClosed = closed;
			if (wasClosed) {
				closedWhileReading = true;
				return -1;
			}
			java.util.Arrays.fill(mono48k, 0.25f);
			return mono48k.length;
		}

		void awaitReading() throws InterruptedException {
			assertTrue(reading.await(2, java.util.concurrent.TimeUnit.SECONDS));
		}

		void release() {
			release.countDown();
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	@Test
	@DisplayName("closing during a blocked read never frees the stream under the reader")
	void closeWaitsForBlockedReadBeforeFreeing() throws Exception {
		BlockingStream stream = new BlockingStream();
		MicLevelMeterService service = new MicLevelMeterService(
				level -> { },
				0.8,
				sourceName -> stream,
				() -> List.of(new PulseSource("mic", "Microphone")));
		service.open("mic");
		stream.awaitReading();

		// The level pump is blocked in read: close must not free the native stream yet.
		service.close();
		assertTrue(!stream.closed,
				"stream must stay open while the reader thread is inside read");
		assertTrue(!stream.closedWhileReading);

		// The reader exits its read, then frees the stream exactly once on its own thread.
		stream.release();
		long deadline = System.currentTimeMillis() + 2000;
		while (!stream.closed && System.currentTimeMillis() < deadline) {
			Thread.sleep(5);
		}
		assertTrue(stream.closed, "the reader frees the stream after its read returns");
		assertTrue(!stream.closedWhileReading);
		service.close();
	}

	@Test
	@DisplayName("switching microphones replaces the session without double-closing")
	void switchingMicrophonesIsSafe() throws Exception {
		List<BlockingStream> streams = new java.util.ArrayList<>();
		MicLevelMeterService service = new MicLevelMeterService(
				level -> { },
				0.8,
				sourceName -> {
					BlockingStream stream = new BlockingStream();
					streams.add(stream);
					return stream;
				},
				() -> List.of(new PulseSource("mic-a", "Mic A"),
						new PulseSource("mic-b", "Mic B")));
		service.open("mic-a");
		streams.get(0).awaitReading();
		service.open("mic-b");
		streams.get(0).release();
		streams.get(1).awaitReading();
		service.close();
		streams.get(1).release();
		long deadline = System.currentTimeMillis() + 2000;
		while ((!streams.get(0).closed || !streams.get(1).closed)
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(5);
		}
		assertTrue(streams.get(0).closed);
		assertTrue(streams.get(1).closed);
		assertTrue(!streams.get(0).closedWhileReading);
		assertTrue(!streams.get(1).closedWhileReading);
	}

	@Test
	@DisplayName("reports an unopenable microphone as level zero")
	void reportsUnopenableMicrophoneAsZero() {
		List<Double> levels = new ArrayList<>();
		MicLevelMeterService service = new MicLevelMeterService(
				levels::add,
				0.8,
				sourceName -> {
					throw new IllegalStateException("no such source");
				},
				List::of);
		service.open("missing");
		assertTrue(!levels.isEmpty());
		assertTrue(levels.stream().allMatch(level -> level == 0.0));
	}
}
