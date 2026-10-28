package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the clip/timeline helper tests in {@code src/components/video-editor/types.test.ts}. */
class ClipsTest {

	private static ClipRegion clip(String id, long startMs, long endMs, Long sourceStartMs, double speed) {
		return new ClipRegion(id, startMs, endMs, sourceStartMs, null, null, speed, null, null, null, null);
	}

	private static ClipRegion clip(String id, long startMs, long endMs, double speed) {
		return clip(id, startMs, endMs, null, speed);
	}

	@Test
	@DisplayName("extends the default full-track clip when metadata duration grows")
	void extendsDefaultFullTrackClip() {
		assertEquals(
				List.of(clip("clip-1", 0, 8_000, 1)),
				Clips.extendAutoFullTrackClip(List.of(clip("clip-1", 0, 5_000, 1)), "clip-1", 5_000L, 8_000L));
	}

	@Test
	@DisplayName("does not change a clip that no longer matches the auto-created shape")
	void refusesChangedShape() {
		assertNull(Clips.extendAutoFullTrackClip(
				List.of(clip("clip-1", 0, 4_000, 1.5)), "clip-1", 5_000L, 8_000L));
	}

	@Test
	@DisplayName("does not change multi-clip timelines")
	void refusesMultiClipTimelines() {
		assertNull(Clips.extendAutoFullTrackClip(
				List.of(clip("clip-1", 0, 3_000, 1), clip("clip-2", 4_000, 8_000, 1)),
				"clip-1", 8_000L, 10_000L));
	}

	@Test
	@DisplayName("does not change clips when the duration does not grow")
	void refusesSameDuration() {
		assertNull(Clips.extendAutoFullTrackClip(
				List.of(clip("clip-1", 0, 8_000, 1)), "clip-1", 8_000L, 8_000L));
	}

	@Test
	@DisplayName("does not change clips when the auto-created clip id is missing")
	void refusesMissingAutoClipId() {
		assertNull(Clips.extendAutoFullTrackClip(
				List.of(clip("clip-1", 0, 5_000, 1)), null, 5_000L, 8_000L));
	}

	@Test
	@DisplayName("does not change clips when the previous auto-created end time is missing")
	void refusesMissingPreviousEnd() {
		assertNull(Clips.extendAutoFullTrackClip(
				List.of(clip("clip-1", 0, 5_000, 1)), "clip-1", null, 8_000L));
	}

	@Test
	@DisplayName("does not change clips when the reported duration shrinks")
	void refusesShrinkingDuration() {
		assertNull(Clips.extendAutoFullTrackClip(
				List.of(clip("clip-1", 0, 8_000, 1)), "clip-1", 8_000L, 7_000L));
	}

	@Test
	@DisplayName("does not change clips when the tracked clip id no longer matches")
	void refusesWrongClipId() {
		assertNull(Clips.extendAutoFullTrackClip(
				List.of(clip("clip-1", 0, 5_000, 1)), "clip-2", 5_000L, 8_000L));
	}

	@Test
	@DisplayName("does not change clips when the clip no longer starts at zero")
	void refusesNonZeroStart() {
		assertNull(Clips.extendAutoFullTrackClip(
				List.of(clip("clip-1", 250, 5_000, 1)), "clip-1", 5_000L, 8_000L));
	}

	@Test
	@DisplayName("selects the next source at an exact cut, but keeps fractional times before it in the previous clip")
	void mapsExactCut() {
		List<ClipRegion> clips = List.of(clip("a", 0, 1000, 0L, 3), clip("b", 1000, 2000, 6000L, 1));
		assertEquals(6000, Clips.mapTimelineTimeToSourceTime(1000, clips));
		assertEquals(3000, Clips.mapTimelineTimeToSourceTime(999.9, clips));
		assertEquals(7000, Clips.mapTimelineTimeToSourceTime(2000, clips));

		List<ClipRegion> sourceAdjacent = List.of(
				clips.get(0),
				new ClipRegion("b", 2000, 3000, 3000L, null, null, 1, null, null, null, null));
		assertEquals(2000, Clips.mapSourceTimeToTimelineTime(3000, sourceAdjacent));
	}

	private static final List<ClipRegion> CLIPS = List.of(
			clip("clip-1", 0, 4_000, 1),
			clip("clip-2", 6_000, 8_000, 2));

	@Test
	@DisplayName("maps kept timeline time into source time")
	void mapsKeptTimelineTime() {
		assertEquals(1_500, Clips.mapTimelineTimeToSourceTime(1_500, CLIPS));
		assertEquals(8_000, Clips.mapTimelineTimeToSourceTime(7_000, CLIPS));
	}

	@Test
	@DisplayName("snaps timeline gaps to the nearest clip edge")
	void snapsTimelineGaps() {
		assertEquals(4_000, Clips.mapTimelineTimeToSourceTime(4_300, CLIPS));
		assertEquals(6_000, Clips.mapTimelineTimeToSourceTime(5_700, CLIPS));
	}

	@Test
	@DisplayName("maps kept source time back into timeline time")
	void mapsKeptSourceTime() {
		assertEquals(1_500, Clips.mapSourceTimeToTimelineTime(1_500, CLIPS));
		assertEquals(7_000, Clips.mapSourceTimeToTimelineTime(8_000, CLIPS));
	}

	@Test
	@DisplayName("snaps removed source gaps to the nearest kept boundary")
	void snapsSourceGaps() {
		assertEquals(4_000, Clips.mapSourceTimeToTimelineTime(4_200, CLIPS));
		assertEquals(6_000, Clips.mapSourceTimeToTimelineTime(5_900, CLIPS));
	}

	@Test
	@DisplayName("maps gaps between different source and timeline positions")
	void mapsDifferentSourcePositions() {
		List<ClipRegion> movedClips = List.of(
				clip("clip-1", 0, 4_000, 0L, 1),
				clip("clip-2", 6_000, 8_000, 10_000L, 1));
		assertEquals(10_000, Clips.mapTimelineTimeToSourceTime(5_900, movedClips));
		assertEquals(6_000, Clips.mapSourceTimeToTimelineTime(9_900, movedClips));
	}

	@Test
	@DisplayName("finds clips only inside visible kept spans")
	void findsVisibleClips() {
		assertEquals("clip-1", Clips.findClipAtTimelineTime(500, CLIPS).id());
		assertNull(Clips.findClipAtTimelineTime(5_000, CLIPS));
	}

	@Test
	@DisplayName("derives the next clip id after converting trim gaps into clip ids")
	void derivesNextClipIdFromTrimGaps() {
		List<ClipRegion> clipsFromTrims = Clips.trimsToClips(
				List.of(new TrimRegion("trim-gap-1", 1_000, 2_000),
						new TrimRegion("trim-gap-2", 4_000, 5_000)),
				6_000);
		assertEquals(List.of("clip-1", "clip-2", "clip-3"),
				clipsFromTrims.stream().map(ClipRegion::id).toList());
		assertEquals(4, Ids.deriveNextId("clip", clipsFromTrims.stream().map(ClipRegion::id).toList()));
	}

	@Test
	@DisplayName("extends the timeline when a slow clip becomes longer than the source duration")
	void slowClipExtendsTimeline() {
		assertEquals(20_000, Clips.getTimelineDurationMs(List.of(clip("clip-1", 0, 20_000, 0.5)), 10_000));
	}

	@Test
	@DisplayName("shortens the timeline when speed edits make clips shorter")
	void fastClipShortensTimeline() {
		assertEquals(5_000, Clips.getTimelineDurationMs(List.of(clip("clip-1", 0, 5_000, 2)), 10_000));
	}

	@Test
	@DisplayName("trims the source ranges no clip covers")
	void trimsUncoveredSourceRanges() {
		List<ClipRegion> clips = List.of(
				clip("clip-1", 0, 10_000, 1),
				clip("clip-2", 10_000, 20_000, 30_000L, 1));
		assertEquals(
				List.of(new TrimRegion("trim-gap-1", 10_000, 30_000),
						new TrimRegion("trim-gap-2", 40_000, 60_000)),
				Clips.clipsToTrims(clips, 60_000));
	}

	@Test
	@DisplayName("covers source ranges that sit out of order on the timeline")
	void trimsOutOfOrderSourceRanges() {
		List<ClipRegion> clips = List.of(
				clip("clip-1", 0, 10_000, 20_000L, 1),
				clip("clip-2", 10_000, 20_000, 0L, 1));
		assertEquals(
				List.of(new TrimRegion("trim-gap-1", 10_000, 20_000),
						new TrimRegion("trim-gap-2", 30_000, 40_000)),
				Clips.clipsToTrims(clips, 40_000));
	}

	@Test
	@DisplayName("merges overlapping source spans instead of trimming between them")
	void mergesOverlappingSourceSpans() {
		List<ClipRegion> clips = List.of(
				clip("clip-1", 0, 20_000, 0L, 1),
				clip("clip-2", 20_000, 30_000, 10_000L, 1));
		assertEquals(List.of(new TrimRegion("trim-gap-1", 20_000, 40_000)),
				Clips.clipsToTrims(clips, 40_000));
	}
}
