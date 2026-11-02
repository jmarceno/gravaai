package dev.gravaai.services.shortcuts;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the shortcut sync did, for the settings page (port of {@code GlobalShortcutsStatus}).
 *
 * @param triggers on {@link Backend#PORTAL}, the keys the desktop actually bound for each action,
 *     as it describes them (for example {@code Ctrl+Alt+R}); empty on the other backends
 */
public record GlobalShortcutsStatus(
		Backend backend,
		Map<GlobalShortcutAction, GlobalShortcutBinding> registered,
		Map<GlobalShortcutAction, String> conflicts,
		@Nullable String error,
		Map<GlobalShortcutAction, String> commands,
		Map<GlobalShortcutAction, String> triggers) {

	/** Where the shortcuts live: COSMIC's settings files, the desktop's portal, or plain CLI flags. */
	public enum Backend {
		/** Registered in COSMIC's keyboard shortcut config. */
		COSMIC,
		/**
		 * Registered through the xdg-desktop-portal GlobalShortcuts interface (KDE Plasma, GNOME 48+):
		 * the desktop confirms them with the user once and owns the keys from then on.
		 */
		PORTAL,
		/** Other desktops: only the {@code --action} CLI commands work. */
		CLI_ONLY
	}

	public GlobalShortcutsStatus {
		registered = copy(registered);
		conflicts = copy(conflicts);
		commands = copy(commands);
		triggers = copy(triggers);
	}

	public GlobalShortcutsStatus(
			Backend backend,
			Map<GlobalShortcutAction, GlobalShortcutBinding> registered,
			Map<GlobalShortcutAction, String> conflicts,
			@Nullable String error,
			Map<GlobalShortcutAction, String> commands) {
		this(backend, registered, conflicts, error, commands, Map.of());
	}

	private static <V> Map<GlobalShortcutAction, V> copy(@Nullable Map<GlobalShortcutAction, V> source) {
		Map<GlobalShortcutAction, V> copy = new LinkedHashMap<>();
		if (source != null) {
			copy.putAll(source);
		}
		return Collections.unmodifiableMap(copy);
	}
}
