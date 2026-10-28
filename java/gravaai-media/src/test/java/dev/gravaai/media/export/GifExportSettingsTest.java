package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences.GifSizePreset;
import dev.gravaai.media.export.GifExportSettings.Size;

/** Port of the size/loop properties of {@code src/lib/exporter/gifExporter.test.ts}. */
class GifExportSettingsTest {

	@Test
	@DisplayName("maps loop=true to repeat 0 (infinite) and loop=false to repeat 1 (once)")
	void loopMapping() {
		for (boolean loop : new boolean[] {true, false}) {
			assertEquals(loop ? 0 : 1, GifExportSettings.gifRepeat(loop));
		}
	}

	@Test
	@DisplayName("returns the original dimensions when the source is smaller than the preset cap")
	void keepsSmallSources() {
		for (int width : new int[] {100, 200, 320, 640, 1280}) {
			for (int height : new int[] {100, 180, 240, 360, 720}) {
				assertEquals(new Size(width, height),
						GifExportSettings.calculateOutputDimensions(width, height,
								GifSizePreset.MEDIUM));
			}
		}
	}

	@Test
	@DisplayName("preserves the aspect ratio while scaling down to a preset max height")
	void preservesAspectRatio() {
		int[][] sources = {{1920, 1080}, {1080, 1920}, {3840, 2160}, {1440, 1440}, {1366, 768}};
		for (int[] source : sources) {
			for (GifSizePreset preset : GifSizePreset.values()) {
				Size size = GifExportSettings.calculateOutputDimensions(source[0], source[1], preset);
				double originalRatio = source[0] / (double) source[1];
				double outputRatio = size.width() / (double) size.height();
				assertTrue(Math.abs(originalRatio - outputRatio) < 0.01,
						source[0] + "x" + source[1] + " with " + preset + " -> " + size);
				assertEquals(0, size.width() % 2);
				assertEquals(0, size.height() % 2);
			}
		}
	}

	@Test
	@DisplayName("original preset always keeps the source dimensions")
	void originalPreset() {
		assertEquals(new Size(1920, 1080),
				GifExportSettings.calculateOutputDimensions(1920, 1080, GifSizePreset.ORIGINAL));
		assertEquals(new Size(1280, 720),
				GifExportSettings.calculateOutputDimensions(1920, 1080, GifSizePreset.MEDIUM));
	}

	@Test
	@DisplayName("validates the GIF frame-rate presets")
	void frameRates() {
		for (int rate : new int[] {15, 20, 25, 30}) {
			assertTrue(GifExportSettings.isValidGifFrameRate(rate));
		}
		assertFalse(GifExportSettings.isValidGifFrameRate(24));
		assertFalse(GifExportSettings.isValidGifFrameRate(60));
	}
}