package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

/** Where on the timeline eye contact applies, and at what angle. */
class EyeContactSettingsTest {

	private static ProjectEditorState editor(String json) {
		return ProjectPersistence.normalizeProjectEditor(Json.parseJsonWithByteOrderMark(json));
	}

	@Test
	@DisplayName("the project switch and angle apply outside Camera layout overrides")
	void projectSetting() {
		ProjectEditorState off = editor("""
				{"webcam":{"enabled":true}}""");
		assertEquals(0, ProjectEditorInputs.eyeContactAngleAt(off, 1000));
		assertFalse(ProjectEditorInputs.eyeContactWanted(off));

		ProjectEditorState on = editor("""
				{"webcam":{"enabled":true,"eyeContact":true,"eyeContactAngle":18}}""");
		assertEquals(18, ProjectEditorInputs.eyeContactAngleAt(on, 1000));
		assertTrue(ProjectEditorInputs.eyeContactWanted(on));

		ProjectEditorState hidden = editor("""
				{"webcam":{"enabled":false,"eyeContact":true}}""");
		assertEquals(0, ProjectEditorInputs.eyeContactAngleAt(hidden, 1000));
		assertFalse(ProjectEditorInputs.eyeContactWanted(hidden));
	}

	@Test
	@DisplayName("a segment override switches eye contact off or on for its span only")
	void segmentOverride() {
		ProjectEditorState editor = editor("""
				{"webcam":{"enabled":true,"eyeContact":true,"eyeContactAngle":15},
				 "cameraLayoutSegments":[
					{"id":"a","startMs":2000,"endMs":4000,"layout":"top-left","style":{"eyeContact":0}},
					{"id":"b","startMs":6000,"endMs":8000,"layout":"top-left","style":{"eyeContact":22}}]}""");
		assertEquals(15, ProjectEditorInputs.eyeContactAngleAt(editor, 1000));
		assertEquals(0, ProjectEditorInputs.eyeContactAngleAt(editor, 3000));
		assertEquals(15, ProjectEditorInputs.eyeContactAngleAt(editor, 5000));
		assertEquals(22, ProjectEditorInputs.eyeContactAngleAt(editor, 7000));

		ProjectEditorState segmentOnly = editor("""
				{"webcam":{"enabled":true},
				 "cameraLayoutSegments":[
					{"id":"b","startMs":6000,"endMs":8000,"layout":"camera-only","style":{"eyeContact":40}}]}""");
		assertTrue(ProjectEditorInputs.eyeContactWanted(segmentOnly));
		assertEquals(0, ProjectEditorInputs.eyeContactAngleAt(segmentOnly, 1000));
		// Clamped to the maximum lift.
		assertEquals(25, ProjectEditorInputs.eyeContactAngleAt(segmentOnly, 7000));
	}
}
