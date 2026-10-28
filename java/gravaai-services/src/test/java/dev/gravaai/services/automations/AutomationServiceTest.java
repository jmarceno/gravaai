package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.result.Result;

/**
 * Behaviour of the facade that replaces the IPC handlers of
 * {@code electron/ipc/register/automations.ts} (the TS file's own test covers the Electron
 * handlers; here the dialog branches become caller-supplied paths).
 */
class AutomationServiceTest {

	private static final class FakeHost implements AutomationHost {
		private String clipboardText = "";
		private final List<String> revealed = new ArrayList<>();
		private final List<String> opened = new ArrayList<>();
		private final List<String[]> notifications = new ArrayList<>();

		@Override
		public void copyToClipboard(String text) {
			clipboardText = text;
		}

		@Override
		public void revealInFolder(String path) {
			revealed.add(path);
		}

		@Override
		public void openPath(String path) {
			opened.add(path);
		}

		@Override
		public boolean isNotificationSupported() {
			return true;
		}

		@Override
		public void notify(String title, String body) {
			notifications.add(new String[] {title, body});
		}
	}

	@TempDir
	Path tempDir;

	private FakeHost host;
	private Path storePath;
	private AutomationService service;

	@BeforeEach
	void setUp() {
		host = new FakeHost();
		storePath = tempDir.resolve("userData/automations.json");
		service = new AutomationService(storePath, host, path -> 42.0, tempDir);
	}

	@AfterEach
	void tearDown() {
		service.close();
	}

	private static AutomationRule rule(
			String id, AutomationTrigger trigger, List<AutomationAction> actions) {
		return new AutomationRule(id, "Rule", true, trigger, List.of(), actions);
	}

	private void save(AutomationRule... rules) {
		Result<AutomationsFile> result = service.saveAutomations(
				Automations.toJson(new AutomationsFile(1, List.of(rules))));
		assertTrue(result.isOk(), result.errorMessage());
	}

	@Test
	@DisplayName("saveAutomations refuses move-to-folder outside export rules")
	void refusesDisallowedAction() {
		Result<AutomationsFile> result = service.saveAutomations(Json.MAPPER.readTree("""
				{"rules": [{"id": "a", "name": "Bad", "trigger": "recording-saved",
				  "actions": [{"type": "move-to-folder", "folder": "/tmp/x"}]}]}
				"""));
		assertTrue(result.isErr());
		assertEquals(
				"\"move-to-folder\" is not available for recording-saved rules", result.errorMessage());
		assertTrue(service.getAutomations().rules().isEmpty());
	}

	@Test
	@DisplayName("saveAutomations accepts move-to-folder for exports and writes the file")
	void acceptsExportMove() {
		Result<AutomationsFile> result = service.saveAutomations(Json.MAPPER.readTree("""
				{"rules": [{"id": "a", "name": "Move", "trigger": "export-finished",
				  "actions": [{"type": "move-to-folder", "folder": "/tmp/x"}]}]}
				"""));
		assertTrue(result.isOk(), result.errorMessage());
		assertEquals(1, service.getAutomations().rules().size());
		assertTrue(Files.exists(storePath));
	}

	@Test
	@DisplayName("runAutomationOnFile rejects unknown rules and missing files")
	void runOnFileValidation() throws IOException {
		assertEquals("Unknown automation rule", service.runAutomationOnFile("", "/tmp/a.mp4").errorMessage());
		Path existing = tempDir.resolve("exists.mp4");
		Files.writeString(existing, "x");
		assertEquals(
				"Unknown automation rule",
				service.runAutomationOnFile("nope", existing.toString()).errorMessage());

		save(rule("rule-1", AutomationTrigger.RECORDING_SAVED, List.of(new AutomationAction.CopyPath())));
		assertEquals(
				"That file does not exist.",
				service.runAutomationOnFile("rule-1", tempDir.resolve("missing.mp4").toString()).errorMessage());
		assertEquals("No file chosen.", service.runAutomationOnFile("rule-1", "  ").errorMessage());
	}

	@Test
	@DisplayName("runAutomationOnFile runs the rule on the chosen file")
	void runsOnFile() throws IOException {
		save(rule("rule-1", AutomationTrigger.RECORDING_SAVED, List.of(new AutomationAction.CopyPath())));
		Path samplePath = tempDir.resolve("clip.mp4");
		Files.writeString(samplePath, "x");

		Result<AutomationRunResult> result = service.runAutomationOnFile("rule-1", samplePath.toString());

		assertTrue(result.isOk(), result.errorMessage());
		assertEquals(List.of("rule-1"), result.orElse(null).matchedRuleIds());
		assertEquals(samplePath.toString(), host.clipboardText);
	}

	@Test
	@DisplayName("eventForRule sets the kind per trigger and the export format with an mp4 default")
	void eventForRule() {
		AutomationRule export = new AutomationRule(
				"e", "Export", true, AutomationTrigger.EXPORT_FINISHED, List.of(), List.of());
		AutomationEvent exportEvent = AutomationService.eventForRule(export, "/tmp/out.mkv", null);
		assertEquals(AutomationMediaKind.SCREEN, exportEvent.kind());
		assertEquals(AutomationExportFormat.MP4, exportEvent.format());

		AutomationRule gif = new AutomationRule(
				"g", "Gif", true, AutomationTrigger.EXPORT_FINISHED, List.of(), List.of());
		assertEquals(AutomationExportFormat.GIF, AutomationService.eventForRule(gif, "/tmp/out.gif", null).format());

		AutomationRule recording = new AutomationRule(
				"r", "Recording", true, AutomationTrigger.RECORDING_SAVED, List.of(), List.of());
		assertEquals(AutomationMediaKind.SCREEN, AutomationService.eventForRule(recording, "/tmp/a.mp4", null).kind());

		AutomationRule notes = new AutomationRule(
				"n", "Notes", true, AutomationTrigger.MEETING_NOTES_READY, List.of(), List.of());
		AutomationEvent notesEvent = AutomationService.eventForRule(notes, "/tmp/m.m4a", "/tmp/m.notes.md");
		assertEquals(AutomationMediaKind.MEETING, notesEvent.kind());
		assertEquals("/tmp/m.notes.md", notesEvent.notesPath());
	}

	@Test
	@DisplayName("fireRecordingSaved runs the rules on a virtual thread")
	void firesRecordingSaved() throws IOException {
		save(rule("rule-1", AutomationTrigger.RECORDING_SAVED, List.of(new AutomationAction.CopyPath())));
		Path file = tempDir.resolve("screen.mp4");
		Files.writeString(file, "x");

		service.fireRecordingSaved(new AutomationTriggers.RecordingSavedResult(true, file.toString(), false));

		waitUntil(() -> file.toString().equals(host.clipboardText), "clipboard to be filled");
	}

	@Test
	@DisplayName("fireMeetingNotesReady runs notes rules with the notes path")
	void firesMeetingNotesReady() throws IOException {
		save(rule(
				"rule-1",
				AutomationTrigger.MEETING_NOTES_READY,
				List.of(new AutomationAction.Notify("Notes", "{notesPath}"))));
		Path media = tempDir.resolve("meeting.m4a");
		Files.writeString(media, "x");

		service.fireMeetingNotesReady(media.toString(), "/tmp/meeting.notes.md");

		waitUntil(() -> !host.notifications.isEmpty(), "a notification");
		assertEquals("/tmp/meeting.notes.md", host.notifications.get(0)[1]);
	}

	@Test
	@DisplayName("a service with no probe reports duration conditions as unknown")
	void noProbeMeansUnknownDuration() throws IOException {
		save(new AutomationRule(
				"rule-1",
				"Long",
				true,
				AutomationTrigger.RECORDING_SAVED,
				List.of(new AutomationCondition.DurationAtLeast(60)),
				List.of(new AutomationAction.CopyPath())));
		Path file = tempDir.resolve("clip.mp4");
		Files.writeString(file, "x");
		try (AutomationService withoutProbe = new AutomationService(storePath, host, null, tempDir)) {
			AutomationRunResult result = AutomationEngine.runAutomations(
					AutomationEvent.of(AutomationTrigger.RECORDING_SAVED, file.toString()), withoutProbe.deps());
			assertTrue(result.matchedRuleIds().isEmpty());
			assertEquals("duration is unknown", result.skipped().get(0).reason());
			assertEquals("", host.clipboardText);
		}
	}

	private static void waitUntil(java.util.function.BooleanSupplier condition, String what) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(25);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new AssertionError(error);
			}
		}
		throw new AssertionError("Timed out waiting for " + what);
	}
}
