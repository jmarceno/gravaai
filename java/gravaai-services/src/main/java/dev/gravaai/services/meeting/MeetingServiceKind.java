package dev.gravaai.services.meeting;

/** Where transcription or summarisation runs: {@code "local"} or {@code "api"} (MeetingSettings). */
public enum MeetingServiceKind {
	LOCAL("local"),
	API("api");

	private final String wire;

	MeetingServiceKind(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	/** The enum for a settings value, or null when it is not one of the two allowed strings. */
	public static MeetingServiceKind fromWire(Object value) {
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
