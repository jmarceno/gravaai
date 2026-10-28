package dev.gravaai.ui.dashboard;

import dev.gravaai.services.shortcuts.GlobalShortcuts;
import dev.gravaai.services.shortcuts.GlobalShortcutsConfig;
import dev.gravaai.services.shortcuts.GlobalShortcutsStatus;

/**
 * Thin port between the settings page and the global-shortcuts service (Lead decision: the app
 * injects {@code GlobalShortcutsService} through {@link ServiceGlobalShortcutsController} when the
 * AppContext builds it, and the page stays testable without COSMIC or the real settings store).
 */
public interface GlobalShortcutsController {

	/** The stored config, normalised (port of {@code readGlobalShortcutsConfig}). */
	GlobalShortcutsConfig readConfig();

	/** The registration status (port of {@code getGlobalShortcutsStatus}). */
	GlobalShortcutsStatus status();

	/** Saves and applies a config (port of {@code applyGlobalShortcuts}). */
	GlobalShortcutsStatus apply(GlobalShortcutsConfig config);

	/** Whether shortcuts can be registered in the desktop (COSMIC) or only shown as CLI commands. */
	boolean cosmicDesktop();

	/** Port of {@code resetGlobalShortcuts}: apply the defaults and persist them. */
	default GlobalShortcutsStatus reset() {
		return apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);
	}
}
