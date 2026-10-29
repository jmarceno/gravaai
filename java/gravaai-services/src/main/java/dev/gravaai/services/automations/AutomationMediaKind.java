package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

/** The {@code kind} field of an automation context: a screen recording or a meeting. */
public enum AutomationMediaKind {
	SCREEN("screen"),
	MEETING("meeting");

	private final String wire;

	AutomationMediaKind(String wire) {
		this.wire = wire;
	}

	public @Nullable String wire() {
		return wire;
	}

	public static @Nullable AutomationMediaKind fromWire(String value) {
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
