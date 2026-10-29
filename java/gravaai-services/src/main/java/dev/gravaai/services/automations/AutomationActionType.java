package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

/** Port of {@code AutomationActionType}. */
public enum AutomationActionType {
	COPY_PATH("copy-path"),
	REVEAL("reveal"),
	OPEN("open"),
	NOTIFY("notify"),
	COPY_TO_FOLDER("copy-to-folder"),
	MOVE_TO_FOLDER("move-to-folder"),
	RUN_COMMAND("run-command");

	private final String wire;

	AutomationActionType(String wire) {
		this.wire = wire;
	}

	public @Nullable String wire() {
		return wire;
	}

	public static @Nullable AutomationActionType fromWire(String value) {
		if (value == null) {
			return null;
		}
		for (AutomationActionType type : values()) {
			if (type.wire.equals(value)) {
				return type;
			}
		}
		return null;
	}
}
