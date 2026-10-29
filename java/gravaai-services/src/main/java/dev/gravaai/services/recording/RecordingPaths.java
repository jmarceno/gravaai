package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.storage.StorageFiles;

/**
 * Paths and ids used next to a recording, from {@code getTelemetryPathForVideo},
 * {@code getKeystrokePathForVideo} and {@code parseWindowId} in {@code electron/ipc/utils.ts}.
 */
public final class RecordingPaths {

	private static final Pattern WINDOW_ID = Pattern.compile("^window:(\\d+)");

	private RecordingPaths() {
	}

	/** Cursor telemetry next to the video: {@code <video>.cursor.json}. */
	public static String getTelemetryPathForVideo(String videoPath) {
		return videoPath + StorageFiles.CURSOR_TELEMETRY_SUFFIX;
	}

	/** Key presses next to the video, never anywhere else: {@code <video>.keys.json}. */
	public static @Nullable String getKeystrokePathForVideo(String videoPath) {
		return videoPath + StorageFiles.KEYSTROKE_TELEMETRY_SUFFIX;
	}

	/** The window id of a {@code window:<id>:<n>} source id, or null. */
	public static @Nullable Integer parseWindowId(String sourceId) {
		if (sourceId == null) {
			return null;
		}
		Matcher match = WINDOW_ID.matcher(sourceId);
		return match.find() ? Integer.valueOf(match.group(1)) : null;
	}
}
