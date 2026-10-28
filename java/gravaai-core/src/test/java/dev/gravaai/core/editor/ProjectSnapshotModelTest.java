package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

/** Port of the pure parts of {@code project/useProjectSnapshotModel.ts}. */
class ProjectSnapshotModelTest {

	@Test
	@DisplayName("builds the persisted editor snapshot without the dev-only motion-blur tuning")
	void buildsPersistedEditorState() {
		ProjectEditorState state = ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark("""
				{"wallpaper":"#123456","zoomMotionBlurTuning":{"panVelocityThreshold":40}}
				"""));

		var persisted = ProjectSnapshotModel.buildPersistedEditorState(state);

		assertEquals("#123456", persisted.get("wallpaper").asText());
		assertFalse(persisted.has("zoomMotionBlurTuning"));
		assertTrue(persisted.has("zoomRegions"));
	}

	@Test
	@DisplayName("uses the explicit source path before converting the project path")
	void resolvesCurrentSourcePath() {
		assertEquals("/explicit.mp4",
				ProjectSnapshotModel.currentSourcePath("/explicit.mp4", "file:///other.mp4"));
		assertEquals("/tmp/My Clip.mp4",
				ProjectSnapshotModel.currentSourcePath(null, "file:///tmp/My%20Clip.mp4"));
		assertEquals(null, ProjectSnapshotModel.currentSourcePath(null, null));
	}

	@Test
	@DisplayName("shows the project file name without its extension")
	void derivesProjectDisplayName() {
		assertEquals("My Project",
				ProjectSnapshotModel.projectDisplayName("/home/user/My Project.gravaai", "Untitled Project"));
		assertEquals("foo", ProjectSnapshotModel.projectDisplayName("/x/foo.test.gravaai", "Untitled Project"));
		assertEquals("record", ProjectSnapshotModel.projectDisplayName("C:\\x\\record.webm", "Untitled Project"));
		assertEquals("Untitled Project", ProjectSnapshotModel.projectDisplayName(null, "Untitled Project"));
		assertEquals("Untitled", ProjectSnapshotModel.projectDisplayName("", "Untitled"));
	}
}
