package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.compose.WebcamFrameFilter;

/** The preview's webcam edit runs once per decoded frame, with the frame's own times. */
class FilteredVideoFrameDecoderTest {

	private static final class Frames implements VideoFrameDecoder {
		private int next;
		boolean closed;

		@Override
		public void seek(double seconds) {
			next = (int) Math.round(seconds * 10);
		}

		@Override
		public @Nullable VideoFrame next() {
			if (next >= 3) {
				return null;
			}
			ByteBuffer pixels = ByteBuffer.allocateDirect(4 * 4 * 4);
			return new VideoFrame(4, 4, next++ * 100_000L, pixels);
		}

		@Override
		public double durationSeconds() {
			return 0.3;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	@Test
	@DisplayName("edits each frame in place with its media and timeline time, and closes both")
	void filtersEachFrame() {
		Frames frames = new Frames();
		List<double[]> calls = new ArrayList<>();
		boolean[] filterClosed = {false};
		WebcamFrameFilter filter = new WebcamFrameFilter() {
			@Override
			public void apply(dev.gravaai.media.compose.RgbaFrame frame, double webcamSeconds,
					double timelineSeconds) {
				calls.add(new double[] {webcamSeconds, timelineSeconds});
				frame.pixelsTopFirstRgba().put(0, (byte) 77);
			}

			@Override
			public void close() {
				filterClosed[0] = true;
			}
		};
		try (FilteredVideoFrameDecoder decoder = new FilteredVideoFrameDecoder(frames, filter,
				seconds -> seconds + 2)) {
			VideoFrame first = decoder.next();
			assertEquals(77, first.rgbaTopFirst().get(0), "the frame carries the edit");
			decoder.next();
			decoder.next();
			assertEquals(null, decoder.next());
		}
		assertEquals(3, calls.size());
		assertEquals(0.1, calls.get(1)[0], 1e-9);
		assertEquals(2.1, calls.get(1)[1], 1e-9);
		assertTrue(frames.closed && filterClosed[0]);
	}
}
