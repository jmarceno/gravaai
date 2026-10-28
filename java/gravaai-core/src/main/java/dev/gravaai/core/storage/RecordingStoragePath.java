package dev.gravaai.core.storage;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Resolves the storage path of a recorded video (port of
 * {@code electron/ipc/recording/storagePath.ts}).
 *
 * <p>Only app-generated names are accepted — {@code recording-<digits>.webm|mp4} and
 * {@code recording-<digits>-webcam.webm|mp4} — and the result is re-checked to stay inside the
 * recordings directory. The name may come from renderer state, so it is treated as untrusted input.
 */
public final class RecordingStoragePath {

	private static final Pattern RECORDED_VIDEO_FILE_NAME =
			Pattern.compile("^recording-[0-9]+(?:-webcam)?\\.(?:webm|mp4)$");

	private RecordingStoragePath() {}

	/**
	 * @param recordingsDir the recordings directory (absolute paths are required by the caller, like
	 *     {@code path.resolve} in the TS source)
	 * @param fileName the recording file name as an untrusted value ({@code unknown} in TS)
	 * @throws IllegalArgumentException if the value is not a string or not an app-generated name
	 */
	public static Path resolveRecordedVideoStoragePath(Path recordingsDir, Object fileName) {
		if (!(fileName instanceof String name) || !RECORDED_VIDEO_FILE_NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Invalid recording file name");
		}

		Path resolvedRecordingsDir = recordingsDir.toAbsolutePath().normalize();
		Path candidatePath = resolvedRecordingsDir.resolve(name).normalize();
		Path relativePath = resolvedRecordingsDir.relativize(candidatePath);
		String relative = relativePath.toString();

		if (relative.isEmpty()
				|| relative.equals("..")
				|| relative.startsWith(".." + File.separator)
				|| relativePath.isAbsolute()) {
			throw new IllegalArgumentException("Invalid recording file name");
		}

		return candidatePath;
	}
}
