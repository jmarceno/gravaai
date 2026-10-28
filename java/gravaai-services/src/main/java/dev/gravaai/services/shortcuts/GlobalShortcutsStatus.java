package dev.gravaai.services.shortcuts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the shortcut sync did, for the settings page (port of {@code GlobalShortcutsStatus}).
 */
public record GlobalShortcutsStatus(
		Backend backend,
		Map<GlobalShortcutAction, GlobalShortcutBinding> registered,
		Map<GlobalShortcutAction, String> conflicts,
		String error,
		Map<GlobalShortcutAction, String> commands) {

	/** Where the shortcuts live: COSMIC's settings files, or plain CLI flags anywhere else. */
	public enum Backend {
		/** Registered in COSMIC's keyboard shortcut config. */
		COSMIC,
		/** Other desktops: only the {@code --action} CLI commands work. */
		CLI_ONLY
	}

	public GlobalShortcutsStatus {
		Map<GlobalShortcutAction, GlobalShortcutBinding> registeredCopy = new LinkedHashMap<>();
		if (registered != null) {
			registeredCopy.putAll(registered);
		}
		registered = Collections.unmodifiableMap(registeredCopy);
		Map<GlobalShortcutAction, String> conflictsCopy = new LinkedHashMap<>();
		if (conflicts != null) {
			conflictsCopy.putAll(conflicts);
		}
		conflicts = Collections.unmodifiableMap(conflictsCopy);
		Map<GlobalShortcutAction, String> commandsCopy = new LinkedHashMap<>();
		if (commands != null) {
			commandsCopy.putAll(commands);
		}
		commands = Collections.unmodifiableMap(commandsCopy);
	}
}
