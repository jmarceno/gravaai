package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.TrimRegion;
import dev.gravaai.media.export.ExportAudioSegments.Segment;
import dev.gravaai.media.export.ExportAudioSegments.Speed;
import dev.gravaai.media.export.ExportAudioSegments.Strategy;
import dev.gravaai.media.export.ExportAudioSegments.StrategyInput;
import dev.gravaai.media.export.ExportAudioSegments.Trim;

/** Port of {@code src/lib/exporter/editedTrackStrategy.test.ts}. */
class ExportAudioSegmentsTest {

	private static final double SOURCE_DURATION_MS = 20_000;
	private static final List<Trim> EMPTY_TRIMS = List.of();

	private static Strategy classify(String primary, double duration, List<Trim> trims,
			List<Speed> speeds, List<AudioRegion> regions, List<String> fallbacks) {
		return ExportAudioSegments.classifyEditedTrackStrategy(new StrategyInput(primary, duration,
				trims, speeds, regions, fallbacks));
	}

	@Test
	@DisplayName("marks single-source speed-only edits as filtergraph candidates")
	void speedOnlyFastPath() {
		assertEquals(Strategy.FILTERGRAPH_FAST_PATH, classify("recording.mp4", SOURCE_DURATION_MS,
				EMPTY_TRIMS, List.of(new Speed(2_000, 10_000, 1.5)), List.of(), List.of()));
	}

	@Test
	@DisplayName("marks a single external source audio track as a filtergraph candidate")
	void singleExternalFastPath() {
		assertEquals(Strategy.FILTERGRAPH_FAST_PATH, classify("mic.m4a", SOURCE_DURATION_MS,
				EMPTY_TRIMS, List.of(new Speed(2_000, 10_000, 1.25)), List.of(), List.of("mic.m4a")));
	}

	@Test
	@DisplayName("falls back for multi-source mixes")
	void multiSourceFallback() {
		assertEquals(Strategy.OFFLINE_RENDER_FALLBACK, classify("recording.mp4", SOURCE_DURATION_MS,
				EMPTY_TRIMS, List.of(new Speed(2_000, 10_000, 1.5)), List.of(), List.of("mic.m4a")));
	}

	@Test
	@DisplayName("falls back when audio regions require overlay mixing")
	void audioRegionFallback() {
		List<AudioRegion> regions = List.of(new AudioRegion("audio-1", 1_000, 4_000, "overlay.wav",
				0.8, false, null));
		assertEquals(Strategy.OFFLINE_RENDER_FALLBACK, classify("recording.mp4", SOURCE_DURATION_MS,
				EMPTY_TRIMS, List.of(new Speed(2_000, 10_000, 1.5)), regions, List.of()));
	}

	@Test
	@DisplayName("falls back for speeds outside the conservative filtergraph window")
	void unsafeSpeedFallback() {
		assertEquals(Strategy.OFFLINE_RENDER_FALLBACK, classify("recording.mp4", SOURCE_DURATION_MS,
				EMPTY_TRIMS, List.of(new Speed(2_000, 10_000, 2.5)), List.of(), List.of()));
	}

	@Test
	@DisplayName("falls back when source duration or speed-region bounds are invalid")
	void invalidBoundsFallback() {
		assertEquals(Strategy.OFFLINE_RENDER_FALLBACK, classify("recording.mp4",
				Double.POSITIVE_INFINITY, EMPTY_TRIMS, List.of(new Speed(Double.NaN, 10_000, 1.5)),
				List.of(), List.of()));
		assertEquals(Strategy.OFFLINE_RENDER_FALLBACK, classify("recording.mp4", SOURCE_DURATION_MS,
				EMPTY_TRIMS, List.of(), List.of(), List.of()));
	}

	@Test
	@DisplayName("builds source segments that preserve trims and speed boundaries")
	void buildsSegments() {
		List<Trim> trims = List.of(new Trim(10_000, 12_000));
		List<Speed> speeds = List.of(new Speed(2_000, 6_000, 1.5),
				new Speed(14_000, 18_000, 0.75));
		assertEquals(List.of(new Segment(0, 2_000, 1), new Segment(2_000, 6_000, 1.5),
				new Segment(6_000, 10_000, 1), new Segment(12_000, 14_000, 1),
				new Segment(14_000, 18_000, 0.75), new Segment(18_000, 20_000, 1)),
				ExportAudioSegments.buildEditedTrackSourceSegments(SOURCE_DURATION_MS, trims,
						speeds));
	}

	@Test
	@DisplayName("returns no source segments when speed-region bounds are invalid")
	void invalidSpeedSegments() {
		assertEquals(List.of(), ExportAudioSegments.buildEditedTrackSourceSegments(
				SOURCE_DURATION_MS, EMPTY_TRIMS, List.of(new Speed(15_000, 10_000, 1.5))));
	}

	@Test
	@DisplayName("trim boundaries are accepted through the core TrimRegion adapter")
	void trimRegionAdapter() {
		assertEquals(List.of(new Trim(0, 5_000)), List.of(Trim.of(new TrimRegion("t", 0, 5_000))));
		assertEquals(new Speed(1_000, 2_000, 1.5),
				Speed.of(new SpeedRegion("s", 1_000, 2_000, 1.5)));
	}
}
