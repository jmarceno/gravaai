package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RenameLibraryProject;

/** Task-21 stage: save / save-as / rename flows against a copy of a real project fixture. */
class EditorProjectFlowsTest {

	private static final Path FIXTURE =
			Path.of("../gravaai-core/src/test/resources/project", "Untitled Project 1.gravaai");

	private static Path copyFixture(Path directory, String name) throws Exception {
		Path target = directory.resolve(name + ".gravaai");
		Files.copy(FIXTURE, target);
		// The checked-in fixtures are read-only; the working copy must be writable.
		target.toFile().setWritable(true);
		return target;
	}

	private static Set<String> topLevelKeys(Path project) throws Exception {
		Set<String> keys = new HashSet<>();
		Json.parseJsonWithByteOrderMark(Files.readString(project)).propertyNames()
				.forEach(keys::add);
		return keys;
	}

	@Test
	@DisplayName("opens a real-project copy, saves it unchanged and preserves unknown top-level keys")
	void savesRealProjectCopy(@TempDir Path directory) throws Exception {
		Path project = copyFixture(directory, "Flow copy");
		Set<String> before = topLevelKeys(project);
		try (EditorSession session = EditorSession.open(project)) {
			assertEquals("Flow copy", session.displayName());
			assertFalse(session.dirty());
			session.save();
			assertFalse(session.dirty());
		}
		assertEquals(before, topLevelKeys(project));
		assertTrue(ProjectDocument.load(project).editor() != null);
	}

	@Test
	@DisplayName("saves an edit into the real project format and reloads it")
	void savesEdit(@TempDir Path directory) throws Exception {
		Path project = copyFixture(directory, "Flow copy");
		int baseline = ProjectDocument.load(project).editor().zoomRegions().size();
		try (EditorSession session = EditorSession.open(project)) {
			session.store().apply(new ZoomCommands.Add(1000, 2000));
			assertTrue(session.dirty());
			session.save();
			assertFalse(session.dirty());
		}
		assertEquals(baseline + 1, ProjectDocument.load(project).editor().zoomRegions().size());
	}

	@Test
	@DisplayName("save-as creates a new project file and continues there")
	void savesAsCopy(@TempDir Path directory) throws Exception {
		Path project = copyFixture(directory, "Flow copy");
		Path target = directory.resolve("Flow copy renamed.gravaai");
		int baseline = ProjectDocument.load(project).editor().zoomRegions().size();
		try (EditorSession session = EditorSession.open(project)) {
			session.store().apply(new ZoomCommands.Add(500, 1200));
			session.saveAs(target);
			assertEquals(target, session.projectPath());
			assertFalse(session.dirty());
		}
		assertTrue(Files.isRegularFile(target));
		assertEquals(baseline + 1, ProjectDocument.load(target).editor().zoomRegions().size());
	}

	@Test
	@DisplayName("renames the project file via the library service and keeps saving to it")
	void renamesProject(@TempDir Path directory) throws Exception {
		Path project = copyFixture(directory, "Flow copy");
		try (EditorSession session = EditorSession.open(project)) {
			Path renamed = RenameLibraryProject.renameLibraryProject(project, "Renamed flow",
					List.of(project),
					path -> List.of(ProjectManager.getProjectThumbnailPath(path)));
			session.relocate(renamed);
			session.store().apply(new ZoomCommands.Add(2000, 3000));
			session.save();
			assertEquals("Renamed flow", session.displayName());
			assertNotEquals(project, session.projectPath());
			assertFalse(Files.exists(project));
		}
		assertTrue(Files.isRegularFile(directory.resolve("Renamed flow.gravaai")));
	}
}
