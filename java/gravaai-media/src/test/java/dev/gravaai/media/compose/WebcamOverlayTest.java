package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of src/components/video-editor/webcamOverlay.test.ts math (stage 7). */
class WebcamOverlayTest {

	@Test
	@DisplayName("scales authored pixels against the 1920 reference viewport")
	void scalesPixels() {
		assertEquals(24.0, WebcamOverlay.scalePixels(24, 1920), 1e-9);
		assertEquals(16.0, WebcamOverlay.scalePixels(24, 1280), 1e-9);
		assertEquals(0.0, WebcamOverlay.scalePixels(-5, 1280));
	}

	@Test
	@DisplayName("roundness percent becomes a squircle radius via sqrt strengthening")
	void cornerRadius() {
		assertEquals(25.0, WebcamOverlay.cornerRadiusPx(100, 80, 50), 1e-9);
		assertEquals(25.0 * Math.sqrt(0.25), WebcamOverlay.cornerRadiusPx(25, 80, 50), 1e-9);
		assertEquals(0.0, WebcamOverlay.cornerRadiusPx(0, 80, 50));
	}

	@Test
	@DisplayName("positions presets and custom coordinates")
	void positions() {
		assertEquals(new WebcamOverlay.Position(0, 0),
				WebcamOverlay.positionForPreset("top-left"));
		assertEquals(new WebcamOverlay.Position(0.5, 1),
				WebcamOverlay.positionForPreset("bottom-center"));
		assertEquals(new WebcamOverlay.Position(1, 1),
				WebcamOverlay.positionForPreset("bottom-right"));
		WebcamOverlay.Position custom = WebcamOverlay.position(400, 200, 50, 50, 0, "custom",
				0.25, 0.75, "bottom-right");
		assertEquals(87.5, custom.x(), 1e-9);
		assertEquals(112.5, custom.y(), 1e-9);
	}

	@Test
	@DisplayName("overlay size clamps to the minimum and reacts to zoom inversely")
	void overlaySize() {
		assertEquals(56.0, WebcamOverlay.sizePx(400, 200, 10, 0, 1, false), 1e-9);
		double normal = WebcamOverlay.sizePx(400, 200, 50, 0, 1, false);
		double zoomed = WebcamOverlay.sizePx(400, 200, 50, 0, 2, true);
		// 200 * 50% / 2 = 50 px would be below the 56 px floor, so the clamp wins.
		assertEquals(56.0, zoomed, 1e-9);
		assertEquals(normal, WebcamOverlay.sizePx(400, 200, 50, 0, 2, false), 1e-9);
		// 200 * 80% = 160 px, counter-scaled by zoom 2 => 80 px.
		assertEquals(80.0, WebcamOverlay.sizePx(400, 200, 80, 0, 2, true), 1e-9);
		WebcamOverlay.Size size = WebcamOverlay.dimensionsPx(400, 200, 40, 20, 10, 1, false);
		assertEquals(80, size.width(), 1e-9);
		// 200 * 20% = 40 px is below the 56 px floor.
		assertEquals(56, size.height(), 1e-9);
	}

	@Test
	@DisplayName("crop normalisation and source rectangles clamp to the frame")
	void cropMath() {
		LayoutCrop crop = WebcamOverlay.normalizeCrop(new LayoutCrop(-1, -1, 2, 2));
		assertEquals(0, crop.x());
		assertEquals(0, crop.y());
		assertEquals(1.0, crop.width(), 1e-9);
		assertFalse(WebcamOverlay.isDefaultCrop(new LayoutCrop(0.2, 0.2, 0.5, 0.5)));
		assertTrue(WebcamOverlay.isDefaultCrop(LayoutCrop.FULL));
		WebcamOverlay.CropRect rect = WebcamOverlay.cropSourceRect(
				new LayoutCrop(0.5, 0.5, 0.5, 0.5), 100, 80);
		assertEquals(50, rect.sx(), 1e-9);
		assertEquals(40, rect.sy(), 1e-9);
		assertEquals(50, rect.sw(), 1e-9);
		assertEquals(40, rect.sh(), 1e-9);
	}

	@Test
	@DisplayName("crop-matched height keeps the cropped webcam aspect")
	void cropMatchedHeight() {
		assertEquals(40.0, WebcamOverlay.cropMatchedHeightPercent(40, 40, null, null,
				LayoutCrop.FULL), 1e-9);
		// 1600x900 source cropped to half height: crop aspect 3.556, so 40 / 3.556 = 11.25.
		double height = WebcamOverlay.cropMatchedHeightPercent(40, 40, 1600.0, 900.0,
				new LayoutCrop(0, 0, 1, 0.5));
		assertEquals(11.25, height, 1e-3);
	}

	@Test
	@DisplayName("shadow strength doubles and produces two webcam shadow layers")
	void shadowLayers() {
		assertEquals(0.7, WebcamOverlay.shadowStrength(0.35), 1e-9);
		assertEquals(1.0, WebcamOverlay.shadowStrength(2));
		assertTrue(WebcamOverlay.shadowLayers(100, 0).isEmpty());
		List<WebcamOverlay.ShadowLayer> layers = WebcamOverlay.shadowLayers(100, 0.5);
		assertEquals(2, layers.size());
		// A tight shadow: the reach is a few percent of the overlay, the strength only fades it.
		assertEquals(2.5, layers.get(0).offsetY(), 1e-9);
		assertEquals(3.5, layers.get(0).blur(), 1e-9);
		assertEquals(0.45, layers.get(0).alpha(), 1e-9);
	}
}
