package dev.gravaai.media.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Port of {@code src/lib/exporter/videoColorSpace.test.ts}, test name included.
 */
class VideoColorSpaceTest {

	@Test
	@DisplayName("does not confuse full-range RGB input with encoded YUV output")
	void doesNotConfuseFullRangeRgbInputWithEncodedYuvOutput() {
		assertEquals("rgb", VideoColorSpace.EXPORT_CANVAS_COLOR_SPACE.matrix());
		assertTrue(VideoColorSpace.EXPORT_CANVAS_COLOR_SPACE.fullRange());

		assertEquals("bt709", VideoColorSpace.ENCODED_H264_COLOR_SPACE_FALLBACK.matrix());
		assertEquals("bt709", VideoColorSpace.ENCODED_H264_COLOR_SPACE_FALLBACK.transfer());
		assertFalse(VideoColorSpace.ENCODED_H264_COLOR_SPACE_FALLBACK.fullRange());
	}

	@Test
	@DisplayName("the source fallback is limited-range BT.709")
	void sourceFallbackIsLimitedRangeBt709() {
		ColourInfo fallback = VideoColorSpace.defaultSourceColour();
		assertEquals("bt709", fallback.spaceName());
		assertTrue(fallback.limitedRange());
		assertFalse(fallback.fullRange());
	}
}
