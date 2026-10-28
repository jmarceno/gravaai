package dev.gravaai.services.projects;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One file of the recordings library (port of {@code RecordingLibraryEntry} in
 * {@code src/types/recordingLibrary.ts}, minus the media-server URL: the Java app decodes files
 * directly, plan §2.2).
 *
 * @param path the absolute file path
 * @param name the file name including its extension
 * @param createdAt the file mtime in milliseconds (the TS {@code createdAt} comes from {@code mtimeMs})
 * @param bytes the file size; empty files are never listed
 */
public record RecordingEntry(String path, String name, long createdAt, long bytes) {

	private static final Pattern AUDIO = Pattern.compile("\\.(wav|m4a|mp3|ogg|flac)$", Pattern.CASE_INSENSITIVE);

	/** {@code isAudio} in {@code RawRecordings.tsx}: audio files get a music icon, not a video frame. */
	public boolean isAudio() {
		return AUDIO.matcher(name).find();
	}

	/** Lower-cased extension without the dot, for UI helpers. */
	public String extension() {
		int lastDot = name.lastIndexOf('.');
		return lastDot > 0 ? name.substring(lastDot + 1).toLowerCase(Locale.ROOT) : "";
	}
}
