package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/** Ported from {@code electron/ipc/project/session.ts} behaviour (the TS file has no test file). */
class RecordingSessionManifestTest {

	@TempDir
	Path dir;

	@Test
	@DisplayName("writes and resolves the version 2 webcam link")
	void writesAndResolvesTheWebcamLink() throws IOException {
		Path video = dir.resolve("recording-1.mp4");
		Path webcam = dir.resolve("recording-1-webcam.mkv");
		Files.writeString(video, "video", StandardCharsets.UTF_8);
		Files.writeString(webcam, "webcam", StandardCharsets.UTF_8);

		RecordingSessionManifest.persistRecordingSessionManifest(
				new RecordingSessionManifest.RecordingSessionData(
						video.toString(), webcam.toString(), 1234L));

		Path manifestPath = RecordingSessionManifest.getRecordingSessionManifestPath(video.toString());
		assertEquals(dir.resolve("recording-1.gravaai-session.json"), manifestPath);
		JsonNode manifest = Json.parseJsonWithByteOrderMark(Files.readString(manifestPath));
		assertEquals(2, manifest.get("version").asInt());
		assertEquals("recording-1.mp4", manifest.get("videoFileName").asString());
		assertEquals("recording-1-webcam.mkv", manifest.get("webcamFileName").asString());
		assertEquals(1234, manifest.get("timeOffsetMs").asInt());

		RecordingSessionManifest.RecordingSessionData resolved =
				RecordingSessionManifest.resolveRecordingSession(video.toString());
		assertEquals(video.toString(), resolved.videoPath());
		assertEquals(webcam.toString(), resolved.webcamPath());
		assertEquals(1234, resolved.timeOffsetMs());
	}

	@Test
	@DisplayName("removes the link when there is no webcam track")
	void removesTheLinkWithoutAWebcamTrack() throws IOException {
		Path video = dir.resolve("recording-1.mp4");
		Files.writeString(video, "video", StandardCharsets.UTF_8);
		RecordingSessionManifest.persistRecordingSessionManifest(
				new RecordingSessionManifest.RecordingSessionData(video.toString(), null, 0));
		Path manifestPath = RecordingSessionManifest.getRecordingSessionManifestPath(video.toString());
		assertFalse(Files.exists(manifestPath));
		assertNull(RecordingSessionManifest.resolveRecordingSession(video.toString()).webcamPath());
	}

	@Test
	@DisplayName("resolves the legacy -webcam sibling when there is no manifest")
	void resolvesTheLegacyWebcamSibling() throws IOException {
		Path video = dir.resolve("recording-2.mp4");
		Path webcam = dir.resolve("recording-2-webcam.webm");
		Files.writeString(video, "video", StandardCharsets.UTF_8);
		Files.writeString(webcam, "webcam", StandardCharsets.UTF_8);

		assertEquals(webcam.toString(), RecordingSessionManifest.resolveLinkedWebcamPath(video.toString()));
		RecordingSessionManifest.RecordingSessionData resolved =
				RecordingSessionManifest.resolveRecordingSession(video.toString());
		assertEquals(webcam.toString(), resolved.webcamPath());
		assertEquals(0, resolved.timeOffsetMs());
		assertNull(RecordingSessionManifest.resolveLinkedWebcamPath(
				dir.resolve("recording-2-webcam.mp4").toString()));
	}

	@Test
	@DisplayName("ignores unknown manifest versions and missing webcam files")
	void ignoresUnknownVersionsAndMissingWebcams() throws IOException {
		Path video = dir.resolve("recording-3.mp4");
		Files.writeString(video, "video", StandardCharsets.UTF_8);
		Path manifestPath = RecordingSessionManifest.getRecordingSessionManifestPath(video.toString());
		Files.writeString(manifestPath, "{\"version\":3,\"webcamFileName\":\"gone.mkv\"}",
				StandardCharsets.UTF_8);
		assertNull(RecordingSessionManifest.resolveRecordingSessionManifest(video.toString()));

		Files.writeString(manifestPath,
				"{\"version\":2,\"webcamFileName\":\"gone.mkv\",\"timeOffsetMs\":250}",
				StandardCharsets.UTF_8);
		RecordingSessionManifest.RecordingSessionData resolved =
				RecordingSessionManifest.resolveRecordingSessionManifest(video.toString());
		assertNull(resolved.webcamPath());
		assertEquals(250, resolved.timeOffsetMs());
		assertTrue(Files.exists(manifestPath));
	}

	@Test
	@DisplayName("keeps the video folder for the manifest path")
	void keepsTheVideoFolderForTheManifestPath() {
		assertEquals(
				Path.of("/tmp/rec/recording-7.gravaai-session.json"),
				RecordingSessionManifest.getRecordingSessionManifestPath("/tmp/rec/recording-7.mkv"));
		assertEquals(
				Path.of("recording-7.gravaai-session.json"),
				RecordingSessionManifest.getRecordingSessionManifestPath("recording-7.mkv"));
	}
}
