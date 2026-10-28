package dev.gravaai.services.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Port of {@code electron/ipc/project/renameLibraryProject.test.ts}. */
class RenameLibraryProjectTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("renames a library project with its preview without overwriting another project")
	void renamesLibraryProjectWithPreview() throws Exception {
		Path original = tempDir.resolve("Original.gravaai");
		Path target = tempDir.resolve("Renamed.gravaai");
		Function<Path, List<Path>> sidecars = file -> List.of(Path.of(file + ".png"));

		Files.writeString(original, "project");
		Files.writeString(Path.of(original + ".png"), "preview");
		Files.writeString(target, "keep");

		assertThrows(IOException.class,
				() -> RenameLibraryProject.renameLibraryProject(original, "Renamed", List.of(original), sidecars));
		assertEquals("keep", Files.readString(target));

		Files.delete(target);

		assertThrows(RuntimeException.class,
				() -> RenameLibraryProject.renameLibraryProject(original, "../escape", List.of(original), sidecars));
		assertThrows(RuntimeException.class,
				() -> RenameLibraryProject.renameLibraryProject(original, "Renamed", List.of(), sidecars));

		Path renamed = RenameLibraryProject.renameLibraryProject(original, "Renamed", List.of(original), sidecars);

		assertEquals(target, renamed);
		assertEquals("project", Files.readString(target));
		assertEquals("preview", Files.readString(Path.of(target + ".png")));
		assertFalse(Files.exists(original));
	}

	@Test
	@DisplayName("rejects names with path separators, control characters or a trailing dot")
	void rejectsInvalidNames() throws Exception {
		Path original = tempDir.resolve("Original.gravaai");
		Files.writeString(original, "project");
		List<Path> allowed = List.of(original);
		Function<Path, List<Path>> sidecars = file -> List.of();

		for (String name : List.of("", "   ", ".", "..", "a/b", "a\\b", "a:b", "a?b", "bad\u0001name", "trailing.")) {
			assertThrows(RuntimeException.class,
					() -> RenameLibraryProject.renameLibraryProject(original, name, allowed, sidecars),
					"expected '" + name + "' to be rejected");
		}
	}
}