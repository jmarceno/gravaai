package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.ClipRegion;

/** Tests the per-clip audio execution plan with atempo chains (plan §5.9). */
class PlaybackAudioPlanTest {

	private static ClipRegion clip(String id, long startMs, long endMs, Long sourceStartMs, double speed) {
		return new ClipRegion(id, startMs, endMs, sourceStartMs, null, null, speed, null, null, null, null);
	}

	@Test
	@DisplayName("plans one segment per clip with the clip's atempo chain")
	void plansSegmentsWithAtempoChains() {
		List<PlaybackAudioPlan.AudioSegment> plan = PlaybackAudioPlan.plan(List.of(
				clip("a", 0, 1_000, 0L, 1),
				clip("b", 1_000, 3_000, 2_000L, 2),
				clip("c", 3_000, 4_000, 8_000L, 0.25)));
		assertEquals(3, plan.size());

		assertEquals(0.0, plan.get(0).timelineStartSeconds());
		assertEquals(1.0, plan.get(0).timelineEndSeconds());
		assertEquals(0.0, plan.get(0).sourceStartSeconds());
		assertEquals(1.0, plan.get(0).sourceEndSeconds());
		assertEquals(List.of(), plan.get(0).atempoFilters());

		assertEquals(2.0, plan.get(1).sourceStartSeconds());
		assertEquals(6.0, plan.get(1).sourceEndSeconds());
		assertEquals(List.of("atempo=2"), plan.get(1).atempoFilters());

		assertEquals(0.25, plan.get(2).speed());
		assertEquals(List.of("atempo=0.5", "atempo=0.5"), plan.get(2).atempoFilters());
	}

	@Test
	@DisplayName("sorts the plan by timeline start and skips empty clips")
	void sortsAndSkipsEmptyClips() {
		List<PlaybackAudioPlan.AudioSegment> plan = PlaybackAudioPlan.plan(List.of(
				clip("b", 2_000, 3_000, 0L, 1),
				clip("empty", 1_000, 1_000, 0L, 1),
				clip("a", 0, 1_000, 0L, 1)));
		assertEquals(List.of(0.0, 2.0), plan.stream()
				.map(PlaybackAudioPlan.AudioSegment::timelineStartSeconds)
				.toList());
	}

	@Test
	@DisplayName("an empty timeline has no audio segments")
	void emptyTimelineHasNoSegments() {
		assertTrue(PlaybackAudioPlan.plan(List.of()).isEmpty());
		assertTrue(PlaybackAudioPlan.plan(null).isEmpty());
	}
}
