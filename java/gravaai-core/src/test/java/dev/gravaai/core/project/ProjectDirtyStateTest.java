package dev.gravaai.core.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Port of {@code src/components/video-editor/projectDirtyState.test.ts}. */
class ProjectDirtyStateTest {

	private static final String PROJECT_JSON = """
			{"version":1,"projectId":"project-1","videoPath":"file:///recording.mp4","editor":{
			"wallpaper":"#ffffff","zoomRegions":[],"trimRegions":[],"clipRegions":[],"speedRegions":[],
			"annotationRegions":[],"audioRegions":[],"autoCaptions":[]}}
			""";

	private static ObjectNode createProjectData() {
		return (ObjectNode) Json.parseJsonWithByteOrderMark(PROJECT_JSON);
	}

	private static ObjectNode withEditorValue(ObjectNode project, String key, String json) {
		ObjectNode copy = project.deepCopy();
		((ObjectNode) copy.get("editor")).set(key, Json.parseJsonWithByteOrderMark(json));
		return copy;
	}

	@Test
	@DisplayName("does not report changes before a project snapshot exists")
	void noChangesBeforeSnapshot() {
		assertFalse(ProjectDirtyState.hasUnsavedProjectChanges(null, createProjectData()));
	}

	@Test
	@DisplayName("reports changes when there is no saved baseline")
	void reportsChangesWithoutBaseline() {
		assertTrue(ProjectDirtyState.hasUnsavedProjectChanges(createProjectData(), null));
	}

	@Test
	@DisplayName("treats deeply equal snapshots as unchanged")
	void treatsEqualSnapshotsAsUnchanged() {
		assertFalse(ProjectDirtyState.hasUnsavedProjectChanges(createProjectData(), createProjectData()));
	}

	@Test
	@DisplayName("detects nested editor changes")
	void detectsNestedEditorChanges() {
		ObjectNode current = withEditorValue(createProjectData(), "zoomRegions", """
				[{"id":"zoom-1","startMs":100,"endMs":500,"focus":{"cx":0.4,"cy":0.6},"depth":2}]
				""");
		assertTrue(ProjectDirtyState.hasUnsavedProjectChanges(current, createProjectData()));
	}

	@Test
	@DisplayName("detects array length changes")
	void detectsArrayLengthChanges() {
		ObjectNode current = withEditorValue(createProjectData(), "autoCaptions", """
				[{"id":"caption-1","startMs":0,"endMs":1000,"text":"hello"}]
				""");
		assertTrue(ProjectDirtyState.hasUnsavedProjectChanges(current, createProjectData()));
	}

	@Test
	@DisplayName("ignores transient webcam media attachment changes")
	void ignoresTransientWebcamChanges() {
		ObjectNode saved = withEditorValue(createProjectData(), "webcam",
				"{\"enabled\":false,\"sourcePath\":null,\"timeOffsetMs\":0,\"size\":28}");
		ObjectNode current = withEditorValue(createProjectData(), "webcam",
				"{\"enabled\":true,\"sourcePath\":\"/Users/test/webcam.mp4\",\"timeOffsetMs\":125,\"size\":28}");
		assertFalse(ProjectDirtyState.hasUnsavedProjectChanges(current, saved));
	}

	@Test
	@DisplayName("detects persistent webcam presentation changes")
	void detectsPersistentWebcamChanges() {
		ObjectNode saved = withEditorValue(createProjectData(), "webcam",
				"{\"enabled\":true,\"sourcePath\":\"/Users/test/webcam.mp4\",\"timeOffsetMs\":0,\"size\":28}");
		ObjectNode current = withEditorValue(createProjectData(), "webcam",
				"{\"enabled\":true,\"sourcePath\":\"/Users/test/webcam.mp4\",\"timeOffsetMs\":0,\"size\":36}");
		assertTrue(ProjectDirtyState.hasUnsavedProjectChanges(current, saved));
	}

	@Test
	@DisplayName("does not mark legacy export preferences as project edits")
	void ignoresLegacyExportPreferences() {
		ObjectNode original = createProjectData();
		ObjectNode editor = ((ObjectNode) original.deepCopy().get("editor"));
		editor.put("exportQuality", "high");
		editor.put("mp4FrameRate", 60);
		ObjectNode legacy = original.deepCopy();
		legacy.set("editor", editor);

		assertFalse(ProjectDirtyState.hasUnsavedProjectChanges(original, legacy));
		assertFalse(ProjectDirtyState.hasUnsavedProjectChanges(legacy, original));

		ObjectNode changed = legacy.deepCopy();
		((ObjectNode) changed.get("editor")).put("wallpaper", "#000000");
		assertTrue(ProjectDirtyState.hasUnsavedProjectChanges(changed, original));
	}

	private static JsonNode parse(String json) {
		return Json.parseJsonWithByteOrderMark(json);
	}
}
