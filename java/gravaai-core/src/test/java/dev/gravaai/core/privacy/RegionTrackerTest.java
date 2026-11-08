package dev.gravaai.core.privacy;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The flicker and stability contract of {@link RegionTracker}: repeated sightings of the same box
 * collapse into one region, gaps are bridged, separate things stay separate, and the time span
 * always covers the sighting plus its margin.
 */
class RegionTrackerTest {

	private static final double FRAME_WIDTH = 1920;
	private static final double FRAME_HEIGHT = 1080;

	private static DetectedRegion face(double left, double top, double width, double height) {
		return new DetectedRegion(left, top, left + width, top + height,
				DetectedRegion.Source.FACE);
	}

	private static DetectedRegion text(double left, double top, double width, double height) {
		return new DetectedRegion(left, top, left + width, top + height,
				DetectedRegion.Source.TEXT);
	}

	private static RegionTracker tracker() {
		return new RegionTracker(RegionTracker.Options.DEFAULTS, FRAME_WIDTH, FRAME_HEIGHT);
	}

	@Test
	@DisplayName("a stationary face seen five times becomes one region spanning the sightings")
	void stationaryDetectionsCollapse() {
		List<RegionTracker.Sample> samples = new ArrayList<>();
		for (int second = 0; second < 5; second++) {
			// A couple of pixels of detector jitter on each sample.
			samples.add(new RegionTracker.Sample(second * 1000L,
					List.of(face(800 + second % 2, 300 + second % 2, 120, 140))));
		}
		List<RegionTracker.TimedRegion> regions = tracker().track(samples, 5_000);
		assertEquals(1, regions.size(), "jitter must not split one face into several regions");
		RegionTracker.TimedRegion region = regions.getFirst();
		assertEquals(0, region.startMs(), "the margin is clamped at the recording start");
		assertEquals(4_000 + 600, region.endMs(), "the span must cover the last sighting plus"
				+ " the default 600 ms margin");
		double expectedWidth = 120 + 1 + 2 * (0.02 * FRAME_HEIGHT);
		assertTrue(region.region().width() < expectedWidth + 4,
				"the region must stay close to the sightings, not grow every sample: "
						+ region.region());
	}

	@Test
	@DisplayName("two faces far apart stay two regions")
	void separateObjectsStaySeparate() {
		List<RegionTracker.Sample> samples = List.of(
				new RegionTracker.Sample(0, List.of(face(100, 100, 100, 120),
						face(1500, 700, 100, 120))));
		assertEquals(2, tracker().track(samples, 10_000).size());
	}

	@Test
	@DisplayName("a one-sample dropout is bridged, not split into two regions")
	void shortGapIsBridged() {
		List<RegionTracker.Sample> samples = List.of(
				new RegionTracker.Sample(0, List.of(face(800, 300, 120, 140))),
				new RegionTracker.Sample(1000, List.of()),
				new RegionTracker.Sample(2000, List.of(face(802, 302, 120, 140))));
		List<RegionTracker.TimedRegion> regions = tracker().track(samples, 3_000);
		assertEquals(1, regions.size(), "a single missed sample must not split the track");
		assertEquals(0, regions.getFirst().startMs());
		assertEquals(2_600, regions.getFirst().endMs());
	}

	@Test
	@DisplayName("a long absence starts a new region")
	void longGapSplits() {
		List<RegionTracker.Sample> samples = List.of(
				new RegionTracker.Sample(0, List.of(face(800, 300, 120, 140))),
				new RegionTracker.Sample(1000, List.of()),
				new RegionTracker.Sample(2000, List.of()),
				new RegionTracker.Sample(3000, List.of()),
				new RegionTracker.Sample(4000, List.of(face(800, 300, 120, 140))));
		assertEquals(2, tracker().track(samples, 5_000).size(),
				"a four-second absence is a new sighting, not a continuation");
	}

	@Test
	@DisplayName("face and text detections never merge into one region")
	void sourcesDoNotMerge() {
		// The same rectangle from both detectors: a screen showing a face inside a text region.
		List<RegionTracker.Sample> samples = List.of(
				new RegionTracker.Sample(0, List.of(face(400, 200, 200, 200),
						text(400, 200, 200, 200))));
		List<RegionTracker.TimedRegion> regions = tracker().track(samples, 1_000);
		assertEquals(2, regions.size(), "the two detectors are independent toggles");
		assertTrue(regions.stream().anyMatch(r -> r.region().source()
				== DetectedRegion.Source.FACE));
		assertTrue(regions.stream().anyMatch(r -> r.region().source()
				== DetectedRegion.Source.TEXT));
	}

	@Test
	@DisplayName("regions are padded and clamped inside the frame")
	void paddedAndClamped() {
		List<RegionTracker.TimedRegion> regions = tracker().track(List.of(
				new RegionTracker.Sample(0, List.of(face(0, 0, 100, 100)))), 1_000);
		RegionTracker.TimedRegion region = regions.getFirst();
		assertAll(
				() -> assertEquals(0, region.region().left(),
						"a region at the frame edge must not go negative"),
				() -> assertEquals(0, region.region().top()),
				() -> assertTrue(region.region().right() > 100,
						"padding must still grow the inward edges"),
				() -> assertTrue(region.region().right() <= FRAME_WIDTH),
				() -> assertTrue(region.region().bottom() <= FRAME_HEIGHT));
	}

	@Test
	@DisplayName("the region span never runs past the recording")
	void spanClampedToRecording() {
		List<RegionTracker.TimedRegion> regions = tracker().track(List.of(
				new RegionTracker.Sample(9_500, List.of(face(800, 300, 120, 140)))), 10_000);
		assertEquals(9_500 - 600, regions.getFirst().startMs());
		assertEquals(10_000, regions.getFirst().endMs(),
				"the trailing margin must be clamped at the recording end");
	}

	@Test
	@DisplayName("a busy screen is capped, keeping the earliest regions")
	void capsRegionCount() {
		List<RegionTracker.Sample> samples = new ArrayList<>();
		for (int i = 0; i < RegionTracker.MAX_REGIONS + 20; i++) {
			// One sample each, all far apart, so nothing merges.
			samples.add(new RegionTracker.Sample(i * 10_000L,
					List.of(text((i % 10) * 180.0, (i / 10) * 100.0, 100, 60))));
		}
		List<RegionTracker.TimedRegion> regions = tracker().track(samples,
				samples.getLast().timeMs() + 10_000);
		assertEquals(RegionTracker.MAX_REGIONS, regions.size());
		assertTrue(regions.getFirst().startMs() <= regions.getLast().startMs(),
				"regions stay in time order: " + regions);
	}

	@Test
	@DisplayName("empty samples produce no regions")
	void noDetectionsNoRegions() {
		assertTrue(tracker().track(List.of(
				new RegionTracker.Sample(0, List.of()),
				new RegionTracker.Sample(1000, List.of())), 2_000).isEmpty());
		assertTrue(tracker().track(List.of(), 2_000).isEmpty());
	}

}
