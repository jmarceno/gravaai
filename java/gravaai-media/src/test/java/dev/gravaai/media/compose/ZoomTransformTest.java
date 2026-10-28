package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.humbleui.types.Rect;

/**
 * Tests for the pure zoom transform geometry. The first case is the ported
 * {@code zoomTransform.test.ts} case; the transform cases pin the exact formula (the TS file
 * only exercises it indirectly through the Pixi-filter routing tests, which move to stage 5
 * with the SkSL motion blur).
 */
class ZoomTransformTest {

	@Test
	@DisplayName("normalizes cursor and camera motion to the same 60fps baseline")
	void directionalBlurFpsNormalisation() {
		ZoomTransform.DirectionalMotionBlur at60Fps = ZoomTransform.computeDirectionalMotionBlur(
				12, 0, 0.5, 1.0 / 60);
		ZoomTransform.DirectionalMotionBlur at30Fps = ZoomTransform.computeDirectionalMotionBlur(
				12, 0, 0.5, 1.0 / 30);
		assertEquals(6.0, at60Fps.velocityX(), 1e-9);
		assertEquals(0.0, at60Fps.velocityY(), 1e-9);
		assertEquals(3.0, at30Fps.velocityX(), 1e-9);
		assertEquals(13, at60Fps.kernelSize());
		assertEquals(-6.0 / 32, at60Fps.offset(), 1e-6);
	}

	@Test
	@DisplayName("a negligible delta produces no directional blur")
	void negligibleDeltaIsInactive() {
		ZoomTransform.DirectionalMotionBlur blur = ZoomTransform.computeDirectionalMotionBlur(
				0.0001, 0, 0.5, 1.0 / 60);
		assertEquals(0.0, blur.magnitude());
		assertEquals(0.0, blur.offset());
		assertEquals(0.0, blur.velocityX());
	}

	@Test
	@DisplayName("zoom progress 0 is the identity transform")
	void zoomTransformAtZeroProgress() {
		ZoomTransform.AppliedTransform transform = ZoomTransform.computeZoomTransform(
				new RenderTargetSize(1000, 800), Rect.makeXYWH(100, 100, 800, 600), 1.7, 0, 0.2,
				0.7);
		assertEquals(1.0, transform.scale(), 1e-9);
		assertEquals(0.0, transform.x(), 1e-9);
		assertEquals(0.0, transform.y(), 1e-9);
	}

	@Test
	@DisplayName("zoom progress 1 centres the focus point on the stage")
	void zoomTransformAtFullProgress() {
		ZoomTransform.AppliedTransform transform = ZoomTransform.computeZoomTransform(
				new RenderTargetSize(1000, 800), Rect.makeXYWH(100, 100, 800, 600), 1.7, 1, 0.2,
				0.7);
		// focus in stage px: (100 + 0.2 * 800, 100 + 0.7 * 600) = (260, 520)
		// final x/y = stage centre - focus * zoomScale: (500 - 260 * 1.7, 400 - 520 * 1.7)
		assertEquals(1.7, transform.scale(), 1e-9);
		assertEquals(58.0, transform.x(), 1e-9);
		assertEquals(-484.0, transform.y(), 1e-9);
	}

	@Test
	@DisplayName("computeFocusFromTransform inverts computeZoomTransform")
	void focusFromTransformRoundTrip() {
		RenderTargetSize stageSize = new RenderTargetSize(1280, 720);
		Rect baseMask = Rect.makeXYWH(80, 60, 1120, 600);
		ZoomTransform.AppliedTransform transform = ZoomTransform.computeZoomTransform(stageSize,
				baseMask, 1.8, 1, 0.35, 0.62);
		ZoomFocus focus = ZoomTransform.computeFocusFromTransform(stageSize, baseMask, 1.8,
				transform.x(), transform.y());
		assertEquals(0.35, focus.cx(), 1e-9);
		assertEquals(0.62, focus.cy(), 1e-9);
	}

	@Test
	@DisplayName("degenerate geometry returns the identity transform")
	void degenerateGeometryIsIdentity() {
		ZoomTransform.AppliedTransform transform = ZoomTransform.computeZoomTransform(
				new RenderTargetSize(1000, 800), Rect.makeXYWH(0, 0, 0, 600), 1.7, 1, 0.5, 0.5);
		assertEquals(1.0, transform.scale(), 1e-9);
		assertEquals(0.0, transform.x(), 1e-9);
		assertEquals(0.0, transform.y(), 1e-9);
	}

	@Test
	@DisplayName("a fresh motion blur state is uninitialised at scale 1")
	void motionBlurStateStartsUninitialised() {
		ZoomTransform.MotionBlurState state = ZoomTransform.createMotionBlurState();
		assertEquals(1.0, state.prevCamScale, 1e-9);
		assertEquals(0.0, state.prevCamX, 1e-9);
		assertEquals(0.0, state.prevCamY, 1e-9);
		assertFalse(state.initialized);
	}
}
