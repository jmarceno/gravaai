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
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class ServiceAutomationsController implements AutomationsController {

	private final AutomationService service;
	private final Portal portal;

	@Nullable public ServiceAutomationsController(AutomationService service, Portal portal) {
		this.service = service;
		this.portal = portal;
	}

	@Override
	public @Nullable AutomationsFile getAutomations() {
		return service.getAutomations();
	}

	@Override
	public @Nullable Result<AutomationsFile> saveAutomations(JsonNode value) {
		return service.saveAutomations(value);
	}

	@Override
	public @Nullable Result<AutomationRunResult> runAutomationOnFile(String ruleId, String samplePath, String notesPath) {
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
