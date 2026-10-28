package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.ClipCameraLayout;
import dev.gravaai.core.project.ClipRegion;

/** Port of src/components/video-editor/sceneLayout.ts behaviour. */
class SceneSpecTest {

	private static ClipRegion clip(String id, long startMs, long endMs, ClipCameraLayout layout) {
		return new ClipRegion(id, startMs, endMs, null, null, null, 1, null, null, layout, null);
	}

	private static List<ClipRegion> clips() {
		return List.of(
				clip("a", 0, 1_000, ClipCameraLayout.DEFAULT),
				clip("b", 1_000, 2_000, ClipCameraLayout.HIDE_CAMERA),
				clip("c", 2_000, 3_000, ClipCameraLayout.CAMERA_ONLY));
	}

	@Test
	@DisplayName("default clips keep the normal overlay state")
	void defaultState() {
		SceneSpec state = SceneSpec.resolve(SceneSpec.spans(clips()), 500);
		assertEquals(1.0, state.cameraOpacity(), 1e-9);
		assertEquals(0.0, state.cameraFill(), 1e-9);
		assertEquals(1.0, state.screenOpacity(), 1e-9);
	}

	@Test
	@DisplayName("hide-camera hides the camera and keeps the screen")
	void hideCameraState() {
		SceneSpec state = SceneSpec.resolve(SceneSpec.spans(clips()), 1_500);
		assertEquals(0.0, state.cameraOpacity(), 1e-9);
		assertEquals(0.0, state.cameraFill(), 1e-9);
		assertEquals(1.0, state.screenOpacity(), 1e-9);
	}

	@Test
	@DisplayName("camera-only fills the frame with the camera and hides the screen")
	void cameraOnlyState() {
		SceneSpec state = SceneSpec.resolve(SceneSpec.spans(clips()), 2_500);
		assertEquals(1.0, state.cameraOpacity(), 1e-9);
		assertEquals(1.0, state.cameraFill(), 1e-9);
		assertEquals(0.0, state.screenOpacity(), 1e-9);
	}

	@Test
	@DisplayName("blends layout changes over 300 ms with smoothstep")
	void blendsTransitions() {
		SceneSpec atCut = SceneSpec.resolve(SceneSpec.spans(clips()), 1_000);
		assertEquals(0.5, atCut.cameraOpacity(), 0.02);
		SceneSpec early = SceneSpec.resolve(SceneSpec.spans(clips()), 900);
		assertTrue(early.cameraOpacity() > 0.8);
		SceneSpec late = SceneSpec.resolve(SceneSpec.spans(clips()), 1_100);
		assertTrue(late.cameraOpacity() < 0.2);
		SceneSpec intoCameraOnly = SceneSpec.resolve(SceneSpec.spans(clips()), 2_000);
		assertEquals(0.5, intoCameraOnly.cameraFill(), 0.02);
		assertEquals(0.5, intoCameraOnly.screenOpacity(), 0.02);
	}

	@Test
	@DisplayName("no clips means the default state")
	void emptyClips() {
		SceneSpec state = SceneSpec.resolve(SceneSpec.spans(List.of()), 0);
		assertEquals(SceneSpec.FULL, state);
	}
}
