package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Decode-ahead ring behaviour with a fake decoder (plan §5.9). */
@Timeout(15)
class DecoderFrameSourceTest {

	private static final long FRAME_US = 33_333;

	private static class FakeDecoder implements VideoFrameDecoder {

		private final List<VideoFrame> frames = new ArrayList<>();
		final List<Double> seeks = new ArrayList<>();
		int index;
		boolean closed;

		FakeDecoder(int frameCount) {
			for (int frame = 0; frame < frameCount; frame++) {
				frames.add(new VideoFrame(2, 2, frame * FRAME_US, ByteBuffer.allocateDirect(16)));
			}
		}

		@Override
		public void seek(double seconds) {
			seeks.add(seconds);
			long targetUs = Math.round(seconds * 1_000_000);
			index = 0;
			while (index < frames.size() && frames.get(index).ptsUs() < targetUs) {
				index++;
			}
		}

		@Override
		public VideoFrame next() {
			if (index >= frames.size()) {
				return null;
			}
			return frames.get(index++);
		}

		@Override
		public double durationSeconds() {
			return 10;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	@Test
	@DisplayName("advances the ring without seeking for forward reads")
	void advancesRingWithoutSeeking() throws InterruptedException {
		FakeDecoder decoder = new FakeDecoder(300);
		try (DecoderFrameSource source = DecoderFrameSource.open(decoder)) {
			assertNotNull(source.frameAt(0.5));
			// Let the decode-ahead worker refill the ring before the forward read.
			Thread.sleep(50);
			assertNotNull(source.frameAt(0.55));
			assertEquals(1, decoder.seeks.size());
			assertEquals(10.0, source.durationSeconds());
		}
	}

	@Test
	@DisplayName("seeks when the request moves backwards")
	void seeksBackwards() {
		FakeDecoder decoder = new FakeDecoder(300);
		try (DecoderFrameSource source = DecoderFrameSource.open(decoder)) {
			assertNotNull(source.frameAt(0.5));
			assertNotNull(source.frameAt(0.1));
			assertTrue(decoder.seeks.size() >= 2);
			assertTrue(decoder.seeks.get(decoder.seeks.size() - 1) < 0.2);
		}
	}

	@Test
	@DisplayName("a request past the last frame returns the last frame")
	void pastEndReturnsLastFrame() {
		FakeDecoder decoder = new FakeDecoder(3);
		try (DecoderFrameSource source = DecoderFrameSource.open(decoder)) {
			assertNotNull(source.frameAt(5.0));
		}
	}

	@Test
	@DisplayName("an empty stream returns null instead of waiting")
	void emptyStreamReturnsNull() {
		FakeDecoder decoder = new FakeDecoder(0);
		try (DecoderFrameSource source = DecoderFrameSource.open(decoder)) {
			assertNull(source.frameAt(1.0, 2_000));
		}
	}

	@Test
	@DisplayName("seeking back after reaching the end decodes again (the preview went blank for good)")
	void seeksBackAfterEof() throws InterruptedException {
		FakeDecoder decoder = new FakeDecoder(60);
		try (DecoderFrameSource source = DecoderFrameSource.open(decoder)) {
			assertNotNull(source.frameAt(1.9));
			// Let the worker decode ahead into EOF.
			Thread.sleep(100);
			assertNotNull(source.frameAt(1.99));
			assertNotNull(source.frameAt(1.99));
			var back = source.frameAt(0.5);
			assertNotNull(back, "a seek back from the end must decode again");
			assertNotNull(source.frameAt(0.0));
		}
	}

	@Test
	@DisplayName("a decode failure is recovered by the next request")
	void recoversFromDecodeFailure() {
		FakeDecoder decoder = new FakeDecoder(60) {
			private boolean failed;

			@Override
			public VideoFrame next() {
				if (!failed && index > 30) {
					failed = true;
					throw new IllegalStateException("simulated decode error");
				}
				return super.next();
			}
		};
		try (DecoderFrameSource source = DecoderFrameSource.open(() -> decoder)) {
			try {
				source.frameAt(1.5);
			} catch (IllegalStateException expected) {
				// One request may become a gap frame.
			}
			assertNotNull(source.frameAt(0.2), "the source must recover after a decode error");
		}
	}

	@Test
	@DisplayName("close stops the worker and closes the decoder")
	void closeClosesDecoder() {
		FakeDecoder decoder = new FakeDecoder(30);
		DecoderFrameSource source = DecoderFrameSource.open(decoder);
		source.close();
		assertTrue(decoder.closed);
	}

	@Test
	@DisplayName("the provider opens one source per path")
	void providerOpensSources() {
		List<String> opened = new ArrayList<>();
		DecoderFrameSourceProvider provider = new DecoderFrameSourceProvider(path -> {
			opened.add(path);
			return new FakeDecoder(30);
		});
		try (FrameSource source = provider.open("/media/screen.mp4")) {
			// The provider opens the decoder inside the worker; the first read decodes frame 0.
			assertNotNull(source.frameAt(0.0));
			assertEquals(10.0, source.durationSeconds());
		}
		assertEquals(List.of("/media/screen.mp4"), opened);
	}
}
