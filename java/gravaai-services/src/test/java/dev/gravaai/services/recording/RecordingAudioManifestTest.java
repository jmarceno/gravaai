package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link RecordingAudioManifest}: when a recording's kept capture tracks count. */
class RecordingAudioManifestTest {

	@Test
	@DisplayName("the manifest and its sidecars sit next to the recording under its stem")
	void namesFollowTheRecording() {
		Path recording = Path.of("/rec/recording-17.mp4");
		assertEquals(Path.of("/rec/recording-17.audio-tracks.json"),
				RecordingAudioManifest.pathFor(recording));
		assertEquals(Path.of("/rec/recording-17.mic.wav"),
				RecordingAudioManifest.micSidecar(recording));
		assertEquals(Path.of("/rec/recording-17.system.m4a"),
				RecordingAudioManifest.systemSidecar(recording));
		// The library removes them with the recording.
		assertTrue(RecordingLibrary.belongsToRecording("recording-17.audio-tracks.json",
				"recording-17.mp4"));
		assertTrue(RecordingLibrary.belongsToRecording("recording-17.mic.wav",
				"recording-17.mp4"));
		assertTrue(RecordingLibrary.belongsToRecording("recording-17.system.m4a",
				"recording-17.mp4"));
	}

	@Test
	@DisplayName("a written manifest reads back with its sidecars, system first")
	void roundTrips(@TempDir Path directory) throws Exception {
		Path recording = directory.resolve("recording-1.mp4");
		Files.write(RecordingAudioManifest.micSidecar(recording), new byte[] {1});
		Files.write(RecordingAudioManifest.systemSidecar(recording), new byte[] {1});
		new RecordingAudioManifest(List.of("mic", "system"), "balanced").write(recording);

		RecordingAudioManifest manifest = RecordingAudioManifest.read(recording).orElseThrow();
		assertEquals("balanced", manifest.micNoiseReduction());
		assertEquals(List.of(RecordingAudioManifest.systemSidecar(recording),
				RecordingAudioManifest.micSidecar(recording)), manifest.sidecars(recording));
		assertTrue(manifest.mic(recording).isPresent());
	}

	@Test
	@DisplayName("a manifest whose sidecar is gone does not count: the recording's own audio plays")
	void incompleteSidecarsDoNotCount(@TempDir Path directory) throws Exception {
		Path recording = directory.resolve("recording-2.mp4");
		assertTrue(RecordingAudioManifest.read(recording).isEmpty(), "no manifest at all");

		Files.write(RecordingAudioManifest.micSidecar(recording), new byte[] {1});
		new RecordingAudioManifest(List.of("mic", "system"), "strong").write(recording);
		assertTrue(RecordingAudioManifest.read(recording).isEmpty(),
				"the system capture is missing, so the sidecars no longer cover the recording");

		Files.writeString(RecordingAudioManifest.pathFor(recording), "not json");
		assertTrue(RecordingAudioManifest.read(recording).isEmpty(), "unreadable");
	}
}
