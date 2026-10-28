package dev.gravaai.services.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Port of {@code electron/ipc/project/trashProjects.test.ts}. */
class TrashLibraryProjectsTest {

	@TempDir
	Path tempDir;

	private record Tree(Path root, Path projectPath) {}

	private Tree createProjectTree(String name, String... extras) throws IOException {
		Path root = Files.createTempDirectory(tempDir, "gravaai-trash-");
		Path projectPath = root.resolve(name + ".gravaai");
		Files.writeString(projectPath, "{}");
		for (String extra : extras) {
			Files.writeString(root.resolve(extra), "x");
		}
		return new Tree(root, projectPath);
	}

	private static TrashLibraryProjects.Dependencies dependencies(List<Path> listed, TrashLibraryProjects.Trash trash,
			Function<Path, Path> thumbnailPath) {
		return new TrashLibraryProjects.Dependencies(() -> listed, trash, thumbnailPath, null);
	}

	@Test
	@DisplayName("rejects paths outside the project library before touching files")
	void rejectsPathsOutsideLibrary() throws Exception {
		Tree tree = createProjectTree("a");
		List<Path> trashed = new CopyOnWriteArrayList<>();

		assertThrows(IllegalArgumentException.class, () -> TrashLibraryProjects.trashLibraryProjects(
				List.of(tree.projectPath().toString(), tree.root().resolve("private.txt").toString()),
				dependencies(List.of(tree.projectPath()), trashed::add, path -> path), false));

		assertTrue(trashed.isEmpty());
	}

	@Test
	@DisplayName("trashes only selected project files, deduplicates paths, and reports partial failures")
	void trashesSelectedProjectsAndReportsPartialFailures() throws Exception {
		Tree tree = createProjectTree("a");
		Path projectB = tree.root().resolve("b.gravaai");
		Files.writeString(projectB, "{}");
		List<Path> trashed = new CopyOnWriteArrayList<>();
		TrashLibraryProjects.Trash trash = path -> {
			trashed.add(path);
			if (path.getFileName().toString().equals("b.gravaai")) {
				throw new IOException("locked");
			}
		};

		TrashLibraryProjects.TrashResult result = TrashLibraryProjects.trashLibraryProjects(
				List.of(tree.projectPath().toString(), tree.projectPath().toString(), projectB.toString()),
				dependencies(List.of(tree.projectPath(), projectB), trash,
						path -> tree.root().resolve("missing.png")),
				false);

		assertEquals(List.of(tree.projectPath().toString()), result.deleted());
		assertEquals(List.of("Could not trash b.gravaai"), result.errors());
		assertEquals(List.of(tree.projectPath(), projectB), trashed);
	}

	@Test
	@DisplayName("keeps recordings by default and only trashes them when opted in")
	void keepsRecordingsByDefault() throws Exception {
		Tree tree = createProjectTree("a", "a.mp4", "a-webcam.mp4");
		List<Path> trashed = new CopyOnWriteArrayList<>();
		AtomicBoolean resolved = new AtomicBoolean();
		TrashLibraryProjects.Dependencies deps = new TrashLibraryProjects.Dependencies(
				() -> List.of(tree.projectPath()), trashed::add,
				path -> Path.of("/missing/thumb.png"),
				path -> {
					resolved.set(true);
					return List.of(tree.root().resolve("a.mp4"), tree.root().resolve("a-webcam.mp4"));
				});

		TrashLibraryProjects.trashLibraryProjects(List.of(tree.projectPath().toString()), deps, false);
		assertTrue(!resolved.get(), "recordings must not be resolved when the option is off");
		assertEquals(List.of(tree.projectPath()), trashed);

		trashed.clear();
		TrashLibraryProjects.TrashResult result = TrashLibraryProjects.trashLibraryProjects(
				List.of(tree.projectPath().toString()), deps, true);

		assertEquals(List.of(), result.errors());
		assertEquals(List.of(tree.projectPath().toString()), result.deleted());
		assertEquals(List.of(tree.projectPath(), tree.root().resolve("a.mp4"), tree.root().resolve("a-webcam.mp4")),
				trashed);
	}

	@Test
	@DisplayName("does not delete a failed project's recordings and reports the failure")
	void doesNotDeleteRecordingsOfFailedProject() throws Exception {
		Tree tree = createProjectTree("a");
		Path projectB = tree.root().resolve("b.gravaai");
		Files.writeString(projectB, "{}");
		List<Path> trashed = new CopyOnWriteArrayList<>();
		TrashLibraryProjects.Trash trash = path -> {
			trashed.add(path);
			if (path.getFileName().toString().equals("b.gravaai")) {
				throw new IOException("locked");
			}
		};

		TrashLibraryProjects.TrashResult result = TrashLibraryProjects.trashLibraryProjects(
				List.of(tree.projectPath().toString(), projectB.toString()),
				new TrashLibraryProjects.Dependencies(() -> List.of(tree.projectPath(), projectB), trash,
						path -> tree.root().resolve("missing.png"),
						path -> List.of(tree.root().resolve("b.mp4"))),
				true);

		assertEquals(List.of(tree.projectPath().toString()), result.deleted());
		assertEquals(List.of("Could not trash b.gravaai"), result.errors());
		assertEquals(List.of(tree.projectPath(), projectB), trashed);
	}

	@Test
	@DisplayName("reports resolution failures as errors instead of losing recordings")
	void reportsResolutionFailures() throws Exception {
		Tree tree = createProjectTree("a");
		List<Path> trashed = new CopyOnWriteArrayList<>();

		TrashLibraryProjects.TrashResult result = TrashLibraryProjects.trashLibraryProjects(
				List.of(tree.projectPath().toString()),
				new TrashLibraryProjects.Dependencies(() -> List.of(tree.projectPath()), trashed::add,
						path -> Path.of("/missing/thumb.png"),
						path -> {
							throw new IOException("unreadable project");
						}),
				true);

		assertEquals(List.of("Could not resolve recordings for a.gravaai"), result.errors());
	}
}