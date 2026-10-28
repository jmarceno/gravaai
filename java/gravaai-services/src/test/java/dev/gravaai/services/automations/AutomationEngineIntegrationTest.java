package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;

import dev.gravaai.core.json.Json;

/**
 * Port of {@code electron/automations/engine.integration.test.ts}: end to end through the real
 * store, the real action runner and real file copies/moves/spawns. Only the desktop services
 * (clipboard/shell/notifications) are a fake host.
 */
class AutomationEngineIntegrationTest {

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
	private AutomationStore store;

	@BeforeEach
	void setUp() throws IOException {
		host = new FakeHost();
		Path userData = Files.createDirectory(tempDir.resolve("userData"));
		store = new AutomationStore(userData.resolve("automations.json"));
	}

	@AfterEach
	void tearDown() {
		store.close();
	}

	private AutomationEngine.Deps deps() {
		return new AutomationEngine.Deps(
				() -> store.read().rules(),
				(action, context) -> AutomationActions.runAutomationAction(action, context, host, tempDir),
				null,
				(title, body) -> host.notify(title, body),
				message -> {},
				Instant::now);
	}

	private void saveRules(AutomationRule... rules) throws IOException {
		JsonNode value = Automations.toJson(new AutomationsFile(1, List.of(rules)));
		store.write(value);
	}

	private static AutomationRule rule(
			String id,
			String name,
			boolean enabled,
			AutomationTrigger trigger,
			List<AutomationCondition> conditions,
			List<AutomationAction> actions) {
		return new AutomationRule(id, name, enabled, trigger, conditions, actions);
	}

	@Test
	@DisplayName("moves an export, reveals the moved file and runs a command on it")
	void movesRevealsAndRuns() throws Exception {
		Path exportsDir = tempDir.resolve("Exports");
		Path source = tempDir.resolve("demo cut.mp4");
		Files.writeString(source, "export-bytes");
		Path marker = tempDir.resolve("command-ran.txt");
		saveRules(
				rule(
						"collect",
						"Collect exports",
						true,
						AutomationTrigger.EXPORT_FINISHED,
						List.of(new AutomationCondition.FormatIs(AutomationExportFormat.MP4)),
						List.of(
								new AutomationAction.MoveToFolder(exportsDir.toString(), "{date} {name}"),
								new AutomationAction.Reveal(),
								new AutomationAction.CopyPath(),
								new AutomationAction.Notify("Exported", "{name}.{ext} in {dir}"),
								new AutomationAction.RunCommand(
										"/bin/sh",
										List.of(
												"-c",
												"printf %s \"$2\" > \"$1\"",
												"--",
												marker.toString(),
												"{path}")))),
				rule(
						"disabled",
						"Disabled",
						false,
						AutomationTrigger.EXPORT_FINISHED,
						List.of(),
						List.of(new AutomationAction.Open())));

		AutomationRunResult result = AutomationEngine.runAutomations(
				new AutomationEvent(
						AutomationTrigger.EXPORT_FINISHED,
						source.toString(),
						null,
						AutomationExportFormat.MP4,
						null,
						null,
						false),
				deps());

		assertEquals(List.of(), result.failures());
		assertEquals(List.of("collect"), result.matchedRuleIds());
		assertEquals(5, result.ranActions());
		List<Path> moved;
		try (var entries = Files.list(exportsDir)) {
			moved = entries.toList();
		}
		assertEquals(1, moved.size());
		assertTrue(moved.get(0).getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2} demo cut\\.mp4"),
				moved.get(0).toString());
		assertFalse(Files.exists(source));
		assertEquals(List.of(moved.get(0).toString()), host.revealed);
		assertEquals(moved.get(0).toString(), host.clipboardText);
		assertEquals(1, host.notifications.size());
		assertEquals("Exported", host.notifications.get(0)[0]);
		String movedName = moved.get(0).getFileName().toString();
		assertEquals(
				movedName.substring(0, movedName.length() - 4) + ".mp4 in " + exportsDir,
				host.notifications.get(0)[1]);
		assertTrue(host.opened.isEmpty());
		assertEquals(moved.get(0).toString(), waitForFile(marker));
	}

	@Test
	@DisplayName("never overwrites: a second copy of the same name gets a suffix")
	void secondCopyGetsSuffix() throws Exception {
		Path target = tempDir.resolve("Copies");
		Path source = tempDir.resolve("recording-1.mp4");
		Files.writeString(source, "recording-bytes");
		saveRules(rule(
				"rule",
				"Rule",
				true,
				AutomationTrigger.RECORDING_SAVED,
				List.of(),
				List.of(
						new AutomationAction.CopyToFolder(target.toString(), null),
						new AutomationAction.Open())));

		AutomationEngine.runAutomations(
				new AutomationEvent(
						AutomationTrigger.RECORDING_SAVED,
						source.toString(),
						AutomationMediaKind.SCREEN,
						null,
						null,
						null,
						false),
				deps());
		AutomationEngine.runAutomations(
				new AutomationEvent(
						AutomationTrigger.RECORDING_SAVED,
						source.toString(),
						AutomationMediaKind.SCREEN,
						null,
						null,
						null,
						false),
				deps());

		List<String> names = new ArrayList<>();
		try (var entries = Files.list(target)) {
			entries.forEach(path -> names.add(path.getFileName().toString()));
		}
		names.sort(String::compareTo);
		assertEquals(List.of("recording-1 (2).mp4", "recording-1.mp4"), names);
		assertEquals("recording-bytes", Files.readString(source));
		assertEquals(
				List.of(target.resolve("recording-1.mp4").toString(), target.resolve("recording-1 (2).mp4").toString()),
				host.opened);
	}

	@Test
	@DisplayName("reports a failing action once and still runs the later ones")
	void reportsFailureOnce() throws Exception {
		Path source = tempDir.resolve("meeting.m4a");
		Files.writeString(source, "audio");
		Path blocker = tempDir.resolve("not-a-folder");
		Files.writeString(blocker, "");
		saveRules(rule(
				"rule",
				"Broken",
				true,
				AutomationTrigger.MEETING_NOTES_READY,
				List.of(),
				List.of(
						new AutomationAction.CopyToFolder(blocker.resolve("inside").toString(), null),
						new AutomationAction.RunCommand(tempDir.resolve("missing-program").toString(), List.of()),
						new AutomationAction.Notify("Notes ready", "{notesPath}"))));

		String notesPath = tempDir.resolve("meeting.notes.md").toString();
		AutomationRunResult result = AutomationEngine.runAutomations(
				new AutomationEvent(
						AutomationTrigger.MEETING_NOTES_READY,
						source.toString(),
						AutomationMediaKind.MEETING,
						null,
						notesPath,
						null,
						false),
				deps());

		assertEquals(
				List.of(AutomationActionType.COPY_TO_FOLDER, AutomationActionType.RUN_COMMAND),
				result.failures().stream().map(AutomationFailure::action).toList());
		assertEquals(1, result.ranActions());
		assertEquals(2, host.notifications.size());
		assertEquals("Automation failed", host.notifications.get(0)[0]);
		assertTrue(host.notifications.get(0)[1].startsWith("Broken: "), host.notifications.get(0)[1]);
		assertEquals("Notes ready", host.notifications.get(1)[0]);
		assertEquals(notesPath, host.notifications.get(1)[1]);
	}

	@Test
	@DisplayName("skips rules whose duration condition is not met")
	void skipsByDuration() throws Exception {
		Path source = tempDir.resolve("short.mp4");
		Files.writeString(source, "x");
		saveRules(rule(
				"rule",
				"Rule",
				true,
				AutomationTrigger.MEETING_SAVED,
				List.of(new AutomationCondition.DurationAtLeast(60)),
				List.of(new AutomationAction.CopyPath())));

		AutomationRunResult shortResult = AutomationEngine.runAutomations(
				new AutomationEvent(
						AutomationTrigger.MEETING_SAVED,
						source.toString(),
						AutomationMediaKind.MEETING,
						null,
						null,
						10.0,
						true),
				deps());
		assertEquals(0, shortResult.ranActions());
		assertTrue(shortResult.skipped().get(0).reason().contains("under 60s"));
		assertEquals("", host.clipboardText);

		AutomationRunResult longResult = AutomationEngine.runAutomations(
				new AutomationEvent(
						AutomationTrigger.MEETING_SAVED,
						source.toString(),
						AutomationMediaKind.MEETING,
						null,
						null,
						70.0,
						true),
				deps());
		assertEquals(1, longResult.ranActions());
		assertEquals(source.toString(), host.clipboardText);
	}

	private static String waitForFile(Path filePath) throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (Files.exists(filePath)) {
				try {
					return Files.readString(filePath);
				} catch (IOException error) {
					throw new AssertionError(error);
				}
			}
			Thread.sleep(25);
		}
		throw new AssertionError("Timed out waiting for " + filePath);
	}

	@Test
	@DisplayName("Json stores the same shape the TS normaliser writes")
	void storeShape() throws IOException {
		saveRules(rule(
				"a",
				"Name",
				true,
				AutomationTrigger.EXPORT_FINISHED,
				List.of(new AutomationCondition.DurationAtLeast(30), new AutomationCondition.FormatIs(AutomationExportFormat.GIF)),
				List.of(
						new AutomationAction.Notify("t", "b"),
						new AutomationAction.RunCommand("echo", List.of("a", "b")))));
		JsonNode raw = Json.MAPPER.readTree(Files.readString(store.filePath()));
		JsonNode rule = raw.get("rules").get(0);
		assertEquals("export-finished", rule.get("trigger").stringValue());
		assertEquals(30, rule.get("conditions").get(0).get("seconds").intValue());
		assertEquals("gif", rule.get("conditions").get(1).get("format").stringValue());
		assertNotEquals(0, rule.get("actions").size());
	}
}
