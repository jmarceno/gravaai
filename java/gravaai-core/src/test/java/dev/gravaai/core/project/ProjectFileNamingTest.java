package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file names autosave creates for projects that have never been saved: filesystem-safe, never
 * empty, never colliding with a project already in the folder.
 */
class ProjectFileNamingTest {

	@Test
	@DisplayName("display names become safe base names")
	void sanitisesBaseNames() {
		assertEquals("My recording", ProjectFileNaming.safeBaseName("  My   recording  "));
		assertEquals("a-b-c-d", ProjectFileNaming.safeBaseName("a/b:c*d"));
		assertEquals("one two", ProjectFileNaming.safeBaseName("one\t\n two"));
		assertEquals("No trailing", ProjectFileNaming.safeBaseName("No trailing... "));
		assertEquals(ProjectFileNaming.UNTITLED_BASE_NAME, ProjectFileNaming.safeBaseName("   "));
		assertEquals(ProjectFileNaming.UNTITLED_BASE_NAME, ProjectFileNaming.safeBaseName(".."));
		assertEquals(ProjectFileNaming.UNTITLED_BASE_NAME, ProjectFileNaming.safeBaseName(null));
	}

	@Test
	@DisplayName("a long display name is cut, so a card title stays readable")
	void cutsOverlongBaseNames() {
		String base = ProjectFileNaming.safeBaseName("x".repeat(300));
		assertEquals(ProjectFileNaming.MAX_BASE_NAME_LENGTH, base.length());
	}

	@Test
	@DisplayName("an empty folder gets the plain name, then numbered copies around a collision")
	void picksTheFirstFreeName(@TempDir Path directory) throws Exception {
		assertEquals(directory.resolve("Recording.gravaai"),
				ProjectFileNaming.uniqueProjectPath(directory, "Recording"));

		Files.writeString(directory.resolve("Recording.gravaai"), "{}");
		assertEquals(directory.resolve("Recording 1.gravaai"),
				ProjectFileNaming.uniqueProjectPath(directory, "Recording"));

		Files.writeString(directory.resolve("Recording 1.gravaai"), "{}");
		assertEquals(directory.resolve("Recording 2.gravaai"),
				ProjectFileNaming.uniqueProjectPath(directory, "Recording"));
	}

	@Test
	@DisplayName("the pick never touches the disk")
	void uniqueProjectPathCreatesNothing(@TempDir Path directory) {
		Path picked = ProjectFileNaming.uniqueProjectPath(directory, "Recording");
		assertFalse(Files.exists(picked));
	}
}
