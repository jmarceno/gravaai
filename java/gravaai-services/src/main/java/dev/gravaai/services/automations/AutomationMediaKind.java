package dev.gravaai.services.automations;

/** The {@code kind} field of an automation context: a screen recording or a meeting. */
public enum AutomationMediaKind {
	SCREEN("screen"),
	MEETING("meeting");

	private final String wire;

	AutomationMediaKind(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	public static AutomationMediaKind fromWire(String value) {
		if (value == null) {
			return null;
		}
		for (AutomationMediaKind kind : values()) {
			if (kind.wire.equals(value)) {
				return kind;
			}
		}
		return null;
	}
}
