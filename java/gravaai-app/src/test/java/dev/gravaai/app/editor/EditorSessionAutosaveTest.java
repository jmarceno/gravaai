package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.project.ProjectDocument;

/**
 * Sweep gap A2: the debounced autosave the Electron app has. Edits reach the disk without a user
 * action, an unsaved recording acquires exactly one project file, an idle editor rewrites nothing,
 * and a write that cannot happen never reports success — it keeps the work dirty and says why.
 */
class EditorSessionAutosaveTest {

	private static final String MINIMAL_PROJECT = """
			{
			  "version": 2,
			  "projectId": "11111111-2222-3333-4444-555555555555",
			  "videoPath": "missing-recording.mp4",
			  "editor": {
			    "clipRegions": [{"id": "clip-1", "startMs": 0, "endMs": 6000, "speed": 1}]
			  }
			}
			""";

	private final List<EditorSession.Autosaved> saved = new CopyOnWriteArrayList<>();
	private final List<String> failures = new CopyOnWriteArrayList<>();

	private EditorSession.AutosaveHooks hooks(@Nullable Path projectsDir) {
		// Direct executor: the snapshot, the write and the result application all run on the single
		// autosave thread, so the test observes exactly the order the app's FX thread imposes.
		return new EditorSession.AutosaveHooks(() -> projectsDir, () -> false, Runnable::run,
				saved::add, failures::add);
	}

	private static Path writeProject(Path directory, String name) throws Exception {
		Path path = directory.resolve(name + ".gravaai");
		Files.writeString(path, MINIMAL_PROJECT);
		return path;
	}

	private static Path writeRecording(Path directory, String name) throws Exception {
		Path video = directory.resolve(name);
		Files.write(video, new byte[] {0});
		return video;
	}

	private static void awaitUntil(BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000L;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				fail("timed out waiting for " + what);
			}
			Thread.sleep(10);
		}
	}

	private static long projectFileCount(Path directory) throws Exception {
		try (var files = Files.list(directory)) {
			return files.filter(file -> file.getFileName().toString().endsWith(".gravaai")).count();
		}
	}

	@Test
	@DisplayName("an edit reaches the project file after the debounce, with no user action")
	void editsReachDiskOnTheirOwn(@TempDir Path directory) throws Exception {
		Path path = writeProject(directory, "Autosave fixture");
		try (EditorSession session = EditorSession.open(path)) {
			session.setAutosaveDelayMs(50);
			session.enableAutosave(hooks(directory));
			session.store().apply(new ZoomCommands.Add(1000, 2500));
			assertTrue(session.dirty(), "the write is debounced, not immediate");
			awaitUntil(() -> !session.dirty(), "the autosave to land");
			assertEquals(1, saved.size());
			assertEquals(path, saved.getFirst().projectPath());
			assertFalse(saved.getFirst().createdFile(), "the project already had a file");
			assertTrue(failures.isEmpty(), "no failure: " + failures);
		}
		ProjectDocument reloaded = ProjectDocument.load(path);
		assertEquals(1, reloaded.editor().zoomRegions().size());
		assertEquals(1000, reloaded.editor().zoomRegions().getFirst().startMs(), 0.001);
	}

	@Test
	@DisplayName("a burst of edits coalesces into one write that carries all of them")
	void editBurstLandsAsOneWrite(@TempDir Path directory) throws Exception {
		Path path = writeProject(directory, "Autosave fixture");
		try (EditorSession session = EditorSession.open(path)) {
			session.setAutosaveDelayMs(50);
			session.enableAutosave(hooks(directory));
			session.store().apply(new ZoomCommands.Add(1000, 2500));
			session.store().apply(new ZoomCommands.Add(3000, 4000));
			awaitUntil(() -> !session.dirty() && !saved.isEmpty(), "the burst to land");
			awaitUntil(() -> saved.size() == 1 && !session.dirty(), "one write, not one per edit");
			assertEquals(1, saved.size(), "the debounce coalesces the burst");
			assertTrue(failures.isEmpty(), "no failure: " + failures);
		}
		ProjectDocument reloaded = ProjectDocument.load(path);
		assertEquals(2, reloaded.editor().zoomRegions().size());
	}

	@Test
	@DisplayName("a fresh recording acquires exactly one project file after the first edit")
	void freshRecordingAcquiresOneFile(@TempDir Path directory) throws Exception {
		Path video = writeRecording(directory, "recording-1793184468126.mp4");
		Path projects = directory.resolve("Projects");
		Files.createDirectories(projects);
		try (EditorSession session = EditorSession.openRecording(video, null, 0)) {
			session.setAutosaveDelayMs(50);
			session.enableAutosave(hooks(projects));
			assertTrue(session.isUnsaved());
			session.store().apply(new ZoomCommands.Add(1000, 2500));
			awaitUntil(() -> !session.isUnsaved(), "the untitled project to be created");

			Path created = session.projectPath();
			assertNotNull(created);
			assertEquals("recording-1793184468126.gravaai", created.getFileName().toString(),
					"named after the recording, the name the editor already shows");
			assertTrue(Files.isRegularFile(created));
			assertEquals(1, saved.size());
			assertTrue(saved.getFirst().createdFile(), "the first file is announced to the dashboard");

			// A second edit continues in the same file; the recording never grows a second project.
			session.store().apply(new ZoomCommands.Add(3000, 4000));
			awaitUntil(() -> !session.dirty(), "the second autosave to land");
			assertEquals(created, session.projectPath());
			assertEquals(1, projectFileCount(projects));
			assertEquals(2, saved.size(), "the second write is reported as an update");
			assertFalse(saved.get(1).createdFile(),
					"only the first write is announced to the dashboard");
			assertEquals(2, ProjectDocument.load(created).editor().zoomRegions().size());
		}
	}

	@Test
	@DisplayName("a Save As that beats the first autosave leaves no stray project behind")
	void saveAsDuringTheFirstAutosaveLeavesNoStray(@TempDir Path directory) throws Exception {
		Path video = writeRecording(directory, "recording-1793184468126.mp4");
		Path projects = Files.createDirectories(directory.resolve("Projects"));
		Path chosen = directory.resolve("Chosen.gravaai");
		// The UI executor holds every second hand-off: the snapshot runs, the write result waits
		// until the test has done its Save As, exactly like a result queued behind a dialog.
		List<Runnable> held = new CopyOnWriteArrayList<>();
		java.util.concurrent.atomic.AtomicInteger handOffs =
				new java.util.concurrent.atomic.AtomicInteger();
		try (EditorSession session = EditorSession.openRecording(video, null, 0)) {
			session.setAutosaveDelayMs(50);
			session.enableAutosave(new EditorSession.AutosaveHooks(() -> projects, () -> false,
					task -> {
						if (handOffs.incrementAndGet() == 2) {
							held.add(task);
						} else {
							task.run();
						}
					}, saved::add, failures::add));
			session.store().apply(new ZoomCommands.Add(1000, 2500));
			awaitUntil(() -> !held.isEmpty(), "the first autosave to finish writing");
			assertEquals(1, projectFileCount(projects), "the untitled file is on disk");

			session.saveAs(chosen);
			held.getFirst().run();

			assertEquals(chosen, session.projectPath(), "the user's choice stays the project");
			assertEquals(0, projectFileCount(projects), "the superseded autosave is removed");
			assertTrue(saved.isEmpty(), "no second project is announced to the dashboard");
			assertTrue(failures.isEmpty(), "no failure: " + failures);
		}
	}

	@Test
	@DisplayName("an existing project is picked up without overwriting it")
	void collisionGetsTheNumberedName(@TempDir Path directory) throws Exception {
		Path video = writeRecording(directory, "recording-take.mp4");
		Path projects = directory.resolve("Projects");
		Files.createDirectories(projects);
		Files.writeString(projects.resolve("recording-take.gravaai"), MINIMAL_PROJECT);
		try (EditorSession session = EditorSession.openRecording(video, null, 0)) {
			session.setAutosaveDelayMs(50);
			session.enableAutosave(hooks(projects));
			session.store().apply(new ZoomCommands.Add(1000, 2500));
			awaitUntil(() -> !session.isUnsaved(), "the autosave to pick a free name");
			assertEquals("recording-take 1.gravaai", session.projectPath().getFileName().toString());
			assertEquals(2, projectFileCount(projects));
			assertEquals(MINIMAL_PROJECT, Files.readString(projects.resolve("recording-take.gravaai")),
					"the project that was already there is untouched");
		}
	}

	@Test
	@DisplayName("an idle editor rewrites nothing, and flushing a clean project does not touch it")
	void idleWritesNothing(@TempDir Path directory) throws Exception {
		Path path = writeProject(directory, "Autosave fixture");
		long before = Files.getLastModifiedTime(path).toMillis();
		try (EditorSession session = EditorSession.open(path)) {
			session.setAutosaveDelayMs(20);
			session.enableAutosave(hooks(directory));
			Thread.sleep(400);
			session.flushAutosave();
			assertEquals(before, Files.getLastModifiedTime(path).toMillis(),
					"the file's mtime is stable while the editor is idle");
			assertTrue(saved.isEmpty(), "no autosave ran");
			assertTrue(failures.isEmpty());
		}
	}

	@Test
	@DisplayName("leaving the editor flushes, so a close prompt is not needed")
	void flushWritesThePendingEdits(@TempDir Path directory) throws Exception {
		Path path = writeProject(directory, "Autosave fixture");
		try (EditorSession session = EditorSession.open(path)) {
			session.setAutosaveDelayMs(60_000);
			session.enableAutosave(hooks(directory));
			session.store().apply(new ZoomCommands.Add(1000, 2500));
			assertTrue(session.dirty(), "the timer is far out; nothing written yet");
			session.flushAutosave();
			assertFalse(session.dirty(), "the flush is the close-path guarantee");
			assertEquals(path, saved.getFirst().projectPath());
		}
		assertEquals(1, ProjectDocument.load(path).editor().zoomRegions().size());
	}

	@Test
	@DisplayName("a write that cannot happen keeps the work dirty and reports why")
	void failedWriteKeepsTheWorkDirty(@TempDir Path directory) throws Exception {
		Path video = writeRecording(directory, "recording-blocked.mp4");
		Path projects = directory.resolve("Projects");
		Files.createDirectories(projects, PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("r-x------")));
		try {
			try (EditorSession session = EditorSession.openRecording(video, null, 0)) {
				session.setAutosaveDelayMs(50);
				session.enableAutosave(hooks(projects));
				session.store().apply(new ZoomCommands.Add(1000, 2500));
				awaitUntil(() -> !failures.isEmpty(), "the autosave to report the failure");

				assertTrue(session.dirty(), "the work is not on disk, so it stays dirty");
				assertTrue(session.isUnsaved(), "no file was created");
				assertNotNull(session.autosaveFailure());
				assertTrue(saved.isEmpty(), "a failed write never reports success");
				assertEquals(0, projectFileCount(projects));

				// The folder becomes writable again: the next save recovers, and only then reports
				// success and clears the reason.
				Files.setPosixFilePermissions(projects, PosixFilePermissions.fromString("rwx------"));
				session.flushAutosave();
				assertFalse(session.dirty());
				assertNull(session.autosaveFailure());
				assertFalse(session.isUnsaved(), "the project file finally exists");
				assertEquals(1, saved.size(), "the recovered write reports success once");
				assertEquals(1, projectFileCount(projects));
			}
		} finally {
			Files.setPosixFilePermissions(projects, PosixFilePermissions.fromString("rwx------"));
		}
	}

	@Test
	@DisplayName("a session without autosave wiring keeps the explicit-save behaviour")
	void unwiredSessionIsNotTouched(@TempDir Path directory) throws Exception {
		Path path = writeProject(directory, "Autosave fixture");
		long before = Files.getLastModifiedTime(path).toMillis();
		try (EditorSession session = EditorSession.open(path)) {
			session.store().apply(new ZoomCommands.Add(1000, 2500));
			Thread.sleep(200);
			assertTrue(session.dirty(), "no hooks: no autosave, as before");
			assertEquals(before, Files.getLastModifiedTime(path).toMillis());
			session.flushAutosave();
			assertTrue(session.dirty());
		}
	}

	@Test
	@DisplayName("an untitled project with nowhere to go is not saved and says nothing")
	void untitledWithoutProjectsDirStaysDirty(@TempDir Path directory) throws Exception {
		Path video = writeRecording(directory, "recording-nowhere.mp4");
		try (EditorSession session = EditorSession.openRecording(video, null, 0)) {
			session.setAutosaveDelayMs(50);
			session.enableAutosave(hooks(null));
			session.store().apply(new ZoomCommands.Add(1000, 2500));
			Thread.sleep(300);
			assertTrue(session.dirty());
			assertTrue(session.isUnsaved());
			assertTrue(saved.isEmpty());
			assertTrue(failures.isEmpty(), "there is no Projects folder: nothing to warn about");
			session.flushAutosave();
			assertTrue(session.dirty());
		}
	}
}
