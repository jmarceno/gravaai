package dev.gravaai.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of the constants and helpers in {@code electron/ipc/constants.ts} and project manager. */
class StorageFilesTest {

	@Test
	@DisplayName("keeps the Electron file names and limits")
	void keepsElectronFileNames() {
		assertEquals("gravaai", StorageFiles.PROJECT_FILE_EXTENSION);
		assertEquals(java.util.List.of("openscreen"), StorageFiles.LEGACY_PROJECT_FILE_EXTENSIONS);
		assertEquals("Projects", StorageFiles.PROJECTS_DIRECTORY_NAME);
		assertEquals(".preview.png", StorageFiles.PROJECT_THUMBNAIL_SUFFIX);
		assertEquals("recent-projects.json", StorageFiles.RECENT_PROJECTS_FILE_NAME);
		assertEquals(16, StorageFiles.MAX_RECENT_PROJECTS);
		assertEquals("shortcuts.json", StorageFiles.SHORTCUTS_FILE_NAME);
		assertEquals("recordings-settings.json", StorageFiles.RECORDINGS_SETTINGS_FILE_NAME);
		assertEquals("countdown-settings.json", StorageFiles.COUNTDOWN_SETTINGS_FILE_NAME);
		assertEquals("app-settings.json", StorageFiles.APP_SETTINGS_FILE_NAME);
		assertEquals("automations.json", StorageFiles.AUTOMATIONS_FILE_NAME);
		assertEquals("recording-", StorageFiles.AUTO_RECORDING_PREFIX);
		assertEquals("recordings", StorageFiles.RECORDINGS_DIRECTORY_NAME);
		assertEquals("extensions", StorageFiles.EXTENSIONS_DIRECTORY_NAME);
		assertEquals(".gravaai-session.json", StorageFiles.RECORDING_SESSION_MANIFEST_SUFFIX);
		assertEquals("agent.sock", StorageFiles.AGENT_SOCKET_FILE_NAME);
		assertEquals("agent-token", StorageFiles.AGENT_TOKEN_FILE_NAME);
		assertEquals("whisper", StorageFiles.WHISPER_DIRECTORY_NAME);
		assertEquals("ggml-small.bin", StorageFiles.WHISPER_SMALL_MODEL_FILE_NAME);
		assertEquals("aiControlEnabled", StorageFiles.AI_CONTROL_SETTING);
		assertEquals(2, StorageFiles.CURSOR_TELEMETRY_VERSION);
		assertEquals(33, StorageFiles.CURSOR_SAMPLE_INTERVAL_MS);
		assertEquals(60 * 60 * 30, StorageFiles.MAX_CURSOR_SAMPLES);
		assertEquals(
				"https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
				StorageFiles.WHISPER_MODEL_DOWNLOAD_URL);
	}

	@Test
	@DisplayName("puts Projects inside the recordings directory")
	void projectsInsideRecordingsDirectory() {
		Path recordings = Path.of("/home/user/.config/Gravaai/recordings");
		assertEquals(recordings.resolve("Projects"), StorageFiles.projectsDir(recordings));
	}

	@ParameterizedTest(name = "accepts a project file path: {0}")
	@ValueSource(strings = {
			"demo.gravaai",
			"Demo.GRAVAAI",
			"a.gravaai",
			"/home/user/Projects/My Project.gravaai",
			"legacy.openscreen",
	})
	@DisplayName("accepts project file extensions")
	void acceptsProjectExtensions(String filePath) {
		assertTrue(StorageFiles.hasProjectFileExtension(filePath));
	}

	@ParameterizedTest(name = "rejects a non-project file path: {0}")
	@ValueSource(strings = {
			"demo.webm",
			"demo",
			"demo.gravaai.bak",
			"demo.txt",
			".gravaai",
			"",
	})
	@DisplayName("rejects non-project file extensions")
	void rejectsNonProjectExtensions(String filePath) {
		assertFalse(StorageFiles.hasProjectFileExtension(filePath));
	}

	@Test
	@DisplayName("derives sibling paths the way the Electron helpers do")
	void derivesSiblingPaths() {
		assertEquals(Path.of("/tmp/demo.gravaai.preview.png"),
				StorageFiles.projectThumbnailPath("/tmp/demo.gravaai"));
		assertEquals(Path.of("/tmp/demo.webm.gravaai-session.json"),
				StorageFiles.recordingSessionManifestPath("/tmp/demo.webm"));
		assertEquals(Path.of("/tmp/demo.webm.cursor.json"),
				StorageFiles.cursorTelemetryPath("/tmp/demo.webm"));
		assertEquals(Path.of("/tmp/demo.webm.keys.json"),
				StorageFiles.keystrokeTelemetryPath("/tmp/demo.webm"));
	}

	@Test
	@DisplayName("detects app-generated recording names")
	void detectsAutoRecordingNames() {
		assertTrue(StorageFiles.isAutoRecordingPath(Path.of("/tmp/recording-1.webm")));
		assertTrue(StorageFiles.isAutoRecordingPath(Path.of("recording-1720588800000-webcam.mkv")));
		assertFalse(StorageFiles.isAutoRecordingPath(Path.of("/tmp/imported.webm")));
		assertFalse(StorageFiles.isAutoRecordingPath(Path.of("/tmp/my-recording-1.webm")));
	}
}
