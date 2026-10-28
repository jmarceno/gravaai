package dev.gravaai.services.shortcuts;

/**
 * System-wide recording shortcuts, portable of {@code GLOBAL_SHORTCUT_ACTIONS} in
 * {@code src/lib/globalShortcuts.ts}. The wire values are part of the protocol with
 * {@code gravaai-ctl}, the COSMIC {@code custom} file and the stored settings.
 */
public enum GlobalShortcutAction {
	TOGGLE_RECORDING("toggle-recording"),
	TOGGLE_PAUSE("toggle-pause"),
	CANCEL_RECORDING("cancel-recording"),
	TOGGLE_MEETING("toggle-meeting");

	private final String wire;

	GlobalShortcutAction(String wire) {
		this.wire = wire;
	}

	/** The string used in files, CLI flags and the control socket. */
	public String wire() {
		return wire;
	}

	/** Port of {@code isGlobalShortcutAction}: null for anything that is not an action. */
	public static GlobalShortcutAction fromWire(String value) {
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
