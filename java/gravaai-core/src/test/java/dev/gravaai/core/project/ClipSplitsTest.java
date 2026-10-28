package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code clipSplit.test.ts}. */
class ClipSplitsTest {

	private static java.util.function.Supplier<String> createIdFactory() {
		AtomicInteger next = new AtomicInteger(1);
		return () -> "clip-" + next.getAndIncrement();
	}

	private static ClipRegion clip(String id, long startMs, long endMs, double speed) {
		return new ClipRegion(id, startMs, endMs, null, null, null, speed, null, null, null, null);
	}

	private static ClipSplits.Plan splitAndDeleteMiddle(ClipRegion clip, double firstSplitMs,
			double secondOffsetMs) {
		var createId = createIdFactory();
		ClipSplits.Plan first = ClipSplits.planClipSplit(List.of(clip), firstSplitMs, createId);
		assertNotNull(first, "first split failed");
		ClipSplits.Plan second = ClipSplits.planClipSplit(List.of(first.right()),
				first.right().startMs() + secondOffsetMs, createId);
		assertNotNull(second, "second split failed");
		return new ClipSplits.Plan(first.targetId(), first.left(), second.right());
	}

	@Test
	@DisplayName("returns null when no clip contains the split position")
	void returnsNullWithoutContainingClip() {
		List<ClipRegion> clips = List.of(clip("clip-1", 0, 1000, 1));
		assertNull(ClipSplits.planClipSplit(clips, 2000, createIdFactory()));
		assertNull(ClipSplits.planClipSplit(clips, 0, createIdFactory()));
		assertNull(ClipSplits.planClipSplit(clips, 1000, createIdFactory()));
	}

	@Test
	@DisplayName("splits a 1x clip at the playhead")
	void splitsAtPlayhead() {
		ClipSplits.Plan plan = ClipSplits.planClipSplit(
				List.of(clip("clip-1", 0, 120_000, 1)), 30_000, createIdFactory());
		assertNotNull(plan);
		assertEquals(0, plan.left().startMs());
		assertEquals(30_000, plan.left().endMs());
		assertEquals(30_000, plan.right().startMs());
		assertEquals(120_000, plan.right().endMs());
	}

	@Test
	@DisplayName("anchors the right half to the source time the split maps to at non-1x speed")
	void anchorsRightHalfToSourceTime() {
		ClipRegion clip = clip("clip-1", 0, 60_000, 2);
		ClipSplits.Plan plan = ClipSplits.planClipSplit(List.of(clip), 10_000, createIdFactory());
		assertNotNull(plan);
		assertEquals(20_000, Clips.getClipSourceEndMs(plan.left()));
		assertEquals(20_000, Clips.getClipSourceStartMs(plan.right()));
		assertEquals(Clips.getClipSourceEndMs(clip), Clips.getClipSourceEndMs(plan.right()));
	}

	@Test
	@DisplayName("leaves both halves where the clip already sat on the timeline")
	void leavesHalvesInPlace() {
		ClipSplits.Plan plan = ClipSplits.planClipSplit(
				List.of(clip("clip-1", 0, 60_000, 2)), 10_000, createIdFactory());
		assertNotNull(plan);
		assertEquals(0, plan.left().startMs());
		assertEquals(10_000, plan.left().endMs());
		assertEquals(10_000, plan.right().startMs());
		assertEquals(60_000, plan.right().endMs());
	}

	@Test
	@DisplayName("keeps the timeline-to-source mapping continuous across the split")
	void keepsMappingContinuous() {
		ClipRegion clip = clip("clip-1", 0, 60_000, 2);
		ClipSplits.Plan plan = ClipSplits.planClipSplit(List.of(clip), 10_000, createIdFactory());
		assertNotNull(plan);
		List<ClipRegion> halves = List.of(plan.left(), plan.right());
		for (double timelineMs : new double[] {0, 5_000, 10_000, 30_000, 60_000}) {
			assertEquals(
					Clips.mapTimelineTimeToSourceTime(timelineMs, List.of(clip)),
					Clips.mapTimelineTimeToSourceTime(timelineMs, halves));
		}
	}

	@Test
	@DisplayName("keeps split halves contiguous in source time so no gap is trimmed")
	void keepsSourceTimeContiguous() {
		ClipRegion clip = clip("clip-1", 0, 48_000, 2.5);
		ClipSplits.Plan plan = ClipSplits.planClipSplit(List.of(clip), 17_333, createIdFactory());
		assertNotNull(plan);
		assertEquals(List.of(), Clips.clipsToTrims(List.of(plan.left(), plan.right()),
				Clips.getClipSourceEndMs(clip)));
	}

	@Test
	@DisplayName("removes the source range the user cut out when the clip is sped up")
	void removesCutSourceRange() {
		long sourceDurationMs = 120_000;
		ClipSplits.Plan kept = splitAndDeleteMiddle(clip("clip-1", 0, 60_000, 2), 10_000, 10_000);
		ClipRegion left = kept.left().id().equals(kept.left().id()) ? kept.left() : kept.left();
		// kept.left is the first half, kept.right is the tail; the middle left2 is the deleted range.
		ClipRegion deleted = new ClipRegion("deleted", 10_000, 20_000, 20_000L, null, null, 2,
				null, null, null, null);
		assertEquals(20_000, Clips.getClipSourceStartMs(deleted));
		assertEquals(40_000, Clips.getClipSourceEndMs(deleted));
		assertEquals(List.of(new TrimRegion("trim-gap-1", 20_000, 40_000)),
				Clips.clipsToTrims(List.of(left, kept.right()), sourceDurationMs));
		assertEquals(sourceDurationMs, Clips.getClipSourceEndMs(kept.right()));
	}

	@Test
	@DisplayName("preserves the gap and source positions after deleting the middle of a 3x clip")
	void preservesGapAfterDeletingMiddle() {
		long sourceDurationMs = 120_000;
		ClipRegion clip = clip("clip-1", 0, 40_000, 3);
		var createId = createIdFactory();
		ClipSplits.Plan first = ClipSplits.planClipSplit(List.of(clip), 10_000, createId);
		assertNotNull(first);
		ClipSplits.Plan second = ClipSplits.planClipSplit(
				List.of(first.right()), 20_000, createId);
		assertNotNull(second);
		List<ClipRegion> remaining = List.of(first.left(), second.right());

		assertEquals(0, remaining.get(0).startMs());
		assertEquals(10_000, remaining.get(0).endMs());
		assertEquals(20_000, remaining.get(1).startMs());
		assertEquals(40_000, remaining.get(1).endMs());
		assertEquals(60_000L, remaining.get(1).sourceStartMs());
		assertEquals(10_000, Clips.mapSourceTimeToTimelineTime(30_000, remaining));
		assertEquals(20_000, Clips.mapSourceTimeToTimelineTime(60_000, remaining));
		assertEquals(40_000, Clips.getTimelineDurationMs(remaining, sourceDurationMs));
	}

	@Test
	@DisplayName("removes the source range the user cut out at 1x")
	void removesCutSourceRangeAtOneX() {
		long sourceDurationMs = 120_000;
		ClipRegion clip = clip("clip-1", 0, sourceDurationMs, 1);
		var createId = createIdFactory();
		ClipSplits.Plan first = ClipSplits.planClipSplit(List.of(clip), 10_000, createId);
		assertNotNull(first);
		ClipSplits.Plan second = ClipSplits.planClipSplit(List.of(first.right()), 20_000, createId);
		assertNotNull(second);
		List<ClipRegion> kept = List.of(first.left(), second.right());

		assertEquals(List.of(new TrimRegion("trim-gap-1", 10_000, 20_000)),
				Clips.clipsToTrims(kept, sourceDurationMs));
		assertEquals(sourceDurationMs, Clips.getClipSourceEndMs(kept.get(1)));
	}

	@Test
	@DisplayName("carries clip settings into both halves")
	void carriesClipSettings() {
		ClipRegion clip = new ClipRegion("clip-1", 0, 60_000, null, null, null, 2, true, true,
				null, null);
		ClipSplits.Plan plan = ClipSplits.planClipSplit(List.of(clip), 10_000, createIdFactory());
		assertNotNull(plan);
		for (ClipRegion half : List.of(plan.left(), plan.right())) {
			assertEquals(2, half.speed());
			assertEquals(true, half.muted());
			assertEquals(true, half.showSourceAudio());
		}
		assertNotEquals(plan.left().id(), plan.right().id());
	}

	@Test
	@DisplayName("keeps the outgoing transition on the right half only")
	void keepsTransitionOnRightHalf() {
		ClipRegion clip = new ClipRegion("clip-1", 0, 60_000, null, null, null, 1, null, null,
				null, new ClipTransition(ClipTransitionKind.FADE_BLACK, 500));
		ClipSplits.Plan plan = ClipSplits.planClipSplit(List.of(clip), 10_000, createIdFactory());
		assertNotNull(plan);
		assertNull(plan.left().transitionOut());
		assertEquals(new ClipTransition(ClipTransitionKind.FADE_BLACK, 500), plan.right().transitionOut());
	}
}
