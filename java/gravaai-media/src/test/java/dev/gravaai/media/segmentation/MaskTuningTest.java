package dev.gravaai.media.segmentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The user-facing mask controls (Webcam → Advanced): how the precision/offset pair maps onto the
 * edge-shaping numbers the segmenter applies.
 */
class MaskTuningTest {

	@Test
	@DisplayName("the defaults reproduce the shape the blur shipped with")
	void defaultsMatchTheShippedShape() {
		MaskTuning tuning = MaskTuning.DEFAULT;
		assertEquals(2, tuning.dilationRadius(256, 256), "the shipped dilation is 2 px");
		assertEquals(2.0, tuning.sigma(), 1e-9, "the shipped sigma is 2");
		assertEquals(12.0, tuning.slope(), 1e-9, "the shipped sigmoid slope is 12");
	}

	@Test
	@DisplayName("precision widens the edge band at the soft end and narrows it at the hard end")
	void precisionShapesTheEdgeBand() {
		MaskTuning soft = new MaskTuning(0, 0);
		MaskTuning hard = new MaskTuning(1, 0);
		assertTrue(soft.sigma() > MaskTuning.DEFAULT.sigma(),
				"the soft end must widen the Gaussian: " + soft.sigma());
		assertTrue(hard.sigma() < MaskTuning.DEFAULT.sigma(),
				"the hard end must narrow the Gaussian: " + hard.sigma());
		assertTrue(soft.slope() < MaskTuning.DEFAULT.slope(),
				"the soft end must flatten the sigmoid: " + soft.slope());
		assertTrue(hard.slope() > MaskTuning.DEFAULT.slope(),
				"the hard end must steepen the sigmoid: " + hard.slope());
	}

	@Test
	@DisplayName("a positive offset grows the mask and a negative one shrinks it")
	void offsetMovesTheEdge() {
		// 5% of a 256 px short side is 12.8 px on top of the baseline 2.
		assertEquals(15, new MaskTuning(0.5, 5).dilationRadius(256, 256));
		assertEquals(-11, new MaskTuning(0.5, -5).dilationRadius(256, 256),
				"a fully negative offset must erode past the baseline dilation");
		assertEquals(2, new MaskTuning(0.5, 0).dilationRadius(256, 256));
	}

	@Test
	@DisplayName("the offset scales with the mask's short side, not its area")
	void offsetFollowsTheMaskResolution() {
		assertEquals(7, new MaskTuning(0.5, 5).dilationRadius(256, 96),
				"5% of the 96 px short side plus the baseline 2");
		assertEquals(2, new MaskTuning(0.5, 0).dilationRadius(96, 256));
	}

	@Test
	@DisplayName("out-of-range values clamp instead of throwing")
	void clampsOutOfRange() {
		assertEquals(1, new MaskTuning(9, 0).precision());
		assertEquals(0, new MaskTuning(-9, 0).precision());
		assertEquals(MaskTuning.MAX_OFFSET_PERCENT, new MaskTuning(0.5, 99).offsetPercent());
		assertEquals(MaskTuning.MIN_OFFSET_PERCENT, new MaskTuning(0.5, -99).offsetPercent());
	}

	@Test
	@DisplayName("non-finite values are rejected: a NaN must not reach the mask math")
	void rejectsNonFinite() {
		assertThrows(IllegalArgumentException.class, () -> new MaskTuning(Double.NaN, 0));
		assertThrows(IllegalArgumentException.class, () -> new MaskTuning(0.5, Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> new MaskTuning(0.5, Double.POSITIVE_INFINITY));
	}
}
