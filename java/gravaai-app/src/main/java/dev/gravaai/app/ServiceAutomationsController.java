package dev.gravaai.app;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.result.Result;
import dev.gravaai.platform.portal.Portal;
import dev.gravaai.services.automations.AutomationRunResult;
import dev.gravaai.services.automations.AutomationService;
import dev.gravaai.services.automations.AutomationsFile;
import dev.gravaai.ui.dashboard.AutomationsController;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Settings -> Automations against the real service, with portal-backed file/folder choosers. */
public final class ServiceAutomationsController implements AutomationsController {

	private final AutomationService service;
	private final @Nullable Portal portal;

	/** @param portal the xdg-desktop-portal file chooser, or {@code null} when unavailable */
	public ServiceAutomationsController(AutomationService service, @Nullable Portal portal) {
		this.service = service;
		this.portal = portal;
	}

	@Override
	public AutomationsFile getAutomations() {
		return service.getAutomations();
	}

	@Override
	public Result<AutomationsFile> saveAutomations(JsonNode value) {
		return service.saveAutomations(value);
	}

	@Override
	public Result<AutomationRunResult> runAutomationOnFile(String ruleId, String samplePath,
			@Nullable String notesPath) {
		return service.runAutomationOnFile(ruleId, samplePath, notesPath);
	}

	@Override
	public @Nullable Path chooseFolder(String currentFolder) {
		if (portal == null) {
			return null;
		}
		try {
			Optional<Path> chosen = portal.openFile(Portal.FileDialogRequest.directory("Choose a folder"));
			return chosen.orElse(null);
		} catch (IOException error) {
			return null;
		}
	}

	@Override
	public @Nullable Path chooseSampleFile() {
		if (portal == null) {
			return null;
		}
		try {
			Portal.FileDialogRequest request = new Portal.FileDialogRequest(
					"Choose a file to run the automation on", false, false,
					List.of(new Portal.FileFilter("Recordings",
							List.of("*.mp4", "*.mov", "*.mkv", "*.webm", "*.m4a", "*.wav", "*.mp3", "*.ogg"))),
					null, null);
			return portal.openFile(request).orElse(null);
		} catch (IOException error) {
			return null;
		}
	}
}
