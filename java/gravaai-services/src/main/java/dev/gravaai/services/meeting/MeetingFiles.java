package dev.gravaai.services.meeting;

import java.nio.file.Path;

/**
 * The three files the meeting pipeline writes next to a recording:
 * {@code <base>.transcript.md}, {@code <base>.notes.md} and {@code <base>.meeting.json}.
 */
public record MeetingFiles(Path transcript, Path notes, Path meta) {

	/** Replaces the recording's last extension with the meeting suffixes. */
	public static MeetingFiles meetingFilesFor(Path mediaPath) {
		String value = mediaPath.toString();
		int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
		int dot = value.lastIndexOf('.');
		String base = dot > slash ? value.substring(0, dot) : value;
		return new MeetingFiles(
				Path.of(base + ".transcript.md"),
				Path.of(base + ".notes.md"),
				Path.of(base + ".meeting.json"));
	}
}
