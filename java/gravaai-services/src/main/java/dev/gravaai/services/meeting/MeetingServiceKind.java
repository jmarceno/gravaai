package dev.gravaai.services.meeting;

import org.jspecify.annotations.Nullable;

/** Where transcription or summarisation runs: {@code "local"} or {@code "api"} (MeetingSettings). */
public enum MeetingServiceKind {
	LOCAL("local"),
	API("api");

	private final String wire;

	MeetingServiceKind(String wire) {
		this.wire = wire;
	}

	public @Nullable String wire() {
		return wire;
	}

	/** The enum for a settings value, or null when it is not one of the two allowed strings. */
	public static @Nullable MeetingServiceKind fromWire(Object value) {
		if (!(value instanceof String text)) {
			return null;
		}
		return switch (text) {
			case "local" -> LOCAL;
			case "api" -> API;
			default -> null;
		};
	}
}
