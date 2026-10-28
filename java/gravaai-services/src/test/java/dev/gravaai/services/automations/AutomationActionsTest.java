package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of {@code electron/automations/actions.test.ts}: the Electron {@code clipboard}, {@code
 * shell} and {@code Notification} stubs become an {@link AutomationHost} fake.
 */
class AutomationActionsTest {

	private static final class FakeHost implements AutomationHost {
		private String clipboardText = "";
		private final List<String> revealed = new ArrayList<>();
		private final List<String> opened = new ArrayList<>();
		private String openError = "";
		private final List<String[]> notifications = new ArrayList<>();
		private boolean supported = true;

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
			if (!openError.isEmpty()) {
				throw new IllegalStateException(openError);
			}
		}

		@Override
		public boolean isNotificationSupported() {
			return supported;
		}

		@Override
		public void notify(String title, String body) {
			notifications.add(new String[] {title, body});
		}
	}

	@TempDir
	Path tempDir;

	private FakeHost host;
	private Path sourcePath;
	private final List<String> logs = new ArrayList<>();

	private AutomationContext contextFor(String filePath) {
		return new AutomationContext(
				AutomationTrigger.EXPORT_FINISHED,
				filePath,
				null,
				AutomationExportFormat.MP4,
				null,
				null,
				LocalDateTime.of(2026, 10, 24, 13, 45, 6).atZone(ZoneId.systemDefault()).toInstant());
	}

	@BeforeEach
	void setUp() throws IOException {
		host = new FakeHost();
		logs.clear();
		sourcePath = tempDir.resolve("clip.final.mp4");
		Files.writeString(sourcePath, "video-bytes");
	}

	@Test
	@DisplayName("runAutomationAction copies the current path to the clipboard")
	void copiesPath() {
		AutomationActionOutcome outcome =
				AutomationActions.runAutomationAction(new AutomationAction.CopyPath(), contextFor(sourcePath.toString()), host, tempDir);
		assertEquals(sourcePath.toString(), host.clipboardText);
		assertEquals(sourcePath.toString(), outcome.path());
	}

	@Test
	@DisplayName("runAutomationAction reveals the current path in the folder")
	void revealsPath() {
		AutomationActions.runAutomationAction(new AutomationAction.Reveal(), contextFor(sourcePath.toString()), host, tempDir);
		assertEquals(List.of(sourcePath.toString()), host.revealed);
	}

	@Test
	@DisplayName("runAutomationAction opens the file and throws when the OS reports an error")
	void opensFile() {
		AutomationActions.runAutomationAction(new AutomationAction.Open(), contextFor(sourcePath.toString()), host, tempDir);
		assertEquals(List.of(sourcePath.toString()), host.opened);

		host.openError = "No application is registered for this file type";
		RuntimeException error = assertThrows(
				RuntimeException.class,
				() -> AutomationActions.runAutomationAction(
						new AutomationAction.Open(), contextFor(sourcePath.toString()), host, tempDir));
		assertTrue(error.getMessage().contains("No application is registered"), error.getMessage());
	}

	@Test
	@DisplayName("runAutomationAction shows a notification with expanded templates")
	void notifiesWithTemplates() {
		AutomationActions.runAutomationAction(
				new AutomationAction.Notify("Saved {name}", "{dir}/{name}.{ext}"),
				contextFor(sourcePath.toString()),
				host,
				tempDir);
		assertEquals(1, host.notifications.size());
		assertEquals("Saved clip.final", host.notifications.get(0)[0]);
		assertEquals(tempDir + "/clip.final.mp4", host.notifications.get(0)[1]);
	}

	@Test
	@DisplayName("runAutomationAction reports unsupported notifications instead of silently doing nothing")
	void reportsUnsupportedNotifications() {
		host.supported = false;
		RuntimeException error = assertThrows(
				RuntimeException.class,
				() -> AutomationActions.runAutomationAction(
						new AutomationAction.Notify("Hi", ""), contextFor(sourcePath.toString()), host, tempDir));
		assertTrue(error.getMessage().contains("not supported"), error.getMessage());
		assertTrue(host.notifications.isEmpty());
	}

	@Test
	@DisplayName("runAutomationAction copies into a folder and hands the new path back")
	void copiesToFolder() {
		Path folder = tempDir.resolve("exports");
		AutomationActionOutcome outcome = AutomationActions.runAutomationAction(
				new AutomationAction.CopyToFolder(folder.toString(), "{date} {name}"),
				contextFor(sourcePath.toString()),
				host,
				tempDir);
		assertEquals(folder.resolve("2026-10-24 clip.final.mp4").toString(), outcome.path());
		assertEquals("video-bytes", readString(Path.of(outcome.path())));
	}

	@Test
	@DisplayName("runAutomationAction moves into a folder and hands the new path back")
	void movesToFolder() {
		Path folder = tempDir.resolve("moved");
		AutomationActionOutcome outcome = AutomationActions.runAutomationAction(
				new AutomationAction.MoveToFolder(folder.toString(), null),
				contextFor(sourcePath.toString()),
				host,
				tempDir);
		assertEquals(folder.resolve("clip.final.mp4").toString(), outcome.path());
		assertFalse(Files.exists(sourcePath));
	}

	@Test
	@DisplayName("runAutomationAction runs a command with expanded arguments")
	void runsCommand() throws IOException {
		Path outputPath = tempDir.resolve("args.txt");
		Files.writeString(tempDir.resolve("command-input.txt"), "payload");
		AutomationActions.runAutomationAction(
				new AutomationAction.RunCommand(
						"/bin/cp", List.of(tempDir.resolve("command-input.txt").toString(), "{path}")),
				contextFor(outputPath.toString()),
				host,
				tempDir);
		waitForFile(outputPath);
		assertEquals("payload", Files.readString(outputPath));
	}

	@Test
	@DisplayName("showAutomationFailureNotification shows the failure notification")
	void showsFailureNotification() {
		AutomationActions.showAutomationFailureNotification("Automation failed", "Broken rule: EACCES", host, logs::add);
		assertEquals(1, host.notifications.size());
		assertEquals("Automation failed", host.notifications.get(0)[0]);
		assertEquals("Broken rule: EACCES", host.notifications.get(0)[1]);
	}

	@Test
	@DisplayName("showAutomationFailureNotification logs instead when notifications are unsupported")
	void logsUnsupportedFailureNotification() {
		host.supported = false;
		AutomationActions.showAutomationFailureNotification("Automation failed", "Broken rule: EACCES", host, logs::add);
		assertTrue(host.notifications.isEmpty());
		assertTrue(logs.get(0).contains("Broken rule: EACCES"));
	}

	private static String readString(Path path) {
		try {
			return Files.readString(path);
		} catch (IOException error) {
			throw new AssertionError(error);
		}
	}

	private static void waitForFile(Path filePath) {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (Files.exists(filePath)) {
				return;
			}
			try {
				Thread.sleep(25);
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
				throw new AssertionError(error);
			}
		}
		throw new AssertionError("Timed out waiting for " + filePath);
	}
}
