package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.project.ProjectDocument;

/** Task-21: the app editor session loads, edits and saves a project through the canonical path. */
class EditorSessionTest {

	private static final String MINIMAL_PROJECT = """
			{
			  "version": 2,
			  "projectId": "11111111-2222-3333-4444-555555555555",
			  "videoPath": "missing-recording.mp4",
			  "createdAt": 1700000000000,
			  "updatedAt": 1700000000000,
			  "projectName": "Session fixture",
			  "editor": {
			    "wallpaper": "/wallpapers/tahoe-light.jpg",
			    "shadowIntensity": 0.67,
			    "backgroundBlur": 0,
			    "aspectRatio": "16:9",
			    "borderRadius": 0,
			    "padding": {"top": 20, "bottom": 20, "left": 20, "right": 20, "linked": true},
			    "clipRegions": [{"id": "clip-1", "startMs": 0, "endMs": 6000, "speed": 1}]
			  }
			}
			""";

	private static Path writeProject(Path directory, String name) throws Exception {
		Path path = directory.resolve(name + ".gravaai");
		Files.writeString(path, MINIMAL_PROJECT);
		return path;
	}

	@Test
	@DisplayName("opens a project and reports its name, state and timeline range")
	void opensProject(@TempDir Path directory) throws Exception {
		Path path = writeProject(directory, "Session fixture");
		try (EditorSession session = EditorSession.open(path)) {
			assertEquals("Session fixture", session.displayName());
			assertEquals(path, session.projectPath());
			assertFalse(session.dirty());
			assertNotNull(session.store().state());
			assertEquals(6000, session.timelineDataSource().totalDurationMs());
			assertEquals(1, session.store().state().clipRegions().size());
			assertEquals(0.67, session.store().state().shadowIntensity(), 0.001);
		}
	}

	@Test
	@DisplayName("tracks dirty state and saves editor changes while preserving unknown keys")
	void savesChanges(@TempDir Path directory) throws Exception {
		Path path = writeProject(directory, "Session fixture");
		try (EditorSession session = EditorSession.open(path)) {
			session.store().apply(new dev.gravaai.core.editor.ZoomCommands.Add(1000, 2500));
			assertTrue(session.dirty());
			session.save();
			assertFalse(session.dirty());
		}
		ProjectDocument reloaded = ProjectDocument.load(path);
		assertEquals(1, reloaded.editor().zoomRegions().size());
		assertEquals(1000, reloaded.editor().zoomRegions().get(0).startMs(), 0.001);
		assertEquals("11111111-2222-3333-4444-555555555555", reloaded.projectId());
	}

	@Test
	@DisplayName("appearance-only edits leave the playback clip sequence unchanged")
	void appearanceEditsDoNotRestartPlaybackClips(@TempDir Path directory) throws Exception {
		Path path = writeProject(directory, "Session fixture");
		try (EditorSession session = EditorSession.open(path)) {
			var before = session.store().state().clipRegions();
			boolean changed = dev.gravaai.ui.editor.EditorMutations.update(session.store(),
					json -> {
						json.put("wallpaper", "#101820");
						json.put("backgroundBlur", 3.5);
					});
			assertTrue(changed, "the appearance edit must reach the store");
			assertFalse(EditorSession.clipSequenceChanged(before,
					session.store().state().clipRegions()),
				"wallpaper/blur edits must not trigger the playback audio reopen path");
			assertTrue(EditorSession.clipSequenceChanged(before,
					java.util.List.of()), "an actual clip-list edit must still be detected");
		}
	}

	@Test
	@DisplayName("saves as a new project file and continues there")
	void savesAs(@TempDir Path directory) throws Exception {
		Path path = writeProject(directory, "Session fixture");
		Path target = directory.resolve("Renamed fixture.gravaai");
		try (EditorSession session = EditorSession.open(path)) {
			session.store().apply(new dev.gravaai.core.editor.ZoomCommands.Add(500, 1500));
			session.saveAs(target);
			assertEquals(target, session.projectPath());
			assertFalse(session.dirty());
		}
		assertTrue(Files.isRegularFile(target));
		assertEquals(1, ProjectDocument.load(target).editor().zoomRegions().size());
	}
}
