package dev.gravaai.media.compose.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.gravaai.media.compose.anim.Interpolation.Extrapolation;
import dev.gravaai.media.compose.anim.Interpolation.Options;

class AnimationKernelTest {

	private static final double[] IN = {0, 10};
	private static final double[] OUT = {0, 100};

	@Test
	void interpolatesLinearlyAndPerSegment() {
		assertEquals(50, Interpolation.interpolate(5, IN, OUT), 1e-9);
		double[] in = {0, 20, 70, 90};
		double[] out = {0, 1, 1, 0};
		assertEquals(0.5, Interpolation.interpolate(10, in, out), 1e-9);
		assertEquals(1, Interpolation.interpolate(40, in, out), 1e-9);
		assertEquals(0.5, Interpolation.interpolate(80, in, out), 1e-9);
	}

	@Test
	void extrapolationModes() {
		assertEquals(150, Interpolation.interpolate(15, IN, OUT), 1e-9, "extend by default");
		assertEquals(100, Interpolation.interpolate(15, IN, OUT,
				new Options(Easing.LINEAR, Extrapolation.EXTEND, Extrapolation.CLAMP)), 1e-9);
		assertEquals(0, Interpolation.interpolate(-5, IN, OUT,
				new Options(Easing.LINEAR, Extrapolation.CLAMP, Extrapolation.EXTEND)), 1e-9);
		assertEquals(15, Interpolation.interpolate(15, IN, OUT,
				new Options(Easing.LINEAR, Extrapolation.EXTEND, Extrapolation.IDENTITY)), 1e-9);
		assertEquals(50, Interpolation.interpolate(15, IN, OUT,
				new Options(Easing.LINEAR, Extrapolation.EXTEND, Extrapolation.WRAP)), 1e-9);
	}

	@Test
	void easingAppliesInsideTheSegment() {
		Options cubic = new Options(Easing.CUBIC, Extrapolation.CLAMP, Extrapolation.CLAMP);
		assertEquals(12.5, Interpolation.interpolate(5, IN, OUT, cubic), 1e-9);
	}

	@Test
	void rejectsBadRanges() {
		assertThrows(IllegalArgumentException.class,
				() -> Interpolation.interpolate(1, new double[] {0, 1, 2}, new double[] {0, 1}));
		assertThrows(IllegalArgumentException.class,
				() -> Interpolation.interpolate(1, new double[] {2, 1}, new double[] {0, 1}));
	}

	@Test
	void easingFamilyEndpointsAndParse() {
		for (String name : new String[] {"linear", "ease", "ease-in", "ease-out", "ease-in-out",
				"ease-out-back", "ease-in-out-elastic", "bounce", "ease-out-expo",
				"cubic-bezier(0.2,0,0,1)"}) {
			Easing easing = Easing.parse(name).orElseThrow();
			assertEquals(0, easing.apply(0), 1e-3, name);
			assertEquals(1, easing.apply(1), 1e-3, name);
		}
		assertTrue(Easing.parse("wobbly").isEmpty());
		assertTrue(Easing.out(Easing.CUBIC).apply(0.5) > 0.5, "out is front-loaded");
		assertTrue(Easing.back(1.70158).apply(0.2) < 0, "back overshoots backwards");
		assertEquals(0.5, Easing.steps(2).apply(0.7), 1e-9);
	}

	@Test
	void springStartsSettlesAndOvershoots() {
		Spring.Params params = Spring.Params.DEFAULT;
		assertEquals(0, Spring.value(0, 30, params), 1e-9);
		assertEquals(1, Spring.value(300, 30, params), 1e-3);
		double max = 0;
		for (int frame = 0; frame < 60; frame++) {
			max = Math.max(max, Spring.value(frame, 30, params));
		}
		assertTrue(max > 1, "default spring overshoots");
		Spring.Params clamped = new Spring.Params(1, 10, 100, true, 0, 1, 0, 0, false);
		for (int frame = 0; frame < 60; frame++) {
			assertTrue(Spring.value(frame, 30, clamped) <= 1 + 1e-12);
		}
	}

	@Test
	void springIsExportRateIndependent() {
		Spring.Params params = Spring.Params.DEFAULT;
		// Frame 10 at 30 fps is the same moment as frame 20 at 60 fps.
		assertEquals(Spring.value(10, 30, params), Spring.value(20, 60, params), 1e-12);
	}

	@Test
	void springStretchDelayAndReverse() {
		Spring.Params stretched = new Spring.Params(1, 10, 100, false, 0, 1, 0, 40, false);
		assertEquals(1, Spring.value(40, 30, stretched), 0.01);
		Spring.Params delayed = new Spring.Params(1, 10, 100, false, 0, 1, 10, 0, false);
		assertEquals(0, Spring.value(10, 30, delayed), 1e-9);
		Spring.Params reversed = new Spring.Params(1, 10, 100, false, 0, 1, 0, 40, true);
		assertEquals(1, Spring.value(0, 30, reversed), 0.01);
		assertEquals(0, Spring.value(40, 30, reversed), 1e-9);
		assertTrue(Spring.durationInFrames(30, Spring.Params.DEFAULT, Spring.DEFAULT_THRESHOLD) > 10);
	}

	@Test
	void criticalAndOverDampedSettleWithoutOvershoot() {
		Spring.Params critical = new Spring.Params(1, 20, 100, false, 0, 1, 0, 0, false);
		Spring.Params over = new Spring.Params(1, 40, 100, false, 0, 1, 0, 0, false);
		for (int frame = 0; frame < 120; frame++) {
			assertTrue(Spring.value(frame, 30, critical) <= 1 + 1e-9);
			assertTrue(Spring.value(frame, 30, over) <= 1 + 1e-9);
		}
	}

	@Test
	void randomIsStableAndSpread() {
		assertEquals(DeterministicRandom.random("seed"), DeterministicRandom.random("seed"));
		assertNotEquals(DeterministicRandom.random("a"), DeterministicRandom.random("b"));
		DeterministicRandom.Sequence first = new DeterministicRandom.Sequence("x");
		DeterministicRandom.Sequence second = new DeterministicRandom.Sequence("x");
		double sum = 0;
		for (int index = 0; index < 1000; index++) {
			double value = first.next();
			assertEquals(value, second.next());
			assertTrue(value >= 0 && value < 1);
			sum += value;
		}
		assertEquals(0.5, sum / 1000, 0.05);
	}
}
