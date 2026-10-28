package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the numeric parts of aspectRatioUtils.ts (plan §5.8 stage 2, aspect). */
class AspectRatioMathTest {

	@Test
	@DisplayName("preset aspect ratios use their exact values")
	void presetValues() {
		assertEquals(16.0 / 9.0, AspectRatioMath.aspectRatioValue("16:9"), 1e-12);
		assertEquals(9.0 / 16.0, AspectRatioMath.aspectRatioValue("9:16"), 1e-12);
		assertEquals(1.0, AspectRatioMath.aspectRatioValue("1:1"), 1e-12);
		assertEquals(4.0 / 3.0, AspectRatioMath.aspectRatioValue("4:3"), 1e-12);
		assertEquals(4.0 / 5.0, AspectRatioMath.aspectRatioValue("4:5"), 1e-12);
		assertEquals(16.0 / 10.0, AspectRatioMath.aspectRatioValue("16:10"), 1e-12);
		assertEquals(10.0 / 16.0, AspectRatioMath.aspectRatioValue("10:16"), 1e-12);
	}

	@Test
	@DisplayName("native and unknown ratios fall back to 16:9")
	void fallbacks() {
		assertEquals(16.0 / 9.0, AspectRatioMath.aspectRatioValue("native"), 1e-12);
		assertEquals(2.0, AspectRatioMath.aspectRatioValue("native", 2.0), 1e-12);
		assertEquals(16.0 / 9.0, AspectRatioMath.aspectRatioValue("not-a-ratio"), 1e-12);
		assertEquals(16.0 / 9.0, AspectRatioMath.aspectRatioValue(null), 1e-12);
	}

	@Test
	@DisplayName("custom W:H ratios are parsed")
	void customRatios() {
		assertTrue(AspectRatioMath.isCustomAspectRatio("3:2"));
		assertEquals(1.5, AspectRatioMath.aspectRatioValue("3:2"), 1e-12);
		assertFalse(AspectRatioMath.isCustomAspectRatio("16:9"));
		assertFalse(AspectRatioMath.isCustomAspectRatio("0:1"));
		assertFalse(AspectRatioMath.isCustomAspectRatio("bad"));
	}

	@Test
	@DisplayName("dimensions for a fixed width round the height to whole pixels")
	void dimensionsForWidth() {
		RenderTargetSize landscape = AspectRatioMath.dimensionsForWidth("16:9", 1280);
		assertEquals(1280, landscape.width());
		assertEquals(720, landscape.height());

		RenderTargetSize portrait = AspectRatioMath.dimensionsForWidth("9:16", 1280);
		assertEquals(1280, portrait.width());
		assertEquals(2276, portrait.height());
	}
}
