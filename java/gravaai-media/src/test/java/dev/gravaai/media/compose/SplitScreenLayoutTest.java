package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Split screen region math: side-by-side and stacked camera strips (plan: split screen card). */
class SplitScreenLayoutTest {

	private static final int WIDTH = 1000;
	private static final int HEIGHT = 800;

	@Test
	@DisplayName("split-right puts a full-height camera strip on the right")
	void splitsRight() {
		SplitScreenLayout.Regions regions = SplitScreenLayout.resolve("split-right", WIDTH,
				HEIGHT, 30);
		assertNotNull(regions);
		assertEquals(WIDTH * 0.7, regions.camera()._left, 0.001);
		assertEquals(0, regions.camera()._top, 0.001);
		assertEquals(WIDTH, regions.camera().getRight(), 0.001);
		assertEquals(HEIGHT, regions.camera().getBottom(), 0.001);
		assertEquals(0, regions.content()._left, 0.001);
		assertEquals(WIDTH * 0.7, regions.content().getRight(), 0.001);
		assertEquals(HEIGHT, regions.content().getBottom(), 0.001);
		assertEquals(WIDTH, regions.content().getWidth() + regions.camera().getWidth(), 0.001);
	}

	@Test
	@DisplayName("split-left mirrors split-right")
	void splitsLeft() {
		SplitScreenLayout.Regions regions = SplitScreenLayout.resolve("split-left", WIDTH,
				HEIGHT, 25);
		assertNotNull(regions);
		assertEquals(0, regions.camera()._left, 0.001);
		assertEquals(WIDTH * 0.25, regions.camera().getRight(), 0.001);
		assertEquals(WIDTH * 0.25, regions.content()._left, 0.001);
		assertEquals(WIDTH, regions.content().getRight(), 0.001);
	}

	@Test
	@DisplayName("split-top stacks the camera above the content")
	void splitsTop() {
		SplitScreenLayout.Regions regions = SplitScreenLayout.resolve("split-top", WIDTH, HEIGHT,
				40);
		assertNotNull(regions);
		assertEquals(0, regions.camera()._top, 0.001);
		assertEquals(HEIGHT * 0.4, regions.camera().getBottom(), 0.001);
		assertEquals(WIDTH, regions.camera().getRight(), 0.001);
		assertEquals(HEIGHT * 0.4, regions.content()._top, 0.001);
		assertEquals(HEIGHT, regions.content().getBottom(), 0.001);
		assertEquals(HEIGHT, regions.content().getHeight() + regions.camera().getHeight(), 0.001);
	}

	@Test
	@DisplayName("split-bottom stacks the camera below the content")
	void splitsBottom() {
		SplitScreenLayout.Regions regions = SplitScreenLayout.resolve("split-bottom", WIDTH,
				HEIGHT, 40);
		assertNotNull(regions);
		assertEquals(HEIGHT * 0.6, regions.camera()._top, 0.001);
		assertEquals(HEIGHT, regions.camera().getBottom(), 0.001);
		assertEquals(0, regions.content()._top, 0.001);
		assertEquals(HEIGHT * 0.6, regions.content().getBottom(), 0.001);
	}

	@Test
	@DisplayName("the camera share is clamped so both regions stay visible")
	void clampsShare() {
		SplitScreenLayout.Regions tiny = SplitScreenLayout.resolve("split-right", WIDTH, HEIGHT,
				1);
		assertNotNull(tiny);
		assertEquals(WIDTH * SplitScreenLayout.MIN_SHARE, tiny.camera().getWidth(), 0.001);
		SplitScreenLayout.Regions huge = SplitScreenLayout.resolve("split-right", WIDTH, HEIGHT,
				100);
		assertNotNull(huge);
		assertEquals(WIDTH * SplitScreenLayout.MAX_SHARE, huge.camera().getWidth(), 0.001);
		assertEquals(WIDTH * (1 - SplitScreenLayout.MAX_SHARE), huge.content().getWidth(), 0.001);
	}

	@Test
	@DisplayName("overlay presets and degenerate sizes resolve to no split")
	void resolvesToNullForOverlayPresets() {
		assertNull(SplitScreenLayout.resolve("bottom-right", WIDTH, HEIGHT, 40));
		assertNull(SplitScreenLayout.resolve("custom", WIDTH, HEIGHT, 40));
		assertNull(SplitScreenLayout.resolve(null, WIDTH, HEIGHT, 40));
		assertNull(SplitScreenLayout.resolve("split-right", 0, HEIGHT, 40));
		assertNull(SplitScreenLayout.resolve("split-top", WIDTH, -1, 40));
	}

	@Test
	@DisplayName("isSplit matches exactly the four split wires")
	void isSplitMatchesOnlySplitWires() {
		assertTrue(SplitScreenLayout.isSplit("split-right"));
		assertTrue(SplitScreenLayout.isSplit("split-left"));
		assertTrue(SplitScreenLayout.isSplit("split-top"));
		assertTrue(SplitScreenLayout.isSplit("split-bottom"));
		assertFalse(SplitScreenLayout.isSplit("bottom-right"));
		assertFalse(SplitScreenLayout.isSplit(null));
	}
}
