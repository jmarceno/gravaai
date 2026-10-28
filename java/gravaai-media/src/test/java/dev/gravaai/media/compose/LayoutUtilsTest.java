package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Port of {@code src/components/video-editor/videoPlayback/layoutUtils.test.ts} (plan §8: tests
 * first, same cases and expected values).
 */
class LayoutUtilsTest {

	private static final int WIDTH = 1000;
	private static final int HEIGHT = 1000;
	private static final LayoutCrop FULL = LayoutCrop.FULL;
	private static final int VIDEO_WIDTH = 1000;
	private static final int VIDEO_HEIGHT = 1000;

	@Test
	@DisplayName("allows advanced bottom padding to pin the video to the top edge")
	void advancedBottomPaddingPinsToTop() {
		PaddedLayout layout = LayoutUtils.computePaddedLayout(WIDTH, HEIGHT,
				new LayoutPadding(0, LayoutUtils.ADVANCED_VERTICAL_PADDING_MAX, 0, 0, false),
				null, FULL, VIDEO_WIDTH, VIDEO_HEIGHT);
		assertEquals(0.0, layout.centerOffsetY(), 1e-6);
	}

	@Test
	@DisplayName("allows advanced top padding to pin the video to the bottom edge")
	void advancedTopPaddingPinsToBottom() {
		PaddedLayout layout = LayoutUtils.computePaddedLayout(WIDTH, HEIGHT,
				new LayoutPadding(LayoutUtils.ADVANCED_VERTICAL_PADDING_MAX, 0, 0, 0, false),
				null, FULL, VIDEO_WIDTH, VIDEO_HEIGHT);
		assertEquals(HEIGHT,
				layout.centerOffsetY() + layout.croppedDisplayHeight(), 1e-6);
	}

	@Test
	@DisplayName("preserves linked padding centering behavior")
	void linkedPaddingCentering() {
		PaddedLayout layout = LayoutUtils.computePaddedLayout(WIDTH, HEIGHT,
				new LayoutPadding(20, 20, 20, 20, true), null, FULL, VIDEO_WIDTH, VIDEO_HEIGHT);
		assertEquals(40.0, layout.centerOffsetY(), 1e-6);
		assertEquals(960.0, layout.centerOffsetY() + layout.croppedDisplayHeight(), 1e-6);
	}

	@Test
	@DisplayName("a crop region scales and centres the cropped content")
	void cropRegionPlacement() {
		PaddedLayout layout = LayoutUtils.computePaddedLayout(200, 100, LayoutPadding.NONE,
				null, new LayoutCrop(0.5, 0.0, 0.5, 1.0), 200, 100);
		assertEquals(1.0, layout.scale(), 1e-9);
		assertEquals(100.0, layout.cropStartX(), 1e-9);
		assertEquals(0.0, layout.cropStartY(), 1e-9);
		assertEquals(50.0, layout.centerOffsetX(), 1e-9);
		assertEquals(0.0, layout.centerOffsetY(), 1e-9);
		assertEquals(100.0, layout.croppedDisplayWidth(), 1e-9);
		assertEquals(100.0, layout.croppedDisplayHeight(), 1e-9);
	}

	@Test
	@DisplayName("frame insets scale the full frame inside the device screen")
	void frameInsetsScaleFullFrame() {
		PaddedLayout layout = LayoutUtils.computePaddedLayout(1000, 1000, LayoutPadding.NONE,
				new FrameInsets(0.1, 0.1, 0.1, 0.1), FULL, 1000, 1000);
		assertEquals(0.8, layout.scale(), 1e-9);
		assertEquals(1000.0, layout.fullFrameDisplayW(), 1e-9);
		assertEquals(100.0, layout.centerOffsetX(), 1e-9);
	}

	@Test
	@DisplayName("uses a percentage of the content's shorter side")
	void borderRadiusUsesShorterSide() {
		assertEquals(86.4, LayoutUtils.scalePreviewBorderRadius(1920, 1080, 8), 1e-6);
		assertEquals(43.2, LayoutUtils.scalePreviewBorderRadius(960, 540, 8), 1e-6);
		assertEquals(40.0, LayoutUtils.scalePreviewBorderRadius(500, 1000, 8), 1e-6);
	}

	@Test
	@DisplayName("clamps invalid or empty preview sizes to zero")
	void borderRadiusClampsInvalidSizes() {
		assertEquals(0.0, LayoutUtils.scalePreviewBorderRadius(0, 540, 16));
		assertEquals(0.0, LayoutUtils.scalePreviewBorderRadius(960, 0, 16));
		assertEquals(0.0, LayoutUtils.scalePreviewBorderRadius(960, 540, -8));
	}

	@Test
	@DisplayName("isZeroPadding covers the linked and asymmetric forms")
	void zeroPaddingDetection() {
		assertTrue(LayoutUtils.isZeroPadding(LayoutPadding.NONE));
		assertTrue(LayoutUtils.isZeroPadding(null));
		assertFalse(LayoutUtils.isZeroPadding(new LayoutPadding(0, 1, 0, 0, false)));
	}
}
