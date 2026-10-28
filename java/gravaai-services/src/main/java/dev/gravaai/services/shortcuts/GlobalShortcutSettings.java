package dev.gravaai.services.shortcuts;

import tools.jackson.databind.JsonNode;

/**
 * The app-settings slot for {@code globalShortcuts} (port of {@code readAppSetting("globalShortcuts")}
 * / {@code writeAppSetting("globalShortcuts", …)} in {@code electron/globalShortcuts/index.ts}).
 *
 * <p>The app wires this to the core app-settings store; the value is an opaque JSON tree so
 * unknown keys in the settings file survive (plan §5.2).
 */
public interface GlobalShortcutSettings {

	/** The stored value, or null when the setting has never been written. */
	JsonNode read();

	/** Replaces the stored value. */
	void write(JsonNode value);
}
