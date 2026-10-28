package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.TrimRegion;

/**
 * Ported from {@code src/lib/exporter/videoTimelineSegments.test.ts} plus coverage for the other
 * pure timeline helpers ({@code computeVideoSegments}, {@code splitVideoSegmentsBySpeed}).
 */
class TimelineSegmentsTest {

	private static ClipRegion clip(String id, long startMs, long endMs, Long sourceStartMs, double speed) {
		return new ClipRegion(id, startMs, endMs, sourceStartMs, null, null, speed, null, null, null, null);
	}

	@Test
	@DisplayName("preserves gaps and source in-points without starting another decode pass")
	void preservesGapsAndSourceInPoints() {
		List<List<TimelineSegments.VideoSegment>> runs = TimelineSegments.buildClipDecodeRuns(List.of(
				clip("a", 0, 1000, 0L, 3),
				clip("b", 2000, 4000, 6000L, 3)));
		assertEquals(
				List.of(List.of(
						new TimelineSegments.VideoSegment(0, 3, 3, 0.0, 1.0),
						new TimelineSegments.VideoSegment(6, 12, 3, 2.0, 4.0))),
				runs);
	}

	@Test
	@DisplayName("starts a new source pass for reordered footage")
	void startsNewPassForReorderedFootage() {
		List<List<TimelineSegments.VideoSegment>> runs = TimelineSegments.buildClipDecodeRuns(List.of(
				clip("b", 2000, 3000, 0L, 1),
				clip("a", 0, 1000, 5000L, 1)));
		assertEquals(List.of(5.0, 0.0), runs.stream().map(run -> run.get(0).startSec()).toList());
	}

	@ParameterizedTest(name = "uses a single {0} fps grid across fractional-frame cuts")
	@ValueSource(doubles = {24, 30, 60, 59.94})
	@DisplayName("uses a single fps grid across fractional-frame cuts")
	void usesSingleFpsGrid(double fps) {
		List<ClipRegion> clips = new ArrayList<>();
		for (int index = 0; index < 100; index++) {
			clips.add(clip(Integer.toString(index), index * 137L, (index + 1) * 137L,
					index * 411L, 3));
		}
		List<TimelineSegments.VideoSegment> segments =
				TimelineSegments.buildClipDecodeRuns(clips).get(0);
		long totalFrames = 0;
		for (TimelineSegments.VideoSegment segment : segments) {
			long frames = TimelineSegments.segmentFrameCount(segment, fps);
			totalFrames += frames;
			for (int index = 0; index < frames; index++) {
				double source = TimelineSegments.segmentSourceTime(segment, index, fps);
				assertTrue(source >= segment.startSec(), "source " + source + " before " + segment);
				assertTrue(source < segment.endSec(), "source " + source + " after " + segment);
			}
		}
		assertEquals((long) Math.ceil(13.7 * fps), totalFrames);
	}

	@Test
	@DisplayName("does not route positioned clips through a concatenating native path")
	void requiresClipTimelineRenderingOnlyForPositionedClips() {
		assertFalse(TimelineSegments.requiresClipTimelineRendering(null));
		assertFalse(TimelineSegments.requiresClipTimelineRendering(List.of(
				clip("full", 0, 1000, 0L, 1))));
		assertTrue(TimelineSegments.requiresClipTimelineRendering(List.of()));
		assertTrue(TimelineSegments.requiresClipTimelineRendering(List.of(
				clip("gap", 1000, 2000, null, 1))));
		assertTrue(TimelineSegments.requiresClipTimelineRendering(List.of(
				clip("sped", 0, 1000, 0L, 2))));
	}

	@Test
	@DisplayName("computes the kept ranges after the removed trims")
	void computesKeptRangesAfterTrims() {
		assertEquals(
				List.of(new TimelineSegments.TimeSegment(0, 4)),
				TimelineSegments.computeVideoSegments(4, null));
		assertEquals(
				List.of(
						new TimelineSegments.TimeSegment(0, 2),
						new TimelineSegments.TimeSegment(3, 5)),
				TimelineSegments.computeVideoSegments(5, List.of(
						new TrimRegion("trim", 2000, 3000))));
	}

	@Test
	@DisplayName("splits the kept ranges at the speed regions")
	void splitsKeptRangesAtSpeedRegions() {
		assertEquals(
				List.of(new TimelineSegments.SpedSegment(0, 4, 1)),
				TimelineSegments.splitVideoSegmentsBySpeed(
						List.of(new TimelineSegments.TimeSegment(0, 4)), List.of()));
		assertEquals(
				List.of(
						new TimelineSegments.SpedSegment(0, 1, 1),
						new TimelineSegments.SpedSegment(1, 3, 2),
						new TimelineSegments.SpedSegment(3, 4, 1)),
				TimelineSegments.splitVideoSegmentsBySpeed(
						List.of(new TimelineSegments.TimeSegment(0, 4)),
						List.of(new SpeedRegion("speed", 1000, 3000, 2))));
	}
}
