package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of sceneEffects.ts {@code getSceneEffectMetrics} (plan §5.8 stage 1). */
class SceneEffectsTest {

	@Test
	@DisplayName("effects scale with viewportWidth / 640")
	void scalesWithViewportWidth() {
		SceneEffects.Metrics metrics = SceneEffects.backgroundMetrics(1920, 10);
		assertEquals(3.0, metrics.viewportScale(), 1e-9);
		assertEquals(30.0, metrics.backgroundBlurPx(), 1e-9);
		assertEquals(60, metrics.backgroundOverscanPx());
	}

	@Test
	@DisplayName("the reference width is a no-op scale")
	void referenceWidthIsUnitScale() {
		SceneEffects.Metrics metrics = SceneEffects.backgroundMetrics(640, 5);
		assertEquals(1.0, metrics.viewportScale(), 1e-9);
		assertEquals(5.0, metrics.backgroundBlurPx(), 1e-9);
		assertEquals(10, metrics.backgroundOverscanPx());
	}

	@Test
	@DisplayName("a zero or negative blur is clamped to zero")
	void zeroAndNegativeBlurClamp() {
		assertEquals(0.0, SceneEffects.backgroundMetrics(1920, 0).backgroundBlurPx(), 1e-9);
		assertEquals(0.0, SceneEffects.backgroundMetrics(1920, -4).backgroundBlurPx(), 1e-9);
		assertEquals(0, SceneEffects.backgroundMetrics(1920, -4).backgroundOverscanPx());
	}

	@Test
	@DisplayName("a zero viewport width behaves like one pixel")
	void zeroViewportClampsToOne() {
		assertEquals(1.0 / 640.0, SceneEffects.backgroundMetrics(0, 0).viewportScale(), 1e-12);
	}

	@Test
	@DisplayName("the three video drop-shadow layers scale with viewport and intensity")
	void shadowLayersScale() {
		java.util.List<SceneEffects.ShadowLayer> layers = SceneEffects.shadowLayers(1280, 0.67);
		assertEquals(3, layers.size());
		// scale = 1280 / 640 = 2, so offset/blur double and the alpha is intensity-scaled.
		assertEquals(12 * 0.67 * 2, layers.get(0).offsetY(), 1e-9);
		assertEquals(48 * 0.67 * 2, layers.get(0).blur(), 1e-9);
		assertEquals(0.7 * 0.67, layers.get(0).alpha(), 1e-9);
		assertEquals(4 * 0.67 * 2, layers.get(1).offsetY(), 1e-9);
		assertEquals(2 * 0.67 * 2, layers.get(2).offsetY(), 1e-9);
	}

	@Test
	@DisplayName("the reference viewport leaves the shadow profile unscaled")
	void shadowLayersAtReferenceWidth() {
		java.util.List<SceneEffects.ShadowLayer> layers = SceneEffects.shadowLayers(640, 1.0);
		assertEquals(12, layers.get(0).offsetY(), 1e-9);
		assertEquals(48, layers.get(0).blur(), 1e-9);
		assertEquals(0.7, layers.get(0).alpha(), 1e-9);
		assertEquals(4, layers.get(1).offsetY(), 1e-9);
		assertEquals(2, layers.get(2).offsetY(), 1e-9);
	}

	@Test
	@DisplayName("no shadow layers at zero or negative intensity")
	void noShadowLayersWithoutIntensity() {
		assertTrue(SceneEffects.shadowLayers(1280, 0).isEmpty());
		assertTrue(SceneEffects.shadowLayers(1280, -1).isEmpty());
	}

	@Test
	@DisplayName("overscan is ceil(blur * 2)")
	void overscanCeil() {
		// 1000 / 640 * 3 = 4.6875 -> ceil(9.375) = 10
		SceneEffects.Metrics metrics = SceneEffects.backgroundMetrics(1000, 3);
		assertEquals(4.6875, metrics.backgroundBlurPx(), 1e-9);
		assertEquals(10, metrics.backgroundOverscanPx());
	}
}
