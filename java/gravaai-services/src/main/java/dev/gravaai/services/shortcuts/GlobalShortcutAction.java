package dev.gravaai.services.shortcuts;

import org.jspecify.annotations.Nullable;

/**
 * System-wide recording shortcuts, portable of {@code GLOBAL_SHORTCUT_ACTIONS} in
 * {@code src/lib/globalShortcuts.ts}. The wire values are part of the protocol with
 * {@code gravaai-ctl}, the COSMIC {@code custom} file and the stored settings.
 */
public enum GlobalShortcutAction {
	TOGGLE_RECORDING("toggle-recording"),
	TOGGLE_PAUSE("toggle-pause"),
	CANCEL_RECORDING("cancel-recording"),
	TOGGLE_MEETING("toggle-meeting"),
	/** Enters or leaves draw mode (the live ink overlay) while recording a screen or an area. */
	TOGGLE_DRAW("toggle-draw"),
	/**
	 * Push to talk: while a recording gates its microphone, holding this unmutes it. The press
	 * and release come from the interaction helper's key telemetry; where that is unavailable a
	 * press flips the mic instead.
	 */
	PUSH_TO_TALK("push-to-talk");

	private final String wire;

	GlobalShortcutAction(String wire) {
		this.wire = wire;
	}

	/** The string used in files, CLI flags and the control socket. */
	public String wire() {
		return wire;
	}

	/** Port of {@code isGlobalShortcutAction}: null for anything that is not an action. */
	public static @Nullable GlobalShortcutAction fromWire(@Nullable String value) {
		if (value == null) {
			return null;
		}
		for (GlobalShortcutAction action : values()) {
			if (action.wire.equals(value)) {
				return action;
			}
		}
		return null;
	}

	public static boolean isAction(String value) {
		return fromWire(value) != null;
	}

	/** The {@code --toggle-recording} style flag the app and gravaai-ctl accept. */
	public String cliFlag() {
		return "--" + wire;
	}
}
