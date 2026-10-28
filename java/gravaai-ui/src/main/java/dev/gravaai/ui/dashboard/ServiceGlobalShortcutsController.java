package dev.gravaai.ui.dashboard;

import java.util.Objects;

import dev.gravaai.services.shortcuts.GlobalShortcutsConfig;
import dev.gravaai.services.shortcuts.GlobalShortcutsService;
import dev.gravaai.services.shortcuts.GlobalShortcutsStatus;

/** AppContext adapter: exposes {@link GlobalShortcutsService} to the settings page. */
public final class ServiceGlobalShortcutsController implements GlobalShortcutsController {

	private final GlobalShortcutsService service;

	public ServiceGlobalShortcutsController(GlobalShortcutsService service) {
		this.service = Objects.requireNonNull(service, "service");
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
