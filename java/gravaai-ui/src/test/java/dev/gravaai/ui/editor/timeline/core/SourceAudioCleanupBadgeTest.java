package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.AudioCleanup;

/** The timeline cleanup indicator: visibility and the text it shows for each stage combination. */
class SourceAudioCleanupBadgeTest {

	@Test
	@DisplayName("a track without cleanup shows nothing")
	void noCleanupShowsNothing() {
		assertFalse(SourceAudioCleanupBadge.visible(AudioCleanup.NONE));
		assertFalse(SourceAudioCleanupBadge.visible(null));
		assertEquals("", SourceAudioCleanupBadge.blockBadge(AudioCleanup.NONE));
		assertEquals("", SourceAudioCleanupBadge.laneMarker(null));
		assertEquals("", SourceAudioCleanupBadge.describe(AudioCleanup.NONE));
	}

	@Test
	@DisplayName("noise reduction alone marks the lane and the blocks")
	void noiseReductionOnly() {
		AudioCleanup cleanup = new AudioCleanup(AudioCleanup.NoiseReduction.BALANCED, 0, 0);
		assertTrue(SourceAudioCleanupBadge.visible(cleanup));
		assertEquals(SourceAudioCleanupBadge.BLOCK_BADGE,
				SourceAudioCleanupBadge.blockBadge(cleanup));
		assertEquals(SourceAudioCleanupBadge.LANE_MARKER,
				SourceAudioCleanupBadge.laneMarker(cleanup));
		assertEquals("NR balanced", SourceAudioCleanupBadge.describe(cleanup));
	}

	@Test
	@DisplayName("the high-pass alone is enough to mark the track")
	void highPassOnly() {
		AudioCleanup cleanup = new AudioCleanup(AudioCleanup.NoiseReduction.OFF, 120, 0);
		assertTrue(SourceAudioCleanupBadge.visible(cleanup));
		assertEquals("HP 120 Hz", SourceAudioCleanupBadge.describe(cleanup));
	}

	@Test
	@DisplayName("the loudness target alone is enough to mark the track")
	void loudnessOnly() {
		AudioCleanup cleanup = new AudioCleanup(AudioCleanup.NoiseReduction.OFF, 0, -16);
		assertTrue(SourceAudioCleanupBadge.visible(cleanup));
		assertEquals("-16 LUFS", SourceAudioCleanupBadge.describe(cleanup));
	}

	@Test
	@DisplayName("every stage is listed in a stable order")
	void allStages() {
		AudioCleanup cleanup = new AudioCleanup(AudioCleanup.NoiseReduction.STRONG, 80, -14);
		assertEquals("NR strong · HP 80 Hz · -14 LUFS",
				SourceAudioCleanupBadge.describe(cleanup));
		assertEquals("NR light", SourceAudioCleanupBadge
				.describe(new AudioCleanup(AudioCleanup.NoiseReduction.LIGHT, 0, 0)));
	}
}
