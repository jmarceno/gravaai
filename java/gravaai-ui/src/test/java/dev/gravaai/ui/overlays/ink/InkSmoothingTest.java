package dev.gravaai.ui.overlays.ink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.platform.compositor.OverlayStacking;

/** Pen stroke smoothing and the desktop stacking seam. */
class InkSmoothingTest {

	private static final List<double[]> POINTS = List.of(
			new double[] {0, 0}, new double[] {10, 0}, new double[] {10, 10}, new double[] {20, 10});

	@Test
	@DisplayName("a stroke starts where the pen went down and joins pieces at the midpoints")
	void midpointPieces() {
		assertEquals(new InkSmoothing.Segment(0, 0, 0, 0, 0, 0), InkSmoothing.segment(POINTS, 0));
		assertEquals(new InkSmoothing.Segment(0, 0, 0, 0, 5, 0), InkSmoothing.segment(POINTS, 1));
		// Each later piece runs midpoint to midpoint, bending through the sample point between.
		assertEquals(new InkSmoothing.Segment(5, 0, 10, 0, 10, 5), InkSmoothing.segment(POINTS, 2));
		assertEquals(new InkSmoothing.Segment(10, 5, 10, 10, 15, 10), InkSmoothing.segment(POINTS, 3));
	}

	@Test
	@DisplayName("each desktop stacking mechanism has its own class")
	void stackingClasses() {
		assertEquals(RefrontOnFocusLossStacking.class,
				InkStacking.forMechanism(OverlayStacking.REFRONT_ON_FOCUS_LOSS).getClass());
		assertEquals(KeepAboveStacking.class, InkStacking.forMechanism(OverlayStacking.KEEP_ABOVE).getClass());
	}
}
