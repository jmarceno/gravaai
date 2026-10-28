package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.AudioRegion;

/** Ports {@code timelineAudioPlacement.test.ts}. */
class TimelineAudioPlacementTest {

	private static AudioRegion region(String id, long startMs, long endMs, Integer trackIndex) {
		return new AudioRegion(id, startMs, endMs, "/audio/" + id + ".mp3", 1.0, false, trackIndex);
	}

	@Test
	@DisplayName("uses the first available track when no preferred track is provided")
	void usesFirstAvailableTrack() {
		var placement = TimelineAudioPlacement.resolveAudioPlacement(
				List.of(region("a1", 0, 500, 0)), 500, 2000, 500, null);
		assertEquals(new TimelineAudioPlacement.AudioPlacement(0, 500), placement);
	}

	@Test
	@DisplayName("falls back to the next track when the first one is blocked")
	void fallsBackToNextTrack() {
		var placement = TimelineAudioPlacement.resolveAudioPlacement(
				List.of(region("a1", 0, 1500, 0)), 1000, 3000, 800, null);
		assertEquals(new TimelineAudioPlacement.AudioPlacement(1, 800), placement);
	}

	@Test
	@DisplayName("returns null when no slot is available")
	void returnsNullWhenNoSlot() {
		var placement = TimelineAudioPlacement.resolveAudioPlacement(
				List.of(region("a1", 0, 2000, 0)), 1500, 2000, 800, 0);
		assertNull(placement);
	}
}
