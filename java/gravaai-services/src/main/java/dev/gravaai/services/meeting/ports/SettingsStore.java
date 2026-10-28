package dev.gravaai.services.meeting.ports;

/**
 * The app-settings JSON store (Electron's appSettingsStore.ts), narrowed to what meetings need.
 * The Lead wires this to {@code dev.gravaai.core.settings.AppSettingsStore} in AppContext, which
 * owns unknown-key preservation and atomic writes (plan §5.2).
 */
public interface SettingsStore {

	/** The value stored under {@code key}, or null when absent. */
	Object get(String key);

	void set(String key, Object value);

	void save();
}
