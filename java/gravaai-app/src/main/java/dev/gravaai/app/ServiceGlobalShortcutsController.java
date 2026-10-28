package dev.gravaai.app;

import dev.gravaai.services.shortcuts.GlobalShortcutsConfig;
import dev.gravaai.services.shortcuts.GlobalShortcutsService;
import dev.gravaai.services.shortcuts.GlobalShortcutsStatus;
import dev.gravaai.ui.dashboard.GlobalShortcutsController;

/** Settings -> Global shortcuts against the real service (plan §5.17, §5.12). */
public final class ServiceGlobalShortcutsController implements GlobalShortcutsController {

	private final GlobalShortcutsService service;

	public ServiceGlobalShortcutsController(GlobalShortcutsService service) {
		this.service = service;
	}

	@Override
	public GlobalShortcutsConfig readConfig() {
		return service.readConfig();
	}

	@Override
	public GlobalShortcutsStatus status() {
		return service.getStatus();
	}

	@Override
	public GlobalShortcutsStatus apply(GlobalShortcutsConfig config) {
		return service.apply(config);
	}

	@Override
	public boolean cosmicDesktop() {
		return service.isCosmicDesktop();
	}
}
