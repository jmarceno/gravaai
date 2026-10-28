package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import dev.gravaai.core.project.SourceAudioTrackSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code timeline/sourceAudioTracks.test.ts} with the same cases and values. */
class TimelineSourceAudioTracksTest {

	private static final TimelineSourceAudioTracks.Labels LABELS = new TimelineSourceAudioTracks.Labels(
			"Source System", "Source Mic", "Source");

	@Test
	@DisplayName("builds candidates for Windows and macOS sidecar containers")
	void buildsSidecarCandidates() {
		assertEquals(List.of("C:/GravaAI/recording-1.mic.wav", "C:/GravaAI/recording-1.mic.m4a",
				"C:/GravaAI/recording-1.mic.webm"),
				TimelineSourceAudioTracks.buildSidecarPathCandidates(
						"C:\\GravaAI\\recording-1.mp4", "mic"));
		assertEquals(List.of("/Users/me/recording-1.system.wav", "/Users/me/recording-1.system.m4a",
				"/Users/me/recording-1.system.webm"),
				TimelineSourceAudioTracks.buildSidecarPathCandidates(
						"/Users/me/recording-1.mp4", "system"));
	}

	@Test
	@DisplayName("keeps embedded system audio controllable when mic is a sidecar")
	void keepsEmbeddedSystemWhenMicIsSidecar() {
		List<TimelineSourceAudioTrack> tracks = TimelineSourceAudioTracks.buildTracks(true,
				"/v.mp4", true, "/v.mic.wav", false, null, LABELS);

		assertEquals(2, tracks.size());
		assertEquals(SourceAudioTrackSettings.SYSTEM, tracks.get(0).id());
		assertEquals("/v.mp4", tracks.get(0).audioPath());
		assertEquals("Source System", tracks.get(0).label());
		assertEquals(SourceAudioTrackSettings.MIC, tracks.get(1).id());
		assertEquals("/v.mic.wav", tracks.get(1).audioPath());
	}

	@Test
	@DisplayName("does not invent a system track when only the mic sidecar exists")
	void doesNotInventSystemTrack() {
		List<TimelineSourceAudioTrack> tracks = TimelineSourceAudioTracks.buildTracks(false, "/v.mp4",
				true, "/v.mic.wav", false, null, LABELS);

		assertEquals(1, tracks.size());
		assertEquals(SourceAudioTrackSettings.MIC, tracks.get(0).id());
	}

	@Test
	@DisplayName("uses dedicated sidecars over the embedded track when both source tracks exist")
	void prefersDedicatedSidecars() {
		List<TimelineSourceAudioTrack> tracks = TimelineSourceAudioTracks.buildTracks(true,
				"/v.mp4", true, "/v.mic.wav", true, "/v.system.m4a", LABELS);

		assertEquals(2, tracks.size());
		assertEquals(SourceAudioTrackSettings.SYSTEM, tracks.get(0).id());
		assertEquals("/v.system.m4a", tracks.get(0).audioPath());
		assertEquals(SourceAudioTrackSettings.MIC, tracks.get(1).id());
		assertEquals("/v.mic.wav", tracks.get(1).audioPath());
	}

	@Test
	@DisplayName("falls back to one mixed source track when no dedicated sidecar exists")
	void fallsBackToMixedTrack() {
		List<TimelineSourceAudioTrack> tracks = TimelineSourceAudioTracks.buildTracks(true,
				"/v.mp4", false, null, false, null, LABELS);

		assertEquals(1, tracks.size());
		assertEquals(SourceAudioTrackSettings.MIXED, tracks.get(0).id());
		assertEquals("Source", tracks.get(0).label());
		assertEquals("/v.mp4", tracks.get(0).audioPath());
	}

	@Test
	@DisplayName("returns no tracks without embedded audio or sidecars")
	void returnsNoTracks() {
		assertTrue(TimelineSourceAudioTracks.buildTracks(false, null, false, null, false, null,
				LABELS).isEmpty());
		assertTrue(TimelineSourceAudioTracks.buildSidecarPathCandidates(null, "mic").isEmpty());
	}
}
