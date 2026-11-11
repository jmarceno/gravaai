package dev.gravaai.ui.editor.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.CropRegion;
import dev.gravaai.media.compose.CameraGeometry;

/** Coordinate + gesture math of the preview crop overlay ({@link CropOverlayMath}). */
class CropOverlayMathTest {

	/** Padded 16:9 recording rect at 640x360 output, identity camera. */
	private static final CameraGeometry MASKED = new CameraGeometry(80, 45, 480, 270, 1, 0, 0,
			640, 360);
	/** The same rect with the camera zoomed 2x and shifted. */
	private static final CameraGeometry ZOOMED = new CameraGeometry(80, 45, 480, 270, 2, -100, -50,
			640, 360);

	private static final double EPS = 1e-9;

	@DisplayName("the crop rect is the recording rect with the camera transform applied")
	@Test
	void rectFollowsMaskAndCamera() {
		AnnotationOverlayMath.Rect rect = CropOverlayMath.rectFor(MASKED, 640, 360);
		assertEquals(80, rect.x(), 1e-9);
		assertEquals(45, rect.y(), 1e-9);
		assertEquals(480, rect.width(), 1e-9);
		assertEquals(270, rect.height(), 1e-9);

		AnnotationOverlayMath.Rect zoomed = CropOverlayMath.rectFor(ZOOMED, 640, 360);
		assertEquals(60, zoomed.x(), 1e-9);
		assertEquals(40, zoomed.y(), 1e-9);
		assertEquals(960, zoomed.width(), 1e-9);
		assertEquals(540, zoomed.height(), 1e-9);
	}

	@DisplayName("panel pixels scale from the compositor output size")
	@Test
	void rectPanelScale() {
		AnnotationOverlayMath.Rect rect = CropOverlayMath.rectFor(MASKED, 1280, 720);
		assertEquals(160, rect.x(), 1e-9);
		assertEquals(90, rect.y(), 1e-9);
		assertEquals(960, rect.width(), 1e-9);
		assertEquals(540, rect.height(), 1e-9);
	}

	@DisplayName("without a rendered frame the whole panel is the crop rect")
	@Test
	void rectFallback() {
		AnnotationOverlayMath.Rect rect = CropOverlayMath.rectFor(null, 1280, 720);
		assertEquals(0, rect.x(), 1e-9);
		assertEquals(0, rect.y(), 1e-9);
		assertEquals(1280, rect.width(), 1e-9);
		assertEquals(720, rect.height(), 1e-9);
	}

	@DisplayName("panel points convert to source fractions through the crop bounds")
	@Test
	void sourcePoints() {
		CropRegion crop = new CropRegion(0.1, 0.2, 0.8, 0.6);
		CropOverlayMath.Point topLeft = CropOverlayMath.sourcePointFor(80, 45, crop, MASKED, 640,
				360);
		assertEquals(0.1, topLeft.x(), 1e-9);
		assertEquals(0.2, topLeft.y(), 1e-9);

		CropOverlayMath.Point bottomRight = CropOverlayMath.sourcePointFor(560, 315, crop, MASKED,
				640, 360);
		assertEquals(0.9, bottomRight.x(), 1e-9);
		assertEquals(0.8, bottomRight.y(), 1e-9);

		CropOverlayMath.Point middle = CropOverlayMath.sourcePointFor(320, 180, crop, MASKED, 640,
				360);
		assertEquals(0.5, middle.x(), 1e-9);
		assertEquals(0.5, middle.y(), 1e-9);
	}

	@DisplayName("the inverse conversion undoes the camera transform and panel scale")
	@Test
	void sourcePointsThroughCamera() {
		CropRegion crop = new CropRegion(0.1, 0.2, 0.8, 0.6);
		// The mask centre (scene 320,180) lands on panel (540,310) under the 2x camera.
		CropOverlayMath.Point middle = CropOverlayMath.sourcePointFor(540, 310, crop, ZOOMED, 640,
				360);
		assertEquals(0.5, middle.x(), 1e-9);
		assertEquals(0.5, middle.y(), 1e-9);
	}

	@DisplayName("without a rendered frame panel points map over the whole panel")
	@Test
	void sourcePointsFallback() {
		CropOverlayMath.Point middle = CropOverlayMath.sourcePointFor(320, 180, null, null, 640,
				360);
		assertEquals(0.5, middle.x(), 1e-9);
		assertEquals(0.5, middle.y(), 1e-9);
	}

	@DisplayName("edges drag independently and keep 10% of the frame")
	@Test
	void edgeDrags() {
		CropRegion full = CropRegion.DEFAULT;
		CropRegion top = CropOverlayMath.drag(full, CropOverlayMath.Handle.TOP, 0, 0.25);
		assertEquals(0.25, top.y(), 1e-9);
		assertEquals(0.75, top.height(), 1e-9);

		CropRegion pastBottom = CropOverlayMath.drag(full, CropOverlayMath.Handle.TOP, 0, 2);
		assertEquals(0.9, pastBottom.y(), 1e-9);
		assertEquals(0.1, pastBottom.height(), 1e-9);

		CropRegion right = CropOverlayMath.drag(new CropRegion(0.2, 0, 0.5, 1),
				CropOverlayMath.Handle.RIGHT, 0.8, 0);
		assertEquals(0.6, right.width(), 1e-9);

		CropRegion left = CropOverlayMath.drag(new CropRegion(0.2, 0, 0.5, 1),
				CropOverlayMath.Handle.LEFT, -0.5, 0);
		assertEquals(0, left.x(), 1e-9);
		assertEquals(0.7, left.width(), 1e-9);

		CropRegion bottom = CropOverlayMath.drag(full, CropOverlayMath.Handle.BOTTOM, 0, 0.05);
		assertEquals(0.1, bottom.height(), 1e-9);
	}

	@DisplayName("corners drag both edges and never invert the rectangle")
	@Test
	void cornerDrags() {
		CropRegion start = new CropRegion(0.1, 0.1, 0.6, 0.6);

		CropRegion se = CropOverlayMath.drag(start, CropOverlayMath.Handle.SE, 0.9, 0.9);
		assertEquals(0.1, se.x(), 1e-9);
		assertEquals(0.1, se.y(), 1e-9);
		assertEquals(0.8, se.width(), 1e-9);
		assertEquals(0.8, se.height(), 1e-9);

		CropRegion nw = CropOverlayMath.drag(start, CropOverlayMath.Handle.NW, 0.4, 0.4);
		assertEquals(0.4, nw.x(), 1e-9);
		assertEquals(0.4, nw.y(), 1e-9);
		assertEquals(0.3, nw.width(), 1e-9);
		assertEquals(0.3, nw.height(), 1e-9);

		CropRegion clamped = CropOverlayMath.drag(start, CropOverlayMath.Handle.NW, -1, -1);
		assertEquals(0, clamped.x(), 1e-9);
		assertEquals(0, clamped.y(), 1e-9);
		assertEquals(0.7, clamped.width(), 1e-9);
		assertEquals(0.7, clamped.height(), 1e-9);

		CropRegion min = CropOverlayMath.drag(start, CropOverlayMath.Handle.NE, 0.05, 0.65);
		assertEquals(0.1, min.width(), 1e-9);
		assertEquals(0.1, min.height(), 1e-9);
	}

	@DisplayName("aspect lock keeps the ratio: the dragged axis follows, the other re-centres")
	@Test
	void aspectLockEdges() {
		CropRegion start = new CropRegion(0, 0, 0.5, 0.5);
		// Right edge pulled left to width 0.4: height follows to 0.4, centred on 0.25.
		CropRegion dragged = CropOverlayMath.drag(start, CropOverlayMath.Handle.RIGHT, 0.4, 0);
		CropRegion locked = CropOverlayMath.applyAspect(dragged, start,
				CropOverlayMath.Handle.RIGHT, 1, 1, 1);
		assertEquals(0, locked.x(), EPS);
		assertEquals(0.05, locked.y(), EPS);
		assertEquals(0.4, locked.width(), EPS);
		assertEquals(0.4, locked.height(), EPS);

		// Top edge raised to height 0.4: width follows, centred on the drag-start centre.
		CropRegion topStart = new CropRegion(0.1, 0.1, 0.5, 0.5);
		CropRegion topDragged = CropOverlayMath.drag(topStart, CropOverlayMath.Handle.TOP, 0, 0.2);
		CropRegion topLocked = CropOverlayMath.applyAspect(topDragged, topStart,
				CropOverlayMath.Handle.TOP, 1, 1, 1);
		assertEquals(0.15, topLocked.x(), EPS);
		assertEquals(0.2, topLocked.y(), EPS);
		assertEquals(0.4, topLocked.width(), EPS);
		assertEquals(0.4, topLocked.height(), EPS);
	}

	@DisplayName("aspect lock on a wide source keeps the source aspect, not the fractions")
	@Test
	void aspectLockPixelRatio() {
		CropRegion start = CropRegion.DEFAULT;
		// 1920x1080 source locked at 16:9: width 0.5 keeps height 0.5.
		CropRegion dragged = CropOverlayMath.drag(start, CropOverlayMath.Handle.RIGHT, 0.5, 0);
		CropRegion locked = CropOverlayMath.applyAspect(dragged, start,
				CropOverlayMath.Handle.RIGHT, 16.0 / 9, 1920, 1080);
		assertEquals(new CropRegion(0, 0.25, 0.5, 0.5), locked);

		// The same drag on a square-ratio lock grows the height to keep square pixels.
		double squareHeight = 0.5 * 1920.0 / 1080.0;
		CropRegion square = CropOverlayMath.applyAspect(dragged, start,
				CropOverlayMath.Handle.RIGHT, 1, 1920, 1080);
		assertEquals(new CropRegion(0, 0.5 - squareHeight / 2, 0.5, squareHeight), square);
	}

	@DisplayName("aspect lock corners anchor the opposite corner")
	@Test
	void aspectLockCorners() {
		CropRegion start = new CropRegion(0.1, 0.1, 0.3, 0.3);

		CropRegion se = CropOverlayMath.applyAspect(
				CropOverlayMath.drag(start, CropOverlayMath.Handle.SE, 0.6, 0.6), start,
				CropOverlayMath.Handle.SE, 1, 1, 1);
		assertEquals(new CropRegion(0.1, 0.1, 0.5, 0.5), se);

		CropRegion nw = CropOverlayMath.applyAspect(
				CropOverlayMath.drag(start, CropOverlayMath.Handle.NW, 0.15, 0.25), start,
				CropOverlayMath.Handle.NW, 1, 1, 1);
		assertEquals(0.15, nw.x(), EPS);
		assertEquals(0.15, nw.y(), EPS);
		assertEquals(0.25, nw.width(), EPS);
		assertEquals(0.25, nw.height(), EPS);
	}

	@DisplayName("aspect lock never leaves the frame and keeps the minimum span")
	@Test
	void aspectLockBounds() {
		CropRegion start = CropRegion.DEFAULT;
		// A 1:1 lock dragged to the full width stays square inside the frame.
		CropRegion dragged = CropOverlayMath.drag(start, CropOverlayMath.Handle.RIGHT, 1.5, 0);
		CropRegion locked = CropOverlayMath.applyAspect(dragged, start,
				CropOverlayMath.Handle.RIGHT, 1, 1, 1);
		assertEquals(new CropRegion(0, 0, 1, 1), locked);

		// A 1:20 lock dragged to 15% height cannot hold the ratio above the minimum width:
		// the pointer and the minimum span win, the ratio gives.
		CropRegion tallDragged = CropOverlayMath.drag(start, CropOverlayMath.Handle.BOTTOM, 0,
				0.15);
		CropRegion tall = CropOverlayMath.applyAspect(tallDragged, start,
				CropOverlayMath.Handle.BOTTOM, 0.05, 1, 1);
		assertEquals(0.1, tall.width(), 1e-9);
		assertEquals(0.15, tall.height(), 1e-9);
		assertEquals(0, tall.y(), 1e-9);

		// The same lock dragged lower: the frame caps the height and re-centres the width.
		CropRegion tallFull = CropOverlayMath.applyAspect(
				CropOverlayMath.drag(start, CropOverlayMath.Handle.BOTTOM, 0, 0.95), start,
				CropOverlayMath.Handle.BOTTOM, 0.05, 1, 1);
		assertEquals(0.1, tallFull.width(), 1e-9);
		assertEquals(0.95, tallFull.height(), 1e-9);
	}

	@DisplayName("aspect lock corners never move the anchored bottom edge")
	@Test
	void aspectLockCornerAnchor() {
		// 1920x1080 source, crop (0.2, 0.2, 0.1, 0.3) locked (ratio 16/27); dragging the NW
		// handle left to width 0.3 would need height 0.9, past the anchored bottom edge 0.5:
		// the anchor wins and the dragged width shrinks to hold the ratio instead.
		CropRegion start = new CropRegion(0.2, 0.2, 0.1, 0.3);
		double ratio = CropOverlayMath.pixelRatio(start, 1920, 1080);
		assertEquals(16.0 / 27, ratio, 1e-9);
		CropRegion dragged = CropOverlayMath.drag(start, CropOverlayMath.Handle.NW, -0.1, 0.2);
		assertEquals(0.3, dragged.width(), 1e-9);
		CropRegion locked = CropOverlayMath.applyAspect(dragged, start,
				CropOverlayMath.Handle.NW, ratio, 1920, 1080);
		assertEquals(0.5, locked.y() + locked.height(), 1e-9);
		assertEquals(1.0 / 6, locked.width(), 1e-9);
		assertEquals(0.5, locked.height(), 1e-9);
	}

	@DisplayName("a loaded crop below the overlay's minimum span is not grown by a locked drag")
	@Test
	void aspectLockSubMinimumSpanCrop() {
		// The project normaliser floors the crop at 0.01; a TOP drag with the lock on must keep
		// the anchored bottom edge where it is instead of growing the crop to the 0.1 minimum.
		CropRegion start = new CropRegion(0, 0, 1, 0.05);
		double ratio = CropOverlayMath.pixelRatio(start, 1920, 1080);
		CropRegion dragged = CropOverlayMath.drag(start, CropOverlayMath.Handle.TOP, 0, 0);
		assertEquals(0.05, dragged.height(), 1e-9);
		CropRegion locked = CropOverlayMath.applyAspect(dragged, start,
				CropOverlayMath.Handle.TOP, ratio, 1920, 1080);
		assertEquals(start, locked);
	}

	@DisplayName("locked corner drags on near-edge crops stay inside the frame")
	@Test
	void aspectLockNearEdgeSubMinimumSpan() {
		// A near-bottom crop under lock: the SE drag keeps the top edge anchored and the crop
		// inside the frame instead of growing the height past the bottom boundary.
		CropRegion nearBottom = new CropRegion(0.1, 0.95, 0.8, 0.05);
		double ratio = CropOverlayMath.pixelRatio(nearBottom, 1920, 1080);
		CropRegion dragged = CropOverlayMath.drag(nearBottom, CropOverlayMath.Handle.SE, 1.5, 1.5);
		CropRegion locked = CropOverlayMath.applyAspect(dragged, nearBottom,
				CropOverlayMath.Handle.SE, ratio, 1920, 1080);
		assertEquals(nearBottom.x(), locked.x(), EPS);
		assertEquals(nearBottom.y(), locked.y(), EPS);
		assertEquals(nearBottom.width(), locked.width(), EPS);
		assertEquals(nearBottom.height(), locked.height(), EPS);

		// A left-hugging crop under lock: the NW drag keeps the anchored right edge at 0.07
		// even though the minimum span is wider.
		CropRegion narrow = new CropRegion(0.02, 0, 0.05, 1);
		double narrowRatio = CropOverlayMath.pixelRatio(narrow, 1920, 1080);
		CropRegion narrowDragged = CropOverlayMath.drag(narrow, CropOverlayMath.Handle.NW, 0, 0.3);
		assertEquals(0.07, narrowDragged.width(), 1e-9);
		CropRegion narrowLocked = CropOverlayMath.applyAspect(narrowDragged, narrow,
				CropOverlayMath.Handle.NW, narrowRatio, 1920, 1080);
		assertEquals(0.07, narrowLocked.width(), 1e-9);
		assertEquals(0.07, narrowLocked.x() + narrowLocked.width(), 1e-9);
	}

	@DisplayName("free drags never push the dragged edge past the frame on sub-minimum crops")
	@Test
	void freeDragFrameBound() {
		// The minimum span cannot push the bottom edge out of the frame.
		CropRegion nearBottom = new CropRegion(0.1, 0.95, 0.8, 0.05);
		CropRegion dragged = CropOverlayMath.drag(nearBottom, CropOverlayMath.Handle.BOTTOM, 0,
				1.5);
		assertEquals(0.05, dragged.height(), 1e-9);
		assertEquals(1, dragged.y() + dragged.height(), 1e-9);
	}

	@DisplayName("locked NW/NE drags near the frame bottom keep the bottom edge anchored")
	@Test
	void aspectLockBottomAnchoredCorner() {
		// A wide locked ratio (16) near the frame bottom derives a sub-minimum height; the top
		// edge moves up to hold the minimum instead of the bottom edge crossing the frame.
		CropRegion start = new CropRegion(0.05, 0.9, 0.9, 0.1);
		CropRegion dragged = CropOverlayMath.drag(start, CropOverlayMath.Handle.NW, 0.55, 0.9);
		assertEquals(0.4, dragged.width(), 1e-9);
		CropRegion locked = CropOverlayMath.applyAspect(dragged, start,
				CropOverlayMath.Handle.NW, 16, 1920, 1080);
		assertEquals(0.55, locked.x(), EPS);
		assertEquals(0.9, locked.y(), EPS);
		assertEquals(0.4, locked.width(), EPS);
		assertEquals(0.1, locked.height(), EPS);
	}

	@DisplayName("pixelRatio reads the crop's aspect in source pixels; 0 dims fall back")
	@Test
	void pixelRatio() {
		assertEquals(16.0 / 9, CropOverlayMath.pixelRatio(CropRegion.DEFAULT, 1920, 1080), 1e-9);
		assertEquals(16.0 / 9, CropOverlayMath.pixelRatio(new CropRegion(0, 0, 0.5, 0.5), 1920,
				1080), 1e-9);
		assertEquals(1, CropOverlayMath.pixelRatio(new CropRegion(0, 0, 0.5, 0.5), 0, 0), 1e-9);
	}
}
