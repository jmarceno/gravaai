package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;

/** Port of {@code src/lib/exporter/exportBitrate.test.ts}. */
class Mp4ExportBitrateTest {

	@Test
	@DisplayName("uses the web-delivery source-quality caps")
	void sourceQualityCaps() {
		assertEquals(8_000_000, Mp4ExportBitrate.getSourceQualityBitrate(1280, 720));
		assertEquals(12_000_000, Mp4ExportBitrate.getSourceQualityBitrate(1920, 1080));
		assertEquals(45_000_000, Mp4ExportBitrate.getSourceQualityBitrate(3840, 2160));
		assertEquals(12_000_000, Mp4ExportBitrate.getMp4ExportBitrate(1920, 1080, 30,
				ExportQuality.SOURCE, ExportEncodingMode.QUALITY));
		assertEquals(9_600_000, Mp4ExportBitrate.getMp4ExportBitrate(1920, 1080, 30,
				ExportQuality.SOURCE, ExportEncodingMode.BALANCED));
	}

	@Test
	@DisplayName("raises high-resolution 60fps source-quality exports above the 30fps budget")
	void sixtyFpsScaling() {
		int thirtyFps = Mp4ExportBitrate.getMp4ExportBitrate(2560, 1440, 30, ExportQuality.SOURCE,
				ExportEncodingMode.QUALITY);
		int sixtyFps = Mp4ExportBitrate.getMp4ExportBitrate(2560, 1440, 60, ExportQuality.SOURCE,
				ExportEncodingMode.QUALITY);
		assertEquals(20_555_556, thirtyFps);
		assertTrue(sixtyFps > thirtyFps);
		assertEquals(29_069_946, sixtyFps);
	}

	@Test
	@DisplayName("keeps modern native static-layout source exports high enough for screen text")
	void nativeStaticLayoutKeepsPolicy() {
		assertEquals(9_600_000, Mp4ExportBitrate.getMp4ExportBitrate(1920, 1080, 30,
				ExportQuality.SOURCE, ExportEncodingMode.BALANCED, true));
		assertEquals(12_000_000, Mp4ExportBitrate.getMp4ExportBitrate(1920, 1080, 30,
				ExportQuality.SOURCE, ExportEncodingMode.QUALITY, true));
	}

	@Test
	@DisplayName("scales modern native static-layout source exports at 60fps")
	void nativeStaticLayoutSixtyFps() {
		int thirtyFps = Mp4ExportBitrate.getMp4ExportBitrate(1920, 1080, 30, ExportQuality.SOURCE,
				ExportEncodingMode.QUALITY, true);
		int sixtyFps = Mp4ExportBitrate.getMp4ExportBitrate(1920, 1080, 60, ExportQuality.SOURCE,
				ExportEncodingMode.QUALITY, true);
		assertEquals(12_000_000, thirtyFps);
		assertTrue(sixtyFps > thirtyFps);
		assertEquals(16_970_563, sixtyFps);
	}

	@Test
	@DisplayName("does not raise fast exports when the requested bitrate is already lower than the cap")
	void fastModeIsNotRaised() {
		assertEquals(6_000_000, Mp4ExportBitrate.getMp4ExportBitrate(1920, 1080, 30,
				ExportQuality.SOURCE, ExportEncodingMode.FAST, true));
	}

	@Test
	@DisplayName("scales the modern native cap with output pixel rate")
	void scalesWithPixelRate() {
		assertEquals(45_000_000, Mp4ExportBitrate.getMp4ExportBitrate(3840, 2160, 30,
				ExportQuality.SOURCE, ExportEncodingMode.QUALITY, true));
	}
}
