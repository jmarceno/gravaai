package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.humbleui.types.Rect;

/**
 * Port of the motion-blur routing cases from
 * {@code src/components/video-editor/videoPlayback/zoomTransform.test.ts} (plan §8: same cases
 * and expected values). The Pixi-filter assertions become assertions on the returned
 * {@link MotionBlur.Effect} shader parameters.
 */
class MotionBlurTest {

	private static ZoomTransform.AppliedTransform transform(RenderTargetSize stageSize,
			Rect baseMask, double zoomScale, double focusX, double focusY) {
		return ZoomTransform.computeZoomTransform(stageSize, baseMask, zoomScale, 1, focusX,
				focusY);
	}

	@Test
	@DisplayName("keeps pure translation on the directional blur path")
	void pureTranslationUsesDirectionalPath() {
		ZoomTransform.MotionBlurState state = ZoomTransform.createMotionBlurState();
		RenderTargetSize stageSize = new RenderTargetSize(1280, 720);
		Rect baseMask = Rect.makeXYWH(80, 60, 1120, 600);
		ZoomTransform.AppliedTransform first = new ZoomTransform.AppliedTransform(1, 0, 0);
		ZoomTransform.AppliedTransform second = new ZoomTransform.AppliedTransform(1, 96, 24);

		MotionBlur.update(state, baseMask, first, stageSize, true, 1, MotionBlur.Tuning.DEFAULT,
				1000.0, null, false);
		MotionBlur.Effect effect = MotionBlur.update(state, baseMask, second, stageSize, true, 1,
				MotionBlur.Tuning.DEFAULT, 1016.0, null, false);

		assertTrue(Math.hypot(effect.velocityX(), effect.velocityY()) > 0);
		assertEquals(0.0, effect.zoomStrength());
		assertEquals(MotionBlur.Path.DIRECTIONAL, effect.path());
	}

	@Test
	@DisplayName("infers an off-center zoom origin for radial blur")
	void offCenterZoomUsesRadialPath() {
		ZoomTransform.MotionBlurState state = ZoomTransform.createMotionBlurState();
		RenderTargetSize stageSize = new RenderTargetSize(1000, 800);
		Rect baseMask = Rect.makeXYWH(100, 100, 800, 600);
		double focusX = 0.2;
		double focusY = 0.7;

		MotionBlur.update(state, baseMask, transform(stageSize, baseMask, 1, focusX, focusY),
				stageSize, true, 1, MotionBlur.Tuning.DEFAULT, 1000.0, null, false);
		MotionBlur.Effect effect = MotionBlur.update(state, baseMask,
				transform(stageSize, baseMask, 1.7, focusX, focusY), stageSize, true, 1,
				MotionBlur.Tuning.DEFAULT, 1016.0, null, false);

		assertTrue(effect.zoomStrength() > 0);
		assertEquals(0.0, Math.hypot(effect.velocityX(), effect.velocityY()));
		assertEquals(stageSize.width() / 2.0, effect.zoomCenterX(), 0.5);
		assertEquals(stageSize.height() / 2.0, effect.zoomCenterY(), 0.5);
		assertEquals(-1.0, effect.zoomRadius());
		assertEquals(MotionBlur.Path.ZOOM, effect.path());
	}

	@Test
	@DisplayName("keeps a zoom on the radial path even when its center moves farther than its size")
	void zoomWithLargeTranslationStaysRadial() {
		ZoomTransform.MotionBlurState state = ZoomTransform.createMotionBlurState();
		RenderTargetSize stageSize = new RenderTargetSize(1280, 720);
		Rect baseMask = Rect.makeXYWH(80, 60, 1120, 600);

		MotionBlur.update(state, baseMask, new ZoomTransform.AppliedTransform(1, 0, 0), stageSize,
				true, 1, MotionBlur.Tuning.DEFAULT, 1000.0, null, false);
		MotionBlur.Effect effect = MotionBlur.update(state, baseMask,
				new ZoomTransform.AppliedTransform(1.01, 120, 0), stageSize, true, 1,
				MotionBlur.Tuning.DEFAULT, 1016.0, null, false);

		assertTrue(effect.zoomStrength() > 0);
		assertEquals(0.0, Math.hypot(effect.velocityX(), effect.velocityY()));
		assertEquals(0.0, effect.zoomInnerRadius());
		assertEquals(MotionBlur.Path.ZOOM, effect.path());
	}

	@Test
	@DisplayName("reconstructs blur without playback or prior render history and remains stable")
	void pausedFrameReconstructionIsStable() {
		ZoomTransform.MotionBlurState state = ZoomTransform.createMotionBlurState();
		RenderTargetSize stageSize = new RenderTargetSize(1000, 800);
		Rect baseMask = Rect.makeXYWH(0, 0, 1000, 800);
		MotionBlur.MotionSample sample = new MotionBlur.MotionSample(
				new ZoomTransform.AppliedTransform(1, 0, 0), 1000.0 / 60);

		MotionBlur.Effect first = MotionBlur.update(state, baseMask,
				new ZoomTransform.AppliedTransform(1, 10, 0), stageSize, false, 1,
				MotionBlur.Tuning.DEFAULT, null, sample, true);
		assertTrue(first.velocityX() > 0);

		MotionBlur.Effect second = MotionBlur.update(state, baseMask,
				new ZoomTransform.AppliedTransform(1, 10, 0), stageSize, false, 1,
				MotionBlur.Tuning.DEFAULT, null, sample, true);
		assertEquals(first.velocityX(), second.velocityX(), 1e-12);
		assertEquals(first.velocityY(), second.velocityY(), 1e-12);

		MotionBlur.Effect zoom = MotionBlur.update(state, baseMask,
				new ZoomTransform.AppliedTransform(1.01, 0, 0), stageSize, false, 1,
				MotionBlur.Tuning.DEFAULT, null, sample, true);
		assertTrue(zoom.zoomStrength() > 0);
		assertEquals(0.0, zoom.velocityX());

		MotionBlur.Effect reset = MotionBlur.update(state, baseMask,
				new ZoomTransform.AppliedTransform(1, 10, 0), stageSize, false, 1,
				MotionBlur.Tuning.DEFAULT, null, null, true);
		assertEquals(0.0, reset.zoomStrength());
		assertEquals(0.0, reset.velocityX());
		assertEquals(MotionBlur.Path.NONE, reset.path());
	}

	@Test
	@DisplayName("the transform quad corners follow the camera transform")
	void transformQuadGeometry() {
		Rect baseMask = Rect.makeXYWH(10, 20, 100, 50);
		MotionBlur.Quad quad = MotionBlur.computeTransformQuad(baseMask,
				new ZoomTransform.AppliedTransform(2, 5, 7));
		assertEquals(new MotionBlur.Point(25, 47), quad.corners().get(0));
		assertEquals(new MotionBlur.Point(225, 47), quad.corners().get(1));
		assertEquals(new MotionBlur.Point(225, 147), quad.corners().get(2));
		assertEquals(new MotionBlur.Point(25, 147), quad.corners().get(3));
		assertEquals(125.0, quad.center().x(), 1e-9);
		assertEquals(97.0, quad.center().y(), 1e-9);
		assertEquals(Math.hypot(200, 100), quad.diagonal(), 1e-9);
	}

	@Test
	@DisplayName("zero blur amount produces no effect")
	void zeroAmountProducesNoEffect() {
		ZoomTransform.MotionBlurState state = ZoomTransform.createMotionBlurState();
		MotionBlur.Effect effect = MotionBlur.update(state, Rect.makeXYWH(0, 0, 100, 100),
				new ZoomTransform.AppliedTransform(1, 0, 0), new RenderTargetSize(100, 100),
				true, 0, MotionBlur.Tuning.DEFAULT, 1000.0, null, false);
		assertEquals(MotionBlur.Path.NONE, effect.path());
	}
}
