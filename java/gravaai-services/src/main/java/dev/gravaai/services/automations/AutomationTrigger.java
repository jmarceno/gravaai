package dev.gravaai.services.automations;

import org.jspecify.annotations.Nullable;

/** Port of {@code AutomationTrigger} in {@code src/types/automations.ts}. */
public enum AutomationTrigger {
	RECORDING_SAVED("recording-saved"),
	MEETING_SAVED("meeting-saved"),
	MEETING_NOTES_READY("meeting-notes-ready"),
	EXPORT_FINISHED("export-finished");

	private final String wire;

	AutomationTrigger(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	public static @Nullable AutomationTrigger fromWire(@Nullable String value) {
		if (value == null) {
			return null;
		}
		for (AutomationTrigger trigger : values()) {
			if (trigger.wire.equals(value)) {
				return trigger;
			}
		}
		return null;
	}

	public static boolean isTrigger(String value) {
		return fromWire(value) != null;
	}
}
