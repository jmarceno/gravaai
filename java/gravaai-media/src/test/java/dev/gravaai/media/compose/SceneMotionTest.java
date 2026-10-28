package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of {@code sceneMotion.test.ts} (plan §8: same cases and expected values). */
class SceneMotionTest {

	private static final ZoomRegionSpec MANUAL_REGION = new ZoomRegionSpec("zoom", 0, 4000,
			ZoomDepth.D2, new ZoomFocus(0.7, 0.3), ZoomMode.MANUAL);

	@Test
	@DisplayName("returns the neutral camera when no zoom is active")
	void neutralWhenNoZoom() {
		SceneMotion.SceneZoomTarget target = SceneMotion.resolveSceneZoomTarget(
				SceneMotion.SceneZoomRequest.of(List.of(), 1000,
						CursorFollowCamera.createCursorFollowCameraState()));
		assertEquals(1.0, target.scale());
		assertEquals(new ZoomFocus(0.5, 0.5), target.focus());
		assertEquals(0.0, target.progress());
	}

	@Test
	@DisplayName("resolves the same manual target for every rendering backend")
	void manualTarget() {
		SceneMotion.SceneZoomTarget target = SceneMotion.resolveSceneZoomTarget(
				SceneMotion.SceneZoomRequest.of(List.of(MANUAL_REGION), 2000,
						CursorFollowCamera.createCursorFollowCameraState()));
		assertTrue(target.scale() > 1);
		// The scene evaluator clamps focus so the zoom never exposes the stage edge.
		assertEquals(2.0 / 3.0, target.focus().cx(), 1e-9);
		assertEquals(1.0 / 3.0, target.focus().cy(), 1e-9);
		assertEquals(1.0, target.progress());
	}

	@ParameterizedTest(name = "preserves a plain pause with classic mode {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("preserves a plain pause with classic mode")
	void preservesPlainPause(boolean zoomClassicMode) {
		assertEquals(SceneMotion.PreviewMotionMode.PRESERVE,
				SceneMotion.resolvePreviewMotionMode(false, false, false, zoomClassicMode));
	}

	@Test
	@DisplayName("snaps paused frames only for an intentional timeline seek")
	void snapsPausedFramesOnSeek() {
		assertEquals(SceneMotion.PreviewMotionMode.SNAP,
				SceneMotion.resolvePreviewMotionMode(false, false, true, false));
	}

	@Test
	@DisplayName("holds every visual sample, including blur and cursor state, while paused")
	void holdsWhilePaused() {
		assertFalse(SceneMotion.shouldComposePreviewFrame(
				SceneMotion.PreviewMotionMode.PRESERVE, false, true, false));
	}

	@Test
	@DisplayName("does not interpolate again at an unchanged playback timestamp")
	void noInterpolationAtUnchangedTimestamp() {
		assertFalse(SceneMotion.shouldComposePreviewFrame(
				SceneMotion.PreviewMotionMode.SPRING, false, false, false));
	}

	@Test
	@DisplayName("composes one exact frame when a seek requests it")
	void composesOnSeekRequest() {
		assertTrue(SceneMotion.shouldComposePreviewFrame(
				SceneMotion.PreviewMotionMode.SNAP, false, false, true));
	}

	@Test
	@DisplayName("holds the composed frame until seeking finishes, even with a pending refresh")
	void holdsUntilSeekingFinishes() {
		assertFalse(SceneMotion.shouldComposePreviewFrame(
				SceneMotion.PreviewMotionMode.SNAP, true, true, true));
		assertTrue(SceneMotion.shouldComposePreviewFrame(
				SceneMotion.PreviewMotionMode.SNAP, false, true, true));
	}
}
