package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.core.project.ProjectPersistence;

/** The Camera layout track as the compositor sees it, frame by frame. */
class CameraLayoutTrackTest {

	private static final ProjectEditorState EDITOR = ProjectPersistence.normalizeProjectEditor(
			Json.parseJsonWithByteOrderMark("""
					{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
					 "webcam":{"enabled":true,"positionPreset":"top-left","corner":"top-left"},
					 "cameraLayoutSegments":[
						{"id":"camera-1","startMs":0,"endMs":10000,"layout":"split-right"},
						{"id":"camera-2","startMs":10000,"endMs":20000,"layout":"bottom-right"},
						{"id":"camera-3","startMs":20000,"endMs":25000,"layout":"camera-only"}]}
					"""));

	private static WebcamSpec webcamAt(double timeMs) {
		return ProjectEditorInputs.webcamSpecAt(ProjectEditorInputs.webcamSpec(EDITOR), EDITOR,
				timeMs);
	}

	@Test
	@DisplayName("each segment moves the camera to its preset; gaps keep the project layout")
	void webcamFollowsSegments() {
		assertEquals("split-right", webcamAt(5_000).positionPreset());
		assertEquals("bottom-right", webcamAt(15_000).positionPreset());
		assertEquals("bottom-right", webcamAt(15_000).corner());
		assertEquals("top-left", webcamAt(27_000).positionPreset());
	}

	@Test
	@DisplayName("a camera-only segment fills the frame with the camera")
	void cameraOnlySegmentDrivesScene() {
		assertEquals(SceneSpec.FULL, ProjectEditorInputs.sceneSpec(EDITOR, 15_000));
		SceneSpec cameraOnly = ProjectEditorInputs.sceneSpec(EDITOR, 22_500);
		assertEquals(1.0, cameraOnly.cameraFill(), 1e-9);
		assertEquals(0.0, cameraOnly.screenOpacity(), 1e-9);
		assertEquals(SceneSpec.FULL, ProjectEditorInputs.sceneSpec(EDITOR, 28_000));
	}
}
