package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Port of {@code src/components/video-editor/videoPlayback/zoomAnimation.test.ts} (plan §8:
 * tests first, same cases and expected values). Covers {@link MathUtils},
 * {@link SpringSmoothing} and {@link ZoomRegionUtils}.
 */
class ZoomAnimationTest {

	private static ZoomRegionSpec region(String id, int startMs, int endMs, ZoomDepth depth,
			double cx, double cy) {
		return new ZoomRegionSpec(id, startMs, endMs, depth, new ZoomFocus(cx, cy), null);
	}

	// ---------------------------------------------------------------------------------------
	// motionSmoothing — spring state helpers
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("returns uninitialized state at 0")
	void createSpringStateDefaults() {
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		assertEquals(0.0, state.value);
		assertEquals(0.0, state.velocity);
		assertFalse(state.initialized);
	}

	@Test
	@DisplayName("accepts a custom initial value")
	void createSpringStateCustomValue() {
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState(5);
		assertEquals(5.0, state.value);
		assertFalse(state.initialized);
	}

	@Test
	@DisplayName("clears velocity and initialized flag")
	void resetSpringStateKeepsValue() {
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState(3);
		state.velocity = 10;
		state.initialized = true;
		SpringSmoothing.resetSpringState(state);
		assertEquals(0.0, state.velocity);
		assertFalse(state.initialized);
		assertEquals(3.0, state.value);
	}

	@Test
	@DisplayName("resets value when provided")
	void resetSpringStateWithValue() {
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState(3);
		state.velocity = 10;
		state.initialized = true;
		SpringSmoothing.resetSpringState(state, 7.0);
		assertEquals(7.0, state.value);
		assertEquals(0.0, state.velocity);
	}

	@Test
	@DisplayName("passes through a normal 16ms frame")
	void clampDeltaPassesNormalFrame() {
		assertEquals(16.67, SpringSmoothing.clampDeltaMs(16.67), 0.01);
	}

	@Test
	@DisplayName("clamps negative delta to fallback")
	void clampDeltaNegative() {
		assertEquals(1000.0 / 60.0, SpringSmoothing.clampDeltaMs(-1), 0.01);
	}

	@Test
	@DisplayName("clamps zero delta to fallback")
	void clampDeltaZero() {
		assertEquals(1000.0 / 60.0, SpringSmoothing.clampDeltaMs(0), 0.01);
	}

	@Test
	@DisplayName("clamps NaN to fallback")
	void clampDeltaNan() {
		assertEquals(1000.0 / 60.0, SpringSmoothing.clampDeltaMs(Double.NaN), 0.01);
	}

	@Test
	@DisplayName("clamps excessively large delta to 80ms")
	void clampDeltaLarge() {
		assertEquals(80.0, SpringSmoothing.clampDeltaMs(500));
	}

	// ---------------------------------------------------------------------------------------
	// motionSmoothing — stepSpringValue
	// ---------------------------------------------------------------------------------------

	private static SpringSmoothing.SpringConfig springConfig() {
		return new SpringSmoothing.SpringConfig(320, 40, 0.92, 0.0005, 0.015);
	}

	@Test
	@DisplayName("snaps to target on first tick (initialization)")
	void springSnapsOnFirstTick() {
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		double result = SpringSmoothing.stepSpringValue(state, 1, 16, springConfig());
		assertEquals(1.0, result);
		assertTrue(state.initialized);
	}

	@Test
	@DisplayName("converges toward the target over many ticks")
	void springConverges() {
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		SpringSmoothing.stepSpringValue(state, 0, 16, springConfig());
		for (int i = 0; i < 200; i++) {
			SpringSmoothing.stepSpringValue(state, 1, 16, springConfig());
		}
		assertEquals(1.0, state.value, 0.001);
	}

	@Test
	@DisplayName("converges within reasonable time for default zoom config")
	void springZoomConfigConverges() {
		SpringSmoothing.SpringConfig zoomConfig = SpringSmoothing.getZoomSpringConfig(1.0);
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		SpringSmoothing.stepSpringValue(state, 0, 16, zoomConfig);
		int frames = 0;
		while (Math.abs(state.value - 1) > 0.001 && frames < 500) {
			SpringSmoothing.stepSpringValue(state, 1, 16, zoomConfig);
			frames++;
		}
		assertTrue(frames < 400, "took " + frames + " frames");
		assertEquals(1.0, state.value, 0.01);
	}

	@Test
	@DisplayName("handles target change mid-animation")
	void springHandlesTargetChange() {
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		SpringSmoothing.stepSpringValue(state, 0.5, 16, springConfig());
		for (int i = 0; i < 30; i++) {
			SpringSmoothing.stepSpringValue(state, 1, 16, springConfig());
		}
		double midway = state.value;
		assertTrue(midway > 0.5);
		for (int i = 0; i < 200; i++) {
			SpringSmoothing.stepSpringValue(state, 0, 16, springConfig());
		}
		assertEquals(0.0, state.value, 0.01);
	}

	@Test
	@DisplayName("settles exactly at target when within rest thresholds")
	void springSettlesWithinThresholds() {
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		SpringSmoothing.stepSpringValue(state, 0, 16, springConfig());
		state.value = 0.9999;
		state.velocity = 0;
		SpringSmoothing.stepSpringValue(state, 1, 16, springConfig());
		assertEquals(1.0, state.value);
		assertEquals(0.0, state.velocity);
	}

	@Test
	@DisplayName("does not produce NaN or Infinity values")
	void springStaysFinite() {
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		SpringSmoothing.stepSpringValue(state, 0, 16, springConfig());
		for (int i = 0; i < 300; i++) {
			SpringSmoothing.stepSpringValue(state, Math.sin(i * 0.1), 16, springConfig());
			assertTrue(Double.isFinite(state.value));
			assertTrue(Double.isFinite(state.velocity));
		}
	}

	// ---------------------------------------------------------------------------------------
	// motionSmoothing — spring configs
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("returns snap-like config at smoothness 0")
	void zoomSpringConfigAtZero() {
		SpringSmoothing.SpringConfig config = SpringSmoothing.getZoomSpringConfig(0);
		assertEquals(1000.0, config.stiffness());
		assertEquals(100.0, config.damping());
	}

	@Test
	@DisplayName("returns the default mapped config at smoothness 0.5")
	void zoomSpringConfigAtHalf() {
		SpringSmoothing.SpringConfig config = SpringSmoothing.getZoomSpringConfig(0.5);
		assertEquals(100.0, config.stiffness());
		assertEquals(1.0, config.mass(), 0.01);
	}

	@Test
	@DisplayName("returns the max mapped config at smoothness 1.0")
	void zoomSpringConfigAtOne() {
		SpringSmoothing.SpringConfig config = SpringSmoothing.getZoomSpringConfig(1.0);
		assertEquals(50.0, config.stiffness());
		assertEquals(2.0, config.mass(), 0.01);
	}

	@Test
	@DisplayName("clamps values above 1.0 to the max config")
	void zoomSpringConfigClampsAboveOne() {
		assertEquals(SpringSmoothing.getZoomSpringConfig(1.0),
				SpringSmoothing.getZoomSpringConfig(2.0));
	}

	@Test
	@DisplayName("clamps values far above 1.0 to the max config")
	void zoomSpringConfigClampsFarAboveOne() {
		assertEquals(SpringSmoothing.getZoomSpringConfig(1.0),
				SpringSmoothing.getZoomSpringConfig(5));
	}

	@Test
	@DisplayName("defaults to 0.5 when called without arguments")
	void zoomSpringConfigDefaults() {
		assertEquals(SpringSmoothing.getZoomSpringConfig(0.5),
				SpringSmoothing.getZoomSpringConfig());
	}

	@Test
	@DisplayName("returns stiff config at 0 (no smoothing)")
	void cursorSpringConfigAtZero() {
		assertEquals(1000.0, SpringSmoothing.getCursorSpringConfig(0).stiffness());
	}

	@Test
	@DisplayName("returns config in legacy range at 0.25")
	void cursorSpringConfigLegacyRange() {
		SpringSmoothing.SpringConfig config = SpringSmoothing.getCursorSpringConfig(0.25);
		assertTrue(config.stiffness() < 760);
		assertTrue(config.stiffness() > 300);
		assertTrue(config.mass() > 1);
	}

	@Test
	@DisplayName("returns config in extended range at 1.5")
	void cursorSpringConfigExtendedRange() {
		SpringSmoothing.SpringConfig config = SpringSmoothing.getCursorSpringConfig(1.5);
		assertTrue(config.stiffness() < 340);
		assertTrue(config.mass() > 1.6);
	}

	@Test
	@DisplayName("clamps at max smoothing")
	void cursorSpringConfigClamps() {
		assertEquals(SpringSmoothing.getCursorSpringConfig(2),
				SpringSmoothing.getCursorSpringConfig(999));
	}

	@Test
	@DisplayName("applies cursor spring tuning multipliers")
	void cursorSpringConfigTuning() {
		SpringSmoothing.SpringConfig untuned = SpringSmoothing.getCursorSpringConfig(0.5);
		SpringSmoothing.SpringConfig tuned = SpringSmoothing.getCursorSpringConfig(0.5,
				SpringSmoothing.CursorSpringTuning.of(1.5, 0.75, 1.25));
		assertEquals(untuned.stiffness() * 1.5, tuned.stiffness(), 1e-6);
		assertEquals(untuned.damping() * 0.75, tuned.damping(), 1e-6);
		assertEquals(untuned.mass() * 1.25, tuned.mass(), 1e-6);
	}

	// ---------------------------------------------------------------------------------------
	// mathUtils
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("passes values in [0,1]")
	void clamp01PassesValues() {
		assertEquals(0.5, MathUtils.clamp01(0.5));
	}

	@Test
	@DisplayName("clamps below 0")
	void clamp01BelowZero() {
		assertEquals(0.0, MathUtils.clamp01(-0.1));
	}

	@Test
	@DisplayName("clamps above 1")
	void clamp01AboveOne() {
		assertEquals(1.0, MathUtils.clamp01(1.5));
	}

	@Test
	@DisplayName("starts at 0")
	void easeOutZoomStartsAtZero() {
		assertEquals(0.0, MathUtils.easeOutZoom(0), 1e-4);
	}

	@Test
	@DisplayName("ends at 1")
	void easeOutZoomEndsAtOne() {
		assertEquals(1.0, MathUtils.easeOutZoom(1), 1e-4);
	}

	@Test
	@DisplayName("is monotonically increasing")
	void easeOutZoomMonotonic() {
		double previous = 0;
		for (double t = 0.05; t <= 1; t += 0.05) {
			double current = MathUtils.easeOutZoom(t);
			assertTrue(current >= previous);
			previous = current;
		}
	}

	@Test
	@DisplayName("has steep initial rise (ease-out character)")
	void easeOutZoomSteepInitialRise() {
		assertTrue(MathUtils.easeOutZoom(0.25) > 0.5);
	}

	@Test
	@DisplayName("starts at 0 and ends at 1")
	void easeOutExpoEndpoints() {
		assertEquals(0.0, MathUtils.easeOutExpo(0), 1e-4);
		assertEquals(1.0, MathUtils.easeOutExpo(1));
	}

	@Test
	@DisplayName("linear bezier returns identity")
	void cubicBezierIdentity() {
		for (double t = 0; t <= 1; t += 0.1) {
			assertEquals(t, MathUtils.cubicBezier(0.333, 0.333, 0.666, 0.666, t), 0.1);
		}
	}

	// ---------------------------------------------------------------------------------------
	// zoomRegionUtils — computeRegionStrength
	// ---------------------------------------------------------------------------------------

	private static final ZoomRegionSpec STRENGTH_REGION =
			region("z1", 2000, 5000, ZoomDepth.D2, 0.5, 0.5);

	@Test
	@DisplayName("returns 0 well before the region")
	void strengthWellBeforeRegion() {
		assertEquals(0.0, ZoomRegionUtils.computeRegionStrength(STRENGTH_REGION, 0));
	}

	@Test
	@DisplayName("does not start playing 300ms before the timeline block")
	void strengthNearStart() {
		assertEquals(0.0,
				ZoomRegionUtils.computeRegionStrength(STRENGTH_REGION,
						STRENGTH_REGION.startMs() - 300));
		assertTrue(ZoomRegionUtils.computeRegionStrength(STRENGTH_REGION,
				STRENGTH_REGION.startMs() + 300) > 0);
		assertEquals(2000, STRENGTH_REGION.startMs());
		assertEquals(5000, STRENGTH_REGION.endMs());
	}

	@Test
	@DisplayName("returns 0 well after the region")
	void strengthWellAfterRegion() {
		assertEquals(0.0, ZoomRegionUtils.computeRegionStrength(STRENGTH_REGION, 10000));
	}

	@Test
	@DisplayName("reaches full strength during the hold phase")
	void strengthHoldPhase() {
		assertEquals(1.0, ZoomRegionUtils.computeRegionStrength(STRENGTH_REGION, 3500));
	}

	@Test
	@DisplayName("rises smoothly during zoom-in")
	void strengthRisesDuringZoomIn() {
		double strength = ZoomRegionUtils.computeRegionStrength(STRENGTH_REGION,
				STRENGTH_REGION.startMs());
		assertTrue(strength > 0);
		assertTrue(strength < 1);
	}

	@Test
	@DisplayName("falls smoothly during zoom-out")
	void strengthFallsDuringZoomOut() {
		double zoomOutStart = STRENGTH_REGION.endMs() - 150;
		double strength = ZoomRegionUtils.computeRegionStrength(STRENGTH_REGION,
				zoomOutStart + 700);
		assertTrue(strength > 0);
		assertTrue(strength < 1);
	}

	@Test
	@DisplayName("shifts zoom timing when custom durations are provided")
	void strengthCustomDurations() {
		double defaultStrength = ZoomRegionUtils.computeRegionStrength(STRENGTH_REGION,
				STRENGTH_REGION.startMs());
		double fasterStrength = ZoomRegionUtils.computeRegionStrength(STRENGTH_REGION,
				STRENGTH_REGION.startMs(), ZoomRegionUtils.DominantRegionOptions.durations(300, 300));
		assertTrue(fasterStrength != defaultStrength);
		assertTrue(fasterStrength > defaultStrength);
	}

	// ---------------------------------------------------------------------------------------
	// zoomRegionUtils — findDominantRegion
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("returns null region when no regions exist")
	void dominantNoRegions() {
		ZoomRegionUtils.DominantRegionResult result = ZoomRegionUtils.findDominantRegion(
				List.of(), 1000);
		assertNull(result.region());
		assertEquals(0.0, result.strength());
	}

	@Test
	@DisplayName("returns the active region at its hold phase")
	void dominantActiveHold() {
		ZoomRegionUtils.DominantRegionResult result = ZoomRegionUtils.findDominantRegion(
				List.of(region("a", 1000, 4000, ZoomDepth.D2, 0.3, 0.3)), 2500);
		assertNotNull(result.region());
		assertEquals("a", result.region().id());
		assertEquals(1.0, result.strength());
	}

	@Test
	@DisplayName("returns null region outside all regions")
	void dominantOutsideAll() {
		ZoomRegionUtils.DominantRegionResult result = ZoomRegionUtils.findDominantRegion(
				List.of(region("a", 1000, 2000, ZoomDepth.D2, 0.5, 0.5)), 10000);
		assertNull(result.region());
	}

	@Test
	@DisplayName("connects chained zooms when connectZooms is true")
	void dominantConnectedHandoff() {
		List<ZoomRegionSpec> regions = List.of(
				region("a", 1000, 3000, ZoomDepth.D2, 0.2, 0.2),
				region("b", 3500, 6000, ZoomDepth.D3, 0.8, 0.8));
		ZoomRegionUtils.DominantRegionResult result = ZoomRegionUtils.findDominantRegion(regions,
				3500, ZoomRegionUtils.DominantRegionOptions.connectZooms(true));
		assertEquals(1.0, result.strength());
		assertNull(result.transition());
		assertEquals("b", result.region().id());
	}

	@Test
	@DisplayName("keeps the outgoing region active until the connected transition begins")
	void dominantOutgoingRegionActive() {
		List<ZoomRegionSpec> regions = List.of(
				region("a", 1000, 3000, ZoomDepth.D2, 0.2, 0.2),
				region("b", 3500, 6000, ZoomDepth.D3, 0.8, 0.8));
		ZoomRegionUtils.DominantRegionResult result = ZoomRegionUtils.findDominantRegion(regions,
				3100, ZoomRegionUtils.DominantRegionOptions.connectZooms(true));
		assertNull(result.transition());
		assertEquals("a", result.region().id());
		assertTrue(result.strength() > 0);
	}

	@Test
	@DisplayName("keeps the incoming region at full strength after a connected handoff")
	void dominantIncomingFullStrength() {
		List<ZoomRegionSpec> regions = List.of(
				region("a", 1000, 3000, ZoomDepth.D2, 0.2, 0.2),
				region("b", 3500, 6000, ZoomDepth.D3, 0.8, 0.8));
		ZoomRegionUtils.DominantRegionResult result = ZoomRegionUtils.findDominantRegion(regions,
				4300, ZoomRegionUtils.DominantRegionOptions.connectZooms(true));
		assertNull(result.transition());
		assertEquals("b", result.region().id());
		assertEquals(1.0, result.strength());
	}

	@Test
	@DisplayName("does NOT connect zooms with a large gap")
	void dominantNoConnectWithGap() {
		List<ZoomRegionSpec> regions = List.of(
				region("a", 1000, 3000, ZoomDepth.D2, 0.2, 0.2),
				region("b", 8000, 10000, ZoomDepth.D3, 0.8, 0.8));
		ZoomRegionUtils.DominantRegionResult result = ZoomRegionUtils.findDominantRegion(regions,
				5000, ZoomRegionUtils.DominantRegionOptions.connectZooms(true));
		assertNull(result.region());
	}

	@Test
	@DisplayName("keeps the next region active during the connected transition")
	void dominantNextRegionDuringTransition() {
		List<ZoomRegionSpec> regions = List.of(
				region("a", 1000, 3000, ZoomDepth.D2, 0.2, 0.2),
				region("b", 4300, 7000, ZoomDepth.D3, 0.7, 0.7));
		ZoomRegionUtils.DominantRegionResult result = ZoomRegionUtils.findDominantRegion(regions,
				4250, ZoomRegionUtils.DominantRegionOptions.connectZooms(true));
		assertEquals(1.0, result.strength());
		assertNotNull(result.region());
		assertEquals("b", result.region().id());
	}

	// ---------------------------------------------------------------------------------------
	// ZoomRegion mode field
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("accepts manual mode")
	void regionAcceptsManualMode() {
		ZoomRegionSpec spec = new ZoomRegionSpec("m1", 0, 1000, ZoomDepth.D2,
				new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL);
		assertEquals(ZoomMode.MANUAL, spec.mode());
	}

	@Test
	@DisplayName("accepts auto mode")
	void regionAcceptsAutoMode() {
		ZoomRegionSpec spec = new ZoomRegionSpec("a1", 0, 1000, ZoomDepth.D2,
				new ZoomFocus(0.5, 0.5), ZoomMode.AUTO);
		assertEquals(ZoomMode.AUTO, spec.mode());
	}

	@Test
	@DisplayName("mode is optional")
	void regionModeOptional() {
		ZoomRegionSpec spec = new ZoomRegionSpec("x1", 0, 1000, ZoomDepth.D2,
				new ZoomFocus(0.5, 0.5), null);
		assertNull(spec.mode());
	}

	// ---------------------------------------------------------------------------------------
	// Spring physics — damping regimes
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("underdamped spring overshoots then converges")
	void underdampedSpringOvershoots() {
		SpringSmoothing.SpringConfig config = new SpringSmoothing.SpringConfig(300, 10, 1,
				0.0005, 0.015);
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		SpringSmoothing.stepSpringValue(state, 0, 16, config);
		double maxValue = 0;
		for (int i = 0; i < 500; i++) {
			SpringSmoothing.stepSpringValue(state, 1, 16, config);
			if (state.value > maxValue) {
				maxValue = state.value;
			}
		}
		assertTrue(maxValue > 1.01);
		assertEquals(1.0, state.value, 0.1);
	}

	@Test
	@DisplayName("overdamped spring converges without overshoot")
	void overdampedSpringNoOvershoot() {
		SpringSmoothing.SpringConfig config = new SpringSmoothing.SpringConfig(100, 200, 1,
				0.0005, 0.015);
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		SpringSmoothing.stepSpringValue(state, 0, 16, config);
		double maxValue = 0;
		for (int i = 0; i < 1000; i++) {
			SpringSmoothing.stepSpringValue(state, 1, 16, config);
			if (state.value > maxValue) {
				maxValue = state.value;
			}
		}
		assertTrue(maxValue <= 1.001);
		assertEquals(1.0, state.value, 0.01);
	}

	@Test
	@DisplayName("critically damped spring converges without oscillation")
	void criticallyDampedSpring() {
		double stiffness = 200;
		double mass = 1;
		double criticalDamping = 2 * Math.sqrt(stiffness * mass);
		SpringSmoothing.SpringConfig config = new SpringSmoothing.SpringConfig(stiffness,
				criticalDamping, mass, 0.0005, 0.015);
		SpringSmoothing.SpringState state = SpringSmoothing.createSpringState();
		SpringSmoothing.stepSpringValue(state, 0, 16, config);
		for (int i = 0; i < 300; i++) {
			SpringSmoothing.stepSpringValue(state, 1, 16, config);
		}
		assertEquals(1.0, state.value, 0.01);
	}
}
