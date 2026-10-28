package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port of {@code electron/automations/fileActions.test.ts}.
 */
class FileActionsTest {

	@TempDir
	Path tempDir;

	private Path sourcePath;
	private Path exportDir;

	private static AutomationContext contextFor(String filePath) {
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
		sourcePath = tempDir.resolve("clip.final.mp4");
		Files.writeString(sourcePath, "video-bytes");
		exportDir = tempDir.resolve("exports");
	}

	@Test
	@DisplayName("expandHomeDirectory expands only a leading tilde")
	void expandHomeDirectory() {
		assertEquals("/home/me", FileActions.expandHomeDirectory("~", Path.of("/home/me")));
		assertEquals("/home/me/Videos", FileActions.expandHomeDirectory("~/Videos", Path.of("/home/me")));
		assertEquals("/home/me/Videos", FileActions.expandHomeDirectory("~\\Videos", Path.of("/home/me")));
		assertEquals("/tmp/~/x", FileActions.expandHomeDirectory("/tmp/~/x", Path.of("/home/me")));
	}

	@Test
	@DisplayName("resolveFolderPath expands templates and home, and rejects relative folders")
	void resolveFolderPath() {
		AutomationContext context = contextFor(sourcePath.toString());
		assertEquals(
				Path.of("/home/me/Videos/2026-10-24"),
				FileActions.resolveFolderPath("~/Videos/{date}", context, Path.of("/home/me")));
		assertThrows(
				IllegalArgumentException.class,
				() -> FileActions.resolveFolderPath("relative/dir", context, Path.of("/home/me")));
		assertTrue(assertThrows(
						IllegalArgumentException.class,
						() -> FileActions.resolveFolderPath("  ", context, Path.of("/home/me")))
				.getMessage()
				.contains("No folder configured"));
	}

	@Test
	@DisplayName("copyFileToFolder creates the folder, keeps the extension and copies the bytes")
	void copiesToFolder() throws IOException {
		String target = FileActions.copyFileToFolder(
				new FileActions.FileActionOptions(contextFor(sourcePath.toString()), exportDir.toString(), null, tempDir));
		assertEquals(exportDir.resolve("clip.final.mp4").toString(), target);
		assertEquals("video-bytes", Files.readString(Path.of(target)));
		assertEquals("video-bytes", Files.readString(sourcePath));
	}

	@Test
	@DisplayName("copyFileToFolder never overwrites: appends (2), (3) …")
	void neverOverwrites() throws IOException {
		FileActions.FileActionOptions options = new FileActions.FileActionOptions(
				contextFor(sourcePath.toString()), exportDir.toString(), null, tempDir);
		FileActions.copyFileToFolder(options);
		String second = FileActions.copyFileToFolder(options);
		String third = FileActions.copyFileToFolder(options);
		assertEquals(exportDir.resolve("clip.final (2).mp4").toString(), second);
		assertEquals(exportDir.resolve("clip.final (3).mp4").toString(), third);
	}

	@Test
	@DisplayName("copyFileToFolder expands the file name template and sanitises it")
	void expandsFileNameTemplate() throws IOException {
		String target = FileActions.copyFileToFolder(new FileActions.FileActionOptions(
				contextFor(sourcePath.toString()), exportDir.toString(), "{date} {name}/{kind}", tempDir));
		assertEquals("2026-10-24 clip.final.mp4", Path.of(target).getFileName().toString());
	}

	@Test
	@DisplayName("copyFileToFolder uses {name} when no file name is configured")
	void usesNameFallback() throws IOException {
		String target = FileActions.copyFileToFolder(new FileActions.FileActionOptions(
				contextFor(sourcePath.toString()), exportDir.toString(), "   ", tempDir));
		assertEquals("clip.final.mp4", Path.of(target).getFileName().toString());
	}

	@Test
	@DisplayName("moveFileToFolder moves the file and removes the source")
	void movesToFolder() throws IOException {
		String target = FileActions.moveFileToFolder(new FileActions.FileActionOptions(
				contextFor(sourcePath.toString()),
				exportDir.resolve("nested").toString(),
				"{date} {name}",
				tempDir));
		assertEquals(exportDir.resolve("nested").resolve("2026-10-24 clip.final.mp4").toString(), target);
		assertEquals("video-bytes", Files.readString(Path.of(target)));
		assertFalse(Files.exists(sourcePath));
	}

	@Test
	@DisplayName("moveFileToFolder does not overwrite an existing target")
	void moveDoesNotOverwrite() throws IOException {
		Files.createDirectories(exportDir);
		Files.writeString(exportDir.resolve("clip.final.mp4"), "existing");
		String target = FileActions.moveFileToFolder(new FileActions.FileActionOptions(
				contextFor(sourcePath.toString()), exportDir.toString(), null, tempDir));
		assertEquals(exportDir.resolve("clip.final (2).mp4").toString(), target);
		assertEquals("existing", Files.readString(exportDir.resolve("clip.final.mp4")));
	}

	@Test
	@DisplayName("runDetachedCommand runs without a shell and passes arguments as separate values")
	void runsDetachedCommand() throws IOException {
		Path spacedSource = tempDir.resolve("source with spaces.mp4");
		Files.writeString(spacedSource, "payload");
		Path target = tempDir.resolve("target with spaces.mp4");
		Path cp = Path.of("/bin/cp");
		FileActions.runDetachedCommand(new FileActions.RunDetachedCommandOptions(
				cp.toString(), List.of(spacedSource.toString(), target.toString()), tempDir, tempDir));
		waitForFile(target);
		assertEquals("payload", Files.readString(target));
	}

	@Test
	@DisplayName("runDetachedCommand rejects a missing program")
	void rejectsMissingProgram() {
		IOException error = assertThrows(
				IOException.class,
				() -> FileActions.runDetachedCommand(new FileActions.RunDetachedCommandOptions(
						tempDir.resolve("definitely-missing-binary").toString(),
						List.of(),
						tempDir,
						tempDir)));
		assertTrue(error.getMessage().contains("definitely-missing-binary") || error.getMessage().contains("No such file"),
				error.getMessage());
	}

	@Test
	@DisplayName("runDetachedCommand rejects an empty program")
	void rejectsEmptyProgram() {
		assertTrue(assertThrows(
						IllegalArgumentException.class,
						() -> FileActions.runDetachedCommand(
								new FileActions.RunDetachedCommandOptions("  ", List.of(), tempDir, tempDir)))
				.getMessage()
				.contains("No program configured"));
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

	@Test
	@DisplayName("contextFor keeps the same local date the file action templates use")
	void contextDate() {
		Instant at = contextFor(sourcePath.toString()).at();
		assertEquals("2026-10-24", AutomationText.formatAutomationDate(at));
	}
}
