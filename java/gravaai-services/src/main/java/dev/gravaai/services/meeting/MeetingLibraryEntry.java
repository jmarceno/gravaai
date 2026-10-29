package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * A meeting is its media file plus the sidecars next to it (.meeting.json, .transcript.md,
 * .notes.md, speaker tracks). There is no separate project file: a meeting with video is edited
 * like any other recording, and the editor reads the same sidecars
 * (electron/ipc/meeting/library.ts).
 */
public record MeetingLibraryEntry(
		Path mediaPath,
		String title,
		long createdAt,
		@Nullable Long durationMs,
		boolean hasVideo,
		boolean hasTranscript,
		boolean hasNotes,
		Status status,
		@Nullable String error) {

	/** Badge state shown in the library. */
	public enum Status {
		IDLE("idle"),
		QUEUED("queued"),
		RUNNING("running"),
		FAILED("failed"),
		INTERRUPTED("interrupted");

		private final String wire;

		Status(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}
}
