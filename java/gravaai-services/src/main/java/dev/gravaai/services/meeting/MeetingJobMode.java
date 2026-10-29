package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** Which pipeline stages a meeting job runs (electron/ipc/register/meeting.ts JOB_MODES). */
public enum MeetingJobMode {
	FULL("full"),
	TRANSCRIBE("transcribe"),
	SUMMARIZE("summarize");

	public static final List<MeetingJobMode> JOB_MODES = List.of(FULL, TRANSCRIBE, SUMMARIZE);

	private final String wire;

	MeetingJobMode(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	/**
	 * Null when the value is not a supported mode (the IPC handler answers 400 then).
	 *
	 * @param value the raw value, or null when the setting is absent
	 */
	public static @Nullable MeetingJobMode fromWire(@Nullable Object value) {
		if (!(value instanceof String text)) {
			return null;
		}
		for (MeetingJobMode mode : values()) {
			if (mode.wire.equals(text)) {
				return mode;
			}
		}
		return null;
	}
}
