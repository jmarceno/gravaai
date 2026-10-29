package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.automations.AutomationRunResult;
import dev.gravaai.services.automations.AutomationService;
import dev.gravaai.services.automations.AutomationsFile;
import tools.jackson.databind.JsonNode;

/** AppContext adapter: exposes {@link AutomationService} and the two portal choosers to the page. */
public final class ServiceAutomationsController implements AutomationsController {

	private final AutomationService service;
	private final Supplier<@Nullable Path> folderChooser;
	private final Supplier<@Nullable Path> sampleFileChooser;

	public ServiceAutomationsController(AutomationService service,
			@Nullable Supplier<@Nullable Path> folderChooser,
			@Nullable Supplier<@Nullable Path> sampleFileChooser) {
		this.service = Objects.requireNonNull(service, "service");
		this.folderChooser = folderChooser == null ? () -> null : folderChooser;
		this.sampleFileChooser = sampleFileChooser == null ? () -> null : sampleFileChooser;
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
	public Result<AutomationRunResult> runAutomationOnFile(
			String ruleId, String samplePath, @Nullable String notesPath) {
		return service.runAutomationOnFile(ruleId, samplePath, notesPath);
	}

	@Override
	public @Nullable Path chooseFolder(String currentFolder) {
		return folderChooser.get();
	}

	@Override
	public @Nullable Path chooseSampleFile() {
		return sampleFileChooser.get();
	}
}
