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

	private static final class FakeDecoder implements VideoFrameDecoder {

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
	@DisplayName("returns null at the end of the media")
	void returnsNullAtEof() {
		FakeDecoder decoder = new FakeDecoder(3);
		try (DecoderFrameSource source = DecoderFrameSource.open(decoder)) {
			assertNull(source.frameAt(5.0));
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
