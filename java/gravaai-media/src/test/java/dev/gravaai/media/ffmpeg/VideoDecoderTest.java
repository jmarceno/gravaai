package dev.gravaai.media.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link VideoDecoder} against the committed fixtures (plan §5.7). */
class VideoDecoderTest {

	@Test
	@DisplayName("decodes every 1080p60 H.264 frame with monotonic timestamps")
	void decodesEveryH264Frame() {
		int count = 0;
		long lastPts = Long.MIN_VALUE;
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264())) {
			DecodedFrame frame;
			while ((frame = decoder.next()) != null) {
				assertEquals(1920, frame.width());
				assertEquals(1080, frame.height());
				assertTrue(frame.ptsUs() >= lastPts,
						"pts went backwards: " + frame.ptsUs() + " after " + lastPts);
				lastPts = frame.ptsUs();
				count++;
				if (count > 1000) {
					throw new AssertionError("decoder did not reach EOF");
				}
			}
		}
		assertEquals(120, count);
	}

	@Test
	@DisplayName("the first H.264 frame starts at zero")
	void firstFrameStartsAtZero() {
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264())) {
			DecodedFrame frame = decoder.next();
			assertNotNull(frame);
			assertTrue(frame.ptsUs() >= 0 && frame.ptsUs() < 100_000,
					"unexpected first pts " + frame.ptsUs());
			assertTrue(frame.durationUs() > 0, "frame duration missing");
		}
	}

	@Test
	@DisplayName("a 4:2:0 frame exposes three planes with the right geometry")
	void exposesYuvPlanes() {
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264())) {
			DecodedFrame frame = decoder.next();
			assertNotNull(frame);
			assertEquals(3, frame.planes().size());
			DecodedFrame.Plane luma = frame.planes().get(0);
			assertEquals(1920, luma.width());
			assertEquals(1080, luma.height());
			assertTrue(luma.stride() >= 1920);
			assertEquals(1920 * 1080, luma.data().remaining());
			DecodedFrame.Plane chroma = frame.planes().get(1);
			assertEquals(960, chroma.width());
			assertEquals(540, chroma.height());
		}
	}

	@Test
	@DisplayName("seekPrecise lands on the requested 1 s timestamp")
	void seekPreciseLandsOnTarget() {
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264())) {
			decoder.seekPrecise(1_000_000);
			DecodedFrame frame = decoder.next();
			assertNotNull(frame);
			long delta = Math.abs(frame.ptsUs() - 1_000_000);
			assertTrue(delta <= 20_000, "seek landed at " + frame.ptsUs() + " us (delta " + delta + ")");
			DecodedFrame following = decoder.next();
			assertNotNull(following);
			assertTrue(following.ptsUs() > frame.ptsUs());
		}
	}

	@Test
	@DisplayName("seekPrecise(0) returns the first frame again")
	void seekToStartReturnsFirstFrame() {
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264())) {
			DecodedFrame skipped = decoder.next();
			assertNotNull(skipped);
			decoder.seekPrecise(0);
			DecodedFrame frame = decoder.next();
			assertNotNull(frame);
			assertTrue(frame.ptsUs() < 100_000, "unexpected pts " + frame.ptsUs());
		}
	}

	@Test
	@DisplayName("decodes every 720p30 VP9 frame")
	void decodesVp9Frames() {
		int count = 0;
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.vp9())) {
			DecodedFrame frame;
			while ((frame = decoder.next()) != null) {
				assertEquals(1280, frame.width());
				assertEquals(720, frame.height());
				count++;
				if (count > 1000) {
					throw new AssertionError("decoder did not reach EOF");
				}
			}
		}
		assertEquals(60, count);
	}

	@Test
	@DisplayName("next() returns null at EOF and after close the decoder is unusable")
	void endOfStreamAndClose() {
		VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264());
		decoder.close();
		decoder.close();
		assertThrows(IllegalStateException.class, decoder::next);
		try (VideoDecoder fresh = VideoDecoder.open(MediaFixtures.h264())) {
			assertNotNull(fresh.next());
			fresh.seekPrecise(2_000_000);
			assertNull(fresh.next());
		}
	}
}
