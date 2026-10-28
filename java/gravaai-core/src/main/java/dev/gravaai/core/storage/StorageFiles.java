package dev.gravaai.core.storage;

import java.nio.file.Path;
import java.util.List;

/**
 * File and directory names inside the user data directory (port of
 * {@code electron/ipc/constants.ts}, plan §5.2).
 *
 * <p>These names are part of the data-compatibility contract with the Electron app: a user
 * switching to the Java app must find and keep every file listed here.
 */
public final class StorageFiles {

	public static final String PROJECT_FILE_EXTENSION = "gravaai";
	public static final List<String> LEGACY_PROJECT_FILE_EXTENSIONS = List.of("openscreen");
	public static final String PROJECTS_DIRECTORY_NAME = "Projects";
	public static final String PROJECT_THUMBNAIL_SUFFIX = ".preview.png";

	public static final String RECENT_PROJECTS_FILE_NAME = "recent-projects.json";
	public static final int MAX_RECENT_PROJECTS = 16;

	public static final String SHORTCUTS_FILE_NAME = "shortcuts.json";
	public static final String RECORDINGS_SETTINGS_FILE_NAME = "recordings-settings.json";
	public static final String COUNTDOWN_SETTINGS_FILE_NAME = "countdown-settings.json";
	public static final String APP_SETTINGS_FILE_NAME = "app-settings.json";
	public static final String AUTOMATIONS_FILE_NAME = "automations.json";

	public static final String AUTO_RECORDING_PREFIX = "recording-";
	public static final String RECORDINGS_DIRECTORY_NAME = "recordings";
	public static final String EXTENSIONS_DIRECTORY_NAME = "extensions";
	public static final String RECORDING_SESSION_MANIFEST_SUFFIX = ".gravaai-session.json";

	public static final String WHISPER_MODEL_DOWNLOAD_URL =
			"https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin";
	public static final String WHISPER_DIRECTORY_NAME = "whisper";
	public static final String WHISPER_SMALL_MODEL_FILE_NAME = "ggml-small.bin";

	/** AI control: a local-only socket plus a per-install token (see electron/agent). */
	public static final String AI_CONTROL_SETTING = "aiControlEnabled";
	public static final String AGENT_SOCKET_FILE_NAME = "agent.sock";
	public static final String AGENT_TOKEN_FILE_NAME = "agent-token";

	/** Cursor/keystroke telemetry files written next to a recording. */
	public static final String CURSOR_TELEMETRY_SUFFIX = ".cursor.json";
	public static final String KEYSTROKE_TELEMETRY_SUFFIX = ".keys.json";
	public static final int CURSOR_TELEMETRY_VERSION = 2;
	public static final int CURSOR_SAMPLE_INTERVAL_MS = 33;
	public static final int MAX_CURSOR_SAMPLES = 60 * 60 * 30; // 1 hour @ 30Hz

	private StorageFiles() {}

	/** The projects folder for a recordings directory (port of getProjectsDir). */
	public static Path projectsDir(Path recordingsDir) {
		return recordingsDir.resolve(PROJECTS_DIRECTORY_NAME);
	}

	/** Port of hasProjectFileExtension in electron/ipc/project/manager.ts. */
	public static boolean hasProjectFileExtension(String filePath) {
		String extension = extname(filePath).replaceFirst("^\\.", "").toLowerCase(java.util.Locale.ROOT);
		return extension.equals(PROJECT_FILE_EXTENSION) || LEGACY_PROJECT_FILE_EXTENSIONS.contains(extension);
	}

	public static boolean hasProjectFileExtension(Path filePath) {
		return hasProjectFileExtension(filePath.toString());
	}

	/** Port of getProjectThumbnailPath: the thumbnail lives next to the project file. */
	public static Path projectThumbnailPath(String projectPath) {
		return Path.of(projectPath + PROJECT_THUMBNAIL_SUFFIX);
	}

	public static Path projectThumbnailPath(Path projectPath) {
		return projectThumbnailPath(projectPath.toString());
	}

	/** The crash-recovery manifest of a recording: {@code <video>.gravaai-session.json}. */
	public static Path recordingSessionManifestPath(String videoPath) {
		return Path.of(videoPath + RECORDING_SESSION_MANIFEST_SUFFIX);
	}

	public static Path recordingSessionManifestPath(Path videoPath) {
		return recordingSessionManifestPath(videoPath.toString());
	}

	/** Port of getTelemetryPathForVideo in electron/ipc/utils.ts. */
	public static Path cursorTelemetryPath(String videoPath) {
		return Path.of(videoPath + CURSOR_TELEMETRY_SUFFIX);
	}

	/** Port of getKeystrokePathForVideo in electron/ipc/utils.ts. */
	public static Path keystrokeTelemetryPath(String videoPath) {
		return Path.of(videoPath + KEYSTROKE_TELEMETRY_SUFFIX);
	}

	/** Port of isAutoRecordingPath: recordings the app made, not files the user imported. */
	public static boolean isAutoRecordingPath(Path filePath) {
		return filePath.getFileName().toString().startsWith(AUTO_RECORDING_PREFIX);
	}

	/** Node's {@code path.extname}: the last dot in the last path segment, ignored at position 0. */
	private static String extname(String filePath) {
		int lastSlash = filePath.lastIndexOf('/');
		String basename = lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
		int lastDot = basename.lastIndexOf('.');
		return lastDot <= 0 ? "" : basename.substring(lastDot);
	}
}
