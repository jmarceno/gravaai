package dev.gravaai.services.automations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.gravaai.core.result.Result;
import tools.jackson.databind.JsonNode;

/**
 * The automations facade the rest of the app calls (plan §5.15): rule storage for the settings
 * page, the "Run now on a file…" action, and the fire-and-forget trigger hooks the recording,
 * meeting and export services use.
 *
 * <p>Wiring replaces the Electron IPC handlers in {@code electron/ipc/register/automations.ts};
 * the folder/file choosers themselves belong to the UI/platform layer, so callers pass the chosen
 * path in.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class AutomationService implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.automations");

	private final AutomationStore store;
	private final AutomationHost host;
	private final Path homedir;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final AutomationEngine.Deps deps;
	private final AutomationTriggers triggers;

	public AutomationService(
			AutomationStore store, AutomationHost host, AutomationDurationProbe probe, Path homedir) {
		this.store = store;
		this.host = host;
		this.homedir = homedir;
		this.deps = new AutomationEngine.Deps(
				() -> store.read().rules(),
				(action, context) -> AutomationActions.runAutomationAction(action, context, host, homedir),
				probe,
				(title, body) -> AutomationActions.showAutomationFailureNotification(title, body, host, log()),
				this::log,
				Instant::now);
		this.triggers = new AutomationTriggers(this::dispatchAsync);
	}

	public AutomationService(
			Path automationsFile, AutomationHost host, AutomationDurationProbe probe, Path homedir) {
		this(new AutomationStore(automationsFile), host, probe, homedir);
	}

	public AutomationStore store() {
		return store;
	}

	public AutomationEngine.Deps deps() {
		return deps;
	}

	public AutomationTriggers triggers() {
		return triggers;
	}

	/** Port of the {@code get-automations} handler: the stored rules (invalid files read empty). */
	public AutomationsFile getAutomations() {
		return store.read();
	}

	/**
	 * Port of the {@code save-automations} handler: refuses rules the engine would silently ignore,
	 * normalises and writes the file.
	 */
	public Result<AutomationsFile> saveAutomations(JsonNode value) {
		String disallowed = Automations.findDisallowedAction(value);
		if (disallowed != null) {
			return Result.err(disallowed);
		}
		try {
			return Result.ok(store.write(value));
		} catch (Exception error) {
			return Result.err(String.valueOf(error));
		}
	}

	/**
	 * Port of the non-dialog part of the {@code run-automation-on-file} handler: checks the rule
	 * and the file, then runs the rule once regardless of {@code enabled}.
	 */
	public Result<AutomationRunResult> runAutomationOnFile(String ruleId, String samplePath) {
		return runAutomationOnFile(ruleId, samplePath, null);
	}

	/**
	 * @param notesPath the meeting notes sidecar, when the caller knows it (the meeting pipeline
	 *     resolves it for {@code meeting-notes-ready} rules)
	 */
	public Result<AutomationRunResult> runAutomationOnFile(String ruleId, String samplePath, String notesPath) {
		if (ruleId == null || ruleId.isBlank()) {
			return Result.err("Unknown automation rule");
		}
		String filePath = samplePath == null ? "" : samplePath.trim();
		if (filePath.isEmpty()) {
			return Result.err("No file chosen.");
		}
		if (!Files.exists(Path.of(filePath))) {
			return Result.err("That file does not exist.");
		}
		AutomationsFile file = store.read();
		AutomationRule rule = null;
		for (AutomationRule entry : file.rules()) {
			if (entry.id().equals(ruleId)) {
				rule = entry;
				break;
			}
		}
		if (rule == null) {
			return Result.err("Unknown automation rule");
		}
		AutomationEvent event = eventForRule(rule, filePath, notesPath);
		return Result.ok(AutomationEngine.runAutomationRuleNow(rule.id(), event, deps));
	}

	/** Runs any rule now with an explicit event (the UI's file picker decides the path). */
	public AutomationRunResult runNow(String ruleId, AutomationEvent event) {
		return AutomationEngine.runAutomationRuleNow(ruleId, event, deps);
	}

	/**
	 * Port of {@code eventForRule} in {@code electron/ipc/register/automations.ts}: the kind comes
	 * from the trigger and exports carry their format (default mp4).
	 */
	static AutomationEvent eventForRule(AutomationRule rule, String filePath, String notesPath) {
		boolean meeting = rule.trigger() == AutomationTrigger.MEETING_SAVED
				|| rule.trigger() == AutomationTrigger.MEETING_NOTES_READY;
		AutomationExportFormat format = null;
		if (rule.trigger() == AutomationTrigger.EXPORT_FINISHED) {
			format = AutomationTriggers.formatForExportPath(filePath);
			if (format == null) {
				format = AutomationExportFormat.MP4;
			}
		}
		AutomationEvent event = new AutomationEvent(
				rule.trigger(),
				filePath,
				meeting ? AutomationMediaKind.MEETING : AutomationMediaKind.SCREEN,
				format,
				rule.trigger() == AutomationTrigger.MEETING_NOTES_READY ? notesPath : null,
				null,
				false);
		return event;
	}

	/** Fire-and-forget: the engine runs on a virtual thread (plan §4.1). */
	public void fireAutomations(AutomationEvent event) {
		dispatchAsync(event);
	}

	public void fireRecordingSaved(AutomationTriggers.RecordingSavedResult result) {
		triggers.fireRecordingSaved(result);
	}

	public void fireMeetingNotesReady(String mediaPath, String notesPath) {
		triggers.fireMeetingNotesReady(mediaPath, notesPath);
	}

	public void fireExportFinished(String filePath) {
		triggers.fireExportFinished(filePath);
	}

	private void dispatchAsync(AutomationEvent event) {
		executor.execute(() -> {
			try {
				AutomationEngine.runAutomations(event, deps);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.ERROR, "[automations] Automation run failed: " + error);
			}
		});
	}

	private void log(String message) {
		LOG.log(System.Logger.Level.WARNING, message);
	}

	private static java.util.function.Consumer<String> log() {
		return message -> LOG.log(System.Logger.Level.WARNING, message);
	}

	@Override
	public void close() {
		executor.shutdown();
		store.close();
	}
}
