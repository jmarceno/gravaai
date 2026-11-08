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
	@DisplayName("a block's margin override moves only that block's card")
	void blockMarginOverride() {
		ProjectEditorState editor = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
						 "webcam":{"enabled":true,"positionPreset":"bottom-right","margin":24},
						 "cameraLayoutSegments":[
							{"id":"camera-1","startMs":0,"endMs":10000,"layout":"bottom-right",
							 "style":{"margin":8}}]}
						"""));
		WebcamSpec base = ProjectEditorInputs.webcamSpec(editor);

		assertEquals(8, ProjectEditorInputs.webcamSpecAt(base, editor, 5_000).margin(), 1e-9);
		// The gap after the block is back on the project margin.
		assertEquals(24, ProjectEditorInputs.webcamSpecAt(base, editor, 12_000).margin(), 1e-9);
	}

	@Test
	@DisplayName("a smooth switch glides from the previous layout; style overrides apply")
	void smoothSwitchAndStyle() {
		ProjectEditorState editor = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
						 "webcam":{"enabled":true,"positionPreset":"top-left","size":40},
						 "cameraLayoutSegments":[
							{"id":"camera-1","startMs":0,"endMs":10000,"layout":"split-right"},
							{"id":"camera-2","startMs":10000,"endMs":20000,
							 "layout":"bottom-right","transition":"smooth","style":{"size":25}}]}
						"""));
		WebcamSpec base = ProjectEditorInputs.webcamSpec(editor);

		WebcamSpec midway = ProjectEditorInputs.webcamSpecAt(base, editor, 10_150);
		assertEquals("bottom-right", midway.positionPreset());
		assertEquals(25, midway.size(), 1e-9);
		assertEquals("split-right", midway.transition().orElseThrow().from().positionPreset());
		assertEquals(0.5, midway.transition().orElseThrow().progress(), 1e-9);

		assertEquals(java.util.Optional.empty(),
				ProjectEditorInputs.webcamSpecAt(base, editor, 10_400).transition());
		assertEquals(java.util.Optional.empty(),
				ProjectEditorInputs.webcamSpecAt(base, editor, 5_000).transition());
	}

	@Test
	@DisplayName("a smooth switch-out glides back to the project layout after the block ends")
	void smoothSwitchOut() {
		ProjectEditorState editor = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
						 "webcam":{"enabled":true,"positionPreset":"bottom-right","corner":"bottom-right"},
						 "cameraLayoutSegments":[
							{"id":"camera-1","startMs":0,"endMs":10000,"layout":"split-right",
							 "transitionOut":"smooth"}]}
						"""));
		WebcamSpec base = ProjectEditorInputs.webcamSpec(editor);

		// Halfway through the glide the camera sits between the split and the project bubble.
		WebcamSpec midway = ProjectEditorInputs.webcamSpecAt(base, editor, 10_150);
		assertEquals("bottom-right", midway.positionPreset());
		assertEquals("split-right", midway.transition().orElseThrow().from().positionPreset());
		assertEquals(0.5, midway.transition().orElseThrow().progress(), 1e-9);

		// The glide starts exactly at the block's end and is done after SMOOTH_MS.
		assertEquals("split-right",
				ProjectEditorInputs.webcamSpecAt(base, editor, 10_000).transition()
						.orElseThrow().from().positionPreset());
		assertEquals(0.0,
				ProjectEditorInputs.webcamSpecAt(base, editor, 10_000).transition()
						.orElseThrow().progress(), 1e-9);
		assertEquals(java.util.Optional.empty(),
				ProjectEditorInputs.webcamSpecAt(base, editor, 10_400).transition());
	}

	@Test
	@DisplayName("a cut switch-out never glides; a touching next block governs the boundary")
	void cutSwitchOutAndTouchingBlocks() {
		WebcamSpec base = ProjectEditorInputs.webcamSpec(EDITOR);
		// camera-2 touches camera-1's end, so camera-2's switch-in governs: no exit glide from
		// camera-1 even with a smooth switch-out, and camera-3's end is a camera-only exit.
		ProjectEditorState touching = ProjectPersistence.normalizeProjectEditor(
				Json.parseJsonWithByteOrderMark("""
						{"clipRegions":[{"id":"a","startMs":0,"endMs":30000,"speed":1}],
						 "webcam":{"enabled":true,"positionPreset":"top-left","corner":"top-left"},
						 "cameraLayoutSegments":[
							{"id":"camera-1","startMs":0,"endMs":10000,"layout":"split-right",
							 "transitionOut":"smooth"},
							{"id":"camera-2","startMs":10000,"endMs":20000,"layout":"bottom-right"},
							{"id":"camera-3","startMs":20000,"endMs":25000,"layout":"camera-only"}]}
						"""));
		WebcamSpec touchingBase = ProjectEditorInputs.webcamSpec(touching);
		assertEquals("bottom-right",
				ProjectEditorInputs.webcamSpecAt(touchingBase, touching, 10_150).positionPreset());
		assertEquals(java.util.Optional.empty(),
				ProjectEditorInputs.webcamSpecAt(touchingBase, touching, 10_150).transition());

		// The default switch-out is a cut: no glide in the gap after camera-2.
		assertEquals(java.util.Optional.empty(),
				ProjectEditorInputs.webcamSpecAt(base, EDITOR, 20_150).transition());
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
