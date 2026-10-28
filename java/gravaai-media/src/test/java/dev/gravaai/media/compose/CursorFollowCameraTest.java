package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code cursorFollowCamera.test.ts} plus cursor interpolation coverage. */
class CursorFollowCameraTest {

	private static List<CursorSample> samples(double... values) {
		// values: timeSeconds, cx, cy triplets
		var samples = new java.util.ArrayList<CursorSample>();
		for (int i = 0; i + 2 < values.length; i += 3) {
			samples.add(new CursorSample(values[i], values[i + 1], values[i + 2]));
		}
		return samples;
	}

	@Test
	@DisplayName("holds the camera while the cursor stays inside the safe zone")
	void holdsInsideSafeZone() {
		CursorFollowCamera.CursorFollowCameraState state =
				CursorFollowCamera.createCursorFollowCameraState();
		List<CursorSample> cursorSamples = samples(0, 0.5, 0.5, 0.1, 0.6, 0.58);
		ZoomFocus initialFocus = CursorFollowCamera.computeCursorFollowFocus(state, cursorSamples,
				0, 2, 1, new ZoomFocus(0.5, 0.5),
				new CursorFollowCamera.CursorFollowConfig(0.25));
		ZoomFocus heldFocus = CursorFollowCamera.computeCursorFollowFocus(state, cursorSamples,
				100, 2, 1, new ZoomFocus(0.5, 0.5),
				new CursorFollowCamera.CursorFollowConfig(0.25));
		assertEquals(new ZoomFocus(0.5, 0.5), initialFocus);
		assertEquals(initialFocus, heldFocus);
	}

	@Test
	@DisplayName("recenters the cursor after it leaves the safe zone")
	void recentersOutsideSafeZone() {
		CursorFollowCamera.CursorFollowCameraState state =
				CursorFollowCamera.createCursorFollowCameraState();
		List<CursorSample> cursorSamples = samples(0, 0.5, 0.5, 0.1, 0.7, 0.5, 0.2, 0.72, 0.5);
		CursorFollowCamera.computeCursorFollowFocus(state, cursorSamples, 0, 2, 1,
				new ZoomFocus(0.5, 0.5), new CursorFollowCamera.CursorFollowConfig(0.25));
		ZoomFocus firstShift = CursorFollowCamera.computeCursorFollowFocus(state, cursorSamples,
				100, 2, 1, new ZoomFocus(0.5, 0.5),
				new CursorFollowCamera.CursorFollowConfig(0.25));
		ZoomFocus secondShift = CursorFollowCamera.computeCursorFollowFocus(state, cursorSamples,
				200, 2, 1, new ZoomFocus(0.5, 0.5),
				new CursorFollowCamera.CursorFollowConfig(0.25));
		assertEquals(0.7, firstShift.cx(), 1e-6);
		assertEquals(0.5, firstShift.cy(), 1e-6);
		assertEquals(0.7, secondShift.cx(), 1e-6);
		assertEquals(0.5, secondShift.cy(), 1e-6);
	}

	@Test
	@DisplayName("clamps the camera when the cursor pushes past the stage edge")
	void clampsAtStageEdge() {
		CursorFollowCamera.CursorFollowCameraState state =
				CursorFollowCamera.createCursorFollowCameraState();
		List<CursorSample> cursorSamples = samples(0, 0.5, 0.5, 0.1, 1, 1);
		CursorFollowCamera.computeCursorFollowFocus(state, cursorSamples, 0, 2, 1,
				new ZoomFocus(0.5, 0.5), new CursorFollowCamera.CursorFollowConfig(0.25));
		ZoomFocus clampedFocus = CursorFollowCamera.computeCursorFollowFocus(state, cursorSamples,
				100, 2, 1, new ZoomFocus(0.5, 0.5),
				new CursorFollowCamera.CursorFollowConfig(0.25));
		assertEquals(new ZoomFocus(0.75, 0.75), clampedFocus);
	}

	@Test
	@DisplayName("starts auto zooms from the saved region focus before following the cursor")
	void startsFromRegionFocus() {
		CursorFollowCamera.CursorFollowCameraState state =
				CursorFollowCamera.createCursorFollowCameraState();
		List<CursorSample> cursorSamples = samples(0, 0.8, 0.2, 0.1, 0.82, 0.22);
		ZoomFocus initialFocus = CursorFollowCamera.computeCursorFollowFocus(state, cursorSamples,
				0, 2, 1, new ZoomFocus(0.3, 0.7),
				new CursorFollowCamera.CursorFollowConfig(0.25));
		assertEquals(new ZoomFocus(0.3, 0.7), initialFocus);
	}

	@Test
	@DisplayName("cursor interpolation handles empty, before-first, after-last and midpoint")
	void cursorInterpolation() {
		assertNull(CursorPosition.interpolateCursorPosition(List.of(), 100));
		List<CursorSample> cursorSamples = samples(0, 0.1, 0.2, 1, 0.5, 0.6);
		CursorSample before = CursorPosition.interpolateCursorPosition(cursorSamples, -100);
		assertEquals(0.1, before.x(), 1e-9);
		CursorSample after = CursorPosition.interpolateCursorPosition(cursorSamples, 5000);
		assertEquals(0.5, after.x(), 1e-9);
		CursorSample middle = CursorPosition.interpolateCursorPosition(cursorSamples, 500);
		assertEquals(0.3, middle.x(), 1e-9);
		assertEquals(0.4, middle.y(), 1e-9);
	}
}
