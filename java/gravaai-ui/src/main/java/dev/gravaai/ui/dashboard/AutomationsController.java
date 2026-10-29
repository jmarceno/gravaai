package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.automations.AutomationRunResult;
import dev.gravaai.services.automations.AutomationsFile;
import tools.jackson.databind.JsonNode;

/**
 * Thin port between the Automations settings page and the automation service (Lead decision:
 * AppContext injects the real {@code AutomationService}, the file/folder choosers and this page
 * stays testable without the engine).
 *
 * <p>The choosers belong to the UI/platform layer (XDG portal), so they are callbacks: AppContext
 * passes suppliers when it builds {@link ServiceAutomationsController}. A {@code null} result means
 * the user cancelled.
 */
public interface AutomationsController {

	/** Port of the {@code get-automations} handler: the stored rules. */
	AutomationsFile getAutomations();

	/** Port of the {@code save-automations} handler. */
	Result<AutomationsFile> saveAutomations(JsonNode value);

	/**
	 * Port of the {@code run-automation-on-file} handler: run one rule against a chosen file.
	 *
	 * @param notesPath optional notes sidecar for summary actions, or null for none
	 */
	Result<AutomationRunResult> runAutomationOnFile(
			String ruleId, String samplePath, @Nullable String notesPath);

	/**
	 * Folder chooser for copy/move actions; {@code currentFolder} is the starting value.
	 *
	 * @return the chosen folder, or null when the user cancels
	 */
	@Nullable Path chooseFolder(String currentFolder);

	/** File chooser for "Run now on a file..."; null when the user cancels. */
	@Nullable Path chooseSampleFile();
}
