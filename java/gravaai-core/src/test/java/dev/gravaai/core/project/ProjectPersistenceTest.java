package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.node.ObjectNode;

/** Tests for the persistence helpers of {@code projectPersistence.ts} ported so far. */
class ProjectPersistenceTest {

	private static ObjectNode editor() {
		return (ObjectNode) Json.parseJsonWithByteOrderMark("""
				{"wallpaper":"#ffffff","exportQuality":"high","mp4FrameRate":60,
				"whisperModelPath":"/tmp/model.bin","zoomMotionBlurTuning":{"panVelocityThreshold":0},
				"zoomRegions":[]}
				""");
	}

	@Test
	@DisplayName("creates version 2 project data with an optional project id")
	void createsProjectData() {
		ObjectNode data = ProjectPersistence.createProjectData("video.mp4", editor(), "project-1");
		assertEquals(2, data.get("version").intValue());
		assertEquals("project-1", data.get("projectId").asString());
		assertEquals("video.mp4", data.get("videoPath").asString());
		assertFalse(data.get("editor").has("exportQuality"));

		ObjectNode withoutId = ProjectPersistence.createProjectData("video.mp4", editor(), "  ");
		assertFalse(withoutId.has("projectId"));
	}

	@Test
	@DisplayName("strips app preferences but keeps project fields")
	void stripsUserPreferences() {
		ObjectNode stripped = ProjectPersistence.stripProjectUserPreferences(editor());
		assertFalse(stripped.has("exportQuality"));
		assertFalse(stripped.has("mp4FrameRate"));
		assertFalse(stripped.has("whisperModelPath"));
		assertEquals("#ffffff", stripped.get("wallpaper").asString());
		assertTrue(stripped.has("zoomMotionBlurTuning"));
	}

	@Test
	@DisplayName("strips the developer-only motion blur tuning")
	void stripsDevMotionBlurTuning() {
		ObjectNode stripped = ProjectPersistence.stripPersistedDevMotionBlurSettings(editor());
		assertFalse(stripped.has("zoomMotionBlurTuning"));
		assertTrue(stripped.has("wallpaper"));
		assertTrue(editor().has("zoomMotionBlurTuning"));
	}
}
