package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.media.export.ExportAudioRouting.FallbackPaths;
import dev.gravaai.media.export.ExportAudioRouting.ResolvedPlan;
import dev.gravaai.media.export.ExportAudioRouting.TrackKind;
import dev.gravaai.media.export.ExportAudioRouting.TrackSetting;

/** Port of {@code audioRoutingEngine.test.ts}, {@code sourceAudioFallback.test.ts} and parts of
 * {@code audioProcessorShared.test}. */
class ExportAudioRoutingTest {

	@Test
	@DisplayName("classifies companion audio paths onto mic/system/mixed tracks")
	void classifiesCompanionPaths() {
		Map<String, TrackKind> cases = Map.ofEntries(
				Map.entry("/recordings/recording-mic.wav", TrackKind.MIC),
				Map.entry("/recordings/recording-system.wav", TrackKind.SYSTEM),
				Map.entry("/recordings/system.mp4", TrackKind.SYSTEM),
				Map.entry("/recordings/mic.wav", TrackKind.MIC),
				Map.entry("mic.wav", TrackKind.MIC),
				Map.entry("system.m4a", TrackKind.SYSTEM),
				Map.entry("/recordings/recording_mic.wav", TrackKind.MIC),
				Map.entry("C:\\Recordings\\recording.SYSTEM.m4a", TrackKind.SYSTEM),
				Map.entry("/recordings/recording_mic_take.wav", TrackKind.MIC));
		for (Map.Entry<String, TrackKind> entry : cases.entrySet()) {
			assertEquals(entry.getValue(), ExportAudioRouting.sourceTrackIdFromPath(entry.getKey()),
					entry.getKey());
		}
	}

	@Test
	@DisplayName("keeps ordinary filenames on the mixed track")
	void keepsOrdinaryFilenamesMixed() {
		for (String filename : List.of("atomic.wav", "dynamic.mp4", "ecosystem.m4a")) {
			assertEquals(TrackKind.MIXED,
					ExportAudioRouting.sourceTrackIdFromPath("/recordings/" + filename), filename);
		}
	}

	@Test
	@DisplayName("treats the video path as embedded audio when present in the fallback list")
	void embeddedFallback() {
		FallbackPaths paths = ExportAudioRouting.resolveSourceAudioFallbackPaths(
				"/tmp/recording.mp4", List.of("/tmp/recording.mp4", "/tmp/recording.mic.wav"));
		assertTrue(paths.hasEmbeddedSourceAudio());
		assertEquals(List.of("/tmp/recording.mic.wav"), paths.externalAudioPaths());
	}

	@Test
	@DisplayName("keeps all fallback paths external when the video has no embedded audio")
	void externalFallback() {
		FallbackPaths paths = ExportAudioRouting.resolveSourceAudioFallbackPaths(
				"/tmp/recording.mp4", List.of("/tmp/recording.system.wav", "/tmp/recording.mic.wav"));
		assertFalse(paths.hasEmbeddedSourceAudio());
		assertEquals(List.of("/tmp/recording.system.wav", "/tmp/recording.mic.wav"),
				paths.externalAudioPaths());
	}

	@Test
	@DisplayName("matches embedded audio from a file URL")
	void fileUrlEmbedded() {
		FallbackPaths paths = ExportAudioRouting.resolveSourceAudioFallbackPaths(
				"file:///tmp/recording.mp4", List.of("/tmp/recording.mp4", "/tmp/recording.mic.wav"));
		assertTrue(paths.hasEmbeddedSourceAudio());
		assertEquals(List.of("/tmp/recording.mic.wav"), paths.externalAudioPaths());
	}

	@Test
	@DisplayName("normalizes Windows file URLs and matches them case-insensitively")
	void windowsFileUrlEmbedded() {
		FallbackPaths paths = ExportAudioRouting.resolveSourceAudioFallbackPaths(
				"file:///C:/Users/Egg/Videos/recording.mp4",
				List.of("c:\\users\\egg\\videos\\recording.mp4",
						"c:\\users\\egg\\videos\\recording.mic.wav"));
		assertTrue(paths.hasEmbeddedSourceAudio());
		assertEquals(List.of("c:\\users\\egg\\videos\\recording.mic.wav"),
				paths.externalAudioPaths());
	}

	@Test
	@DisplayName("routes mic and system companions onto dedicated tracks, system first")
	void dedicatedTracks() {
		ResolvedPlan plan = ExportAudioRouting.buildResolvedPlan("/recordings/recording.mp4",
				List.of("/recordings/recording.mp4", "/recordings/recording-mic.wav",
						"/recordings/system.mp4"),
				List.of(), null, null, null);
		assertEquals(Map.of("mic", "/recordings/recording-mic.wav",
				"system", "/recordings/system.mp4"), plan.pathsByTrack());
		assertEquals(List.of("/recordings/system.mp4", "/recordings/recording-mic.wav"),
				plan.playbackPaths());
		assertTrue(plan.muteEmbeddedPreview());
		assertFalse(plan.includeEmbeddedInExport());
		assertEquals(3, plan.tracks().size());
		assertEquals(TrackKind.SYSTEM, plan.tracks().get(0).kind());
		assertEquals(TrackKind.MIC, plan.tracks().get(1).kind());
		assertEquals(TrackKind.EMBEDDED, plan.tracks().get(2).kind());
	}

	@Test
	@DisplayName("uses the mixed companion when there are no dedicated tracks")
	void mixedFallback() {
		ResolvedPlan plan = ExportAudioRouting.buildResolvedPlan("/recordings/recording.mp4",
				List.of("/recordings/mix.wav", "/recordings/atomic.wav"), List.of(), null, null,
				null);
		assertEquals(Map.of("mixed", "/recordings/mix.wav"), plan.pathsByTrack());
		assertEquals(List.of("/recordings/mix.wav"), plan.playbackPaths());
		assertFalse(plan.muteEmbeddedPreview());
		assertFalse(plan.includeEmbeddedInExport());
		assertEquals(1, plan.tracks().size());
	}

	@Test
	@DisplayName("applies normalize gain and clamps audio region gains")
	void audioRegionGains() {
		ResolvedPlan plan = ExportAudioRouting.buildResolvedPlan("/recordings/recording.mp4",
				List.of(), List.of(
						new AudioRegion("b", 2_000, 4_000, "/tmp/b.wav", 0.5, true, null),
						new AudioRegion("a", 1_000, 2_000, "/tmp/a.wav", 0.9, false, null)),
				null, null, 0.8);
		assertEquals(2, plan.tracks().size());
		assertEquals(0.9, plan.tracks().get(0).gain(), 1e-9);
		assertEquals(0.675, plan.tracks().get(1).gain(), 1e-9);
		assertEquals(0.8, plan.masterGain(), 1e-9);
	}

	@Test
	@DisplayName("keeps embedded audio only when no system or mixed companion exists")
	void embeddedIncludedOnlyWithoutCompanions() {
		ResolvedPlan plan = ExportAudioRouting.buildResolvedPlan("/recordings/recording.mp4",
				List.of("/recordings/recording.mp4"), List.of(), null, null, null);
		assertEquals(1, plan.tracks().size());
		assertEquals(TrackKind.EMBEDDED, plan.tracks().get(0).kind());
		assertEquals(1.0, plan.masterGain(), 1e-9);
		assertEquals(0.675, ExportAudioRouting.resolveSourceTrackGain(
				Map.of("mic", new TrackSetting(0.5, true)), TrackKind.MIC), 1e-9);
	}
}