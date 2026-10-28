package dev.gravaai.ui.editor.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.AnnotationPosition;
import dev.gravaai.core.project.AnnotationSize;
import dev.gravaai.media.compose.CameraGeometry;

/** Coordinate math of the preview annotation overlay (port of {@code AnnotationOverlay.tsx}). */
class AnnotationOverlayMathTest {

	private static final CameraGeometry IDENTITY = new CameraGeometry(0, 0, 640, 360, 1, 0, 0, 640, 360);

	@DisplayName("percent position/size map through the recording rect and identity camera")
	@Test
	void identityTransform() {
		AnnotationOverlayMath.Rect rect = AnnotationOverlayMath.overlayRect(
				new AnnotationPosition(50, 50), new AnnotationSize(30, 20), IDENTITY, 640, 360);
		assertEquals(320, rect.x(), 1e-9);
		assertEquals(180, rect.y(), 1e-9);
		assertEquals(192, rect.width(), 1e-9);
		assertEquals(72, rect.height(), 1e-9);
	}

	@DisplayName("camera zoom scale and translation move the annotation like React")
	@Test
	void cameraTransform() {
		CameraGeometry zoomed = new CameraGeometry(0, 0, 640, 360, 2, -100, -50, 640, 360);
		AnnotationOverlayMath.Rect rect = AnnotationOverlayMath.overlayRect(
				new AnnotationPosition(50, 50), new AnnotationSize(30, 20), zoomed, 640, 360);
		assertEquals(540, rect.x(), 1e-9);
		assertEquals(310, rect.y(), 1e-9);
		assertEquals(384, rect.width(), 1e-9);
		assertEquals(144, rect.height(), 1e-9);
	}

	@DisplayName("padding offsets the recording rect before the camera transform")
	@Test
	void paddedRecordingRect() {
		CameraGeometry padded = new CameraGeometry(80, 45, 480, 270, 1, 0, 0, 640, 360);
		AnnotationOverlayMath.Rect rect = AnnotationOverlayMath.overlayRect(
				new AnnotationPosition(0, 0), new AnnotationSize(10, 10), padded, 640, 360);
		assertEquals(80, rect.x(), 1e-9);
		assertEquals(45, rect.y(), 1e-9);
		assertEquals(48, rect.width(), 1e-9);
		assertEquals(27, rect.height(), 1e-9);
	}

	@DisplayName("panel pixels scale from the compositor output size")
	@Test
	void panelScale() {
		// Renderer capped at 640x360 while the panel shows 1280x720: everything doubles.
		AnnotationOverlayMath.Rect rect = AnnotationOverlayMath.overlayRect(
				new AnnotationPosition(50, 50), new AnnotationSize(30, 20), IDENTITY, 1280, 720);
		assertEquals(640, rect.x(), 1e-9);
		assertEquals(360, rect.y(), 1e-9);
		assertEquals(384, rect.width(), 1e-9);
		assertEquals(144, rect.height(), 1e-9);
	}

	@DisplayName("position and size round-trip through panel pixels")
	@Test
	void roundTrip() {
		CameraGeometry zoomed = new CameraGeometry(80, 45, 480, 270, 1.5, -20, -10, 640, 360);
		AnnotationPosition position = new AnnotationPosition(37.5, 62.5);
		AnnotationSize size = new AnnotationSize(22, 18);
		AnnotationOverlayMath.Rect rect = AnnotationOverlayMath.overlayRect(position, size, zoomed,
				900, 506);
		AnnotationPosition backPosition = AnnotationOverlayMath.positionFor(rect, zoomed, 900, 506);
		AnnotationSize backSize = AnnotationOverlayMath.sizeFor(rect, zoomed, 900, 506);
		assertEquals(position.x(), backPosition.x(), 1e-9);
		assertEquals(position.y(), backPosition.y(), 1e-9);
		assertEquals(size.width(), backSize.width(), 1e-9);
		assertEquals(size.height(), backSize.height(), 1e-9);
	}

	@DisplayName("missing geometry falls back to the full frame like safeRecordingRect")
	@Test
	void fallbackUsesFullFrame() {
		AnnotationOverlayMath.Rect rect = AnnotationOverlayMath.overlayRect(
				new AnnotationPosition(50, 50), new AnnotationSize(50, 50), null, 640, 360);
		// Top-left anchored percent: position 50% starts halfway, size 50% fills the rest.
		assertEquals(320, rect.x(), 1e-9);
		assertEquals(180, rect.y(), 1e-9);
		assertEquals(320, rect.width(), 1e-9);
		assertEquals(180, rect.height(), 1e-9);

		CameraGeometry emptyMask = new CameraGeometry(0, 0, 0, 0, 1, 0, 0, 640, 360);
		AnnotationOverlayMath.Rect same = AnnotationOverlayMath.overlayRect(
				new AnnotationPosition(50, 50), new AnnotationSize(50, 50), emptyMask, 640, 360);
		assertEquals(rect, same);
	}

	@DisplayName("size never collapses below one percent (positivePercent)")
	@Test
	void positivePercent() {
		assertEquals(1, AnnotationOverlayMath.positivePercent(-5), 1e-9);
		assertEquals(1, AnnotationOverlayMath.positivePercent(0), 1e-9);
		assertEquals(12, AnnotationOverlayMath.positivePercent(12), 1e-9);
		assertEquals(0, AnnotationOverlayMath.positivePercent(Double.NaN), 1e-9);
		assertTrue(AnnotationOverlayMath.positivePercent(Double.POSITIVE_INFINITY) == 0);
	}
}
