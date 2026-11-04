package dev.gravaai.media.compose.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.ElementMotion;

class MotionEvaluatorTest {

	@Test
	void fadeRampsInHoldsAndRampsOut() {
		List<ElementMotion.Track> fade = ElementMotion.preset("fade", true, true, 400);
		assertEquals(0, MotionEvaluator.evaluate(fade, 0, 3000).opacity(), 1e-9);
		double half = MotionEvaluator.evaluate(fade, 200, 3000).opacity();
		assertTrue(half > 0.5 && half < 1, "ease-out is past half way: " + half);
		assertEquals(1, MotionEvaluator.evaluate(fade, 1500, 3000).opacity(), 1e-9);
		assertEquals(0, MotionEvaluator.evaluate(fade, 3000, 3000).opacity(), 1e-9);
	}

	@Test
	void springEasingOvershootsAndSettles() {
		Easing spring = Easing.parse("spring").orElseThrow();
		double peak = 0;
		for (double t = 0; t <= 1; t += 0.01) {
			peak = Math.max(peak, spring.apply(t));
		}
		assertTrue(peak > 1.01, "overshoots: " + peak);
		assertEquals(1, spring.apply(1), 1e-9);
	}

	@Test
	void restPoseWithoutTracks() {
		assertTrue(MotionEvaluator.evaluate(List.of(), 100, 1000).isRest());
	}
}
