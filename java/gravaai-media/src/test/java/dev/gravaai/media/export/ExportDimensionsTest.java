package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences.ExportQuality;
import dev.gravaai.media.export.ExportDimensions.CropBounds;
import dev.gravaai.media.export.ExportDimensions.ProbeSnapshot;
import dev.gravaai.media.export.ExportDimensions.Size;

/** Port of {@code src/components/video-editor/exportDimensions.test.ts}. */
class ExportDimensionsTest {

	@Test
	@DisplayName("caps landscape shares at 1080p without upscaling")
	void capLandscape() {
		assertEquals(new Size(1920, 1080), ExportDimensions.capMp4ShareDimensions(3840, 2160));
		assertEquals(new Size(1280, 720), ExportDimensions.capMp4ShareDimensions(1280, 720));
	}

	@Test
	@DisplayName("caps portrait and square shares within equivalent 1080p bounds")
	void capPortrait() {
		assertEquals(new Size(1080, 1920), ExportDimensions.capMp4ShareDimensions(2160, 3840));
		assertEquals(new Size(1080, 1080), ExportDimensions.capMp4ShareDimensions(2160, 2160));
	}

	@Test
	@DisplayName("keeps unusual aspect ratios and even dimensions")
	void capUnusual() {
		assertEquals(new Size(1920, 802), ExportDimensions.capMp4ShareDimensions(3441, 1441));
	}

	@Test
	@DisplayName("keeps native exports at the source dimensions")
	void nativeSourceDimensions() {
		assertEquals(new Size(1920, 1080),
				ExportDimensions.calculateMp4SourceDimensions(1920, 1080, "native"));
	}

	@Test
	@DisplayName("uses the cropped source bounds for native exports")
	void nativeCropBounds() {
		assertEquals(new Size(320, 144), ExportDimensions.calculateMp4SourceDimensions(320, 180,
				"native", new CropBounds(1, 0.8)));
	}

	@Test
	@DisplayName("uses the rotated source bounds for 9:16 original exports")
	void portraitSourceDimensions() {
		assertEquals(new Size(1080, 1920),
				ExportDimensions.calculateMp4SourceDimensions(1920, 1080, "9:16"));
	}

	@Test
	@DisplayName("ignores crop bounds for fixed-aspect exports")
	void fixedAspectIgnoresCrop() {
		assertEquals(new Size(1080, 1920), ExportDimensions.calculateMp4SourceDimensions(1920, 1080,
				"9:16", new CropBounds(0.5, 0.5)));
	}

	@Test
	@DisplayName("uses the rotated source bounds for portrait social ratios")
	void fourFiveSourceDimensions() {
		assertEquals(new Size(1080, 1350),
				ExportDimensions.calculateMp4SourceDimensions(1920, 1080, "4:5"));
	}

	@Test
	@DisplayName("keeps landscape aspect-ratio exports inside the source bounds")
	void fourThreeSourceDimensions() {
		assertEquals(new Size(1440, 1080),
				ExportDimensions.calculateMp4SourceDimensions(1920, 1080, "4:3"));
	}

	@Test
	@DisplayName("normalizes odd source dimensions to even export dimensions")
	void normalizesOddDimensions() {
		Size source = ExportDimensions.calculateMp4SourceDimensions(1919, 1079, "native");
		assertEquals(new Size(1918, 1078), source);
		assertEquals(new Size(1918, 1078),
				ExportDimensions.calculateMp4ExportDimensions(source.width(), source.height(),
						ExportQuality.SOURCE));
		assertEquals(new Size(1726, 970),
				ExportDimensions.calculateMp4ExportDimensions(source.width(), source.height(),
						ExportQuality.HIGH));
	}

	@Test
	@DisplayName("scales portrait output dimensions from the aspect target")
	void scalesPortrait() {
		Size source = ExportDimensions.calculateMp4SourceDimensions(1920, 1080, "9:16");
		assertEquals(new Size(1080, 1920),
				ExportDimensions.calculateMp4ExportDimensions(source.width(), source.height(),
						ExportQuality.SOURCE));
		assertEquals(new Size(972, 1728),
				ExportDimensions.calculateMp4ExportDimensions(source.width(), source.height(),
						ExportQuality.HIGH));
	}

	@Test
	@DisplayName("debounces only native crop-driven target changes")
	void debouncesNativeCropChanges() {
		ProbeSnapshot base = new ProbeSnapshot(1920, 1080, 1920, 1080, "native", 30);
		assertTrue(ExportDimensions.shouldDebounceMp4SupportProbe(base,
				new ProbeSnapshot(1920, 1080, 1920, 864, "native", 30)));
	}

	@Test
	@DisplayName("keeps non-crop probe changes immediate")
	void keepsOtherProbeChangesImmediate() {
		ProbeSnapshot base = new ProbeSnapshot(1920, 1080, 1920, 1080, "native", 30);
		assertFalse(ExportDimensions.shouldDebounceMp4SupportProbe(null, base));
		assertFalse(ExportDimensions.shouldDebounceMp4SupportProbe(base,
				new ProbeSnapshot(1920, 1080, 1920, 1080, "native", 60)));
		assertFalse(ExportDimensions.shouldDebounceMp4SupportProbe(base,
				new ProbeSnapshot(1280, 720, 1280, 720, "native", 30)));
		assertFalse(ExportDimensions.shouldDebounceMp4SupportProbe(base,
				new ProbeSnapshot(1920, 1080, 1920, 1080, "16:9", 30)));
	}
}
