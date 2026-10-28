package dev.gravaai.services.meeting;

import java.nio.file.Path;

/**
 * State of one meeting job, mirrored to the UI through {@code meeting-job-update}
 * (electron/ipc/meeting/jobs.ts).
 */
public record MeetingJob(
		String id,
		Path mediaPath,
		MeetingJobMode mode,
		Status status,
		Stage stage,
		String message,
		Integer progress,
		String error,
		String createdAt,
		String finishedAt) {

	/** Job lifecycle; {@code wire()} is the string the renderer sees. */
	public enum Status {
		QUEUED("queued"),
		RUNNING("running"),
		DONE("done"),
		FAILED("failed"),
		CANCELLED("cancelled");

		private final String wire;

		Status(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	/** The pipeline stage a status update belongs to. */
	public enum Stage {
		TRANSCRIBE("transcribe"),
		SUMMARIZE("summarize"),
		TITLE("title");

		private final String wire;

		Stage(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	public boolean isActive() {
		return status == Status.QUEUED || status == Status.RUNNING;
	}
}
