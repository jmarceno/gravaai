package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;
import dev.gravaai.media.export.Mp4ExportSettings.ExportSettingsOverrides;
import dev.gravaai.media.export.Mp4ExportSettings.SmokeExportConfig;

/** Port of {@code src/components/video-editor/mp4ExportSettings.test.ts}. */
class Mp4ExportSettingsTest {

	private static final SmokeExportConfig NO_SMOKE =
			new SmokeExportConfig(false, null, null, null);

	@Test
	@DisplayName("uses editor defaults when neither menu settings nor smoke settings override them")
	void editorDefaults() {
		Mp4ExportSettings resolved = Mp4ExportSettings.resolve(NO_SMOKE, null, ExportQuality.HIGH,
				ExportEncodingMode.BALANCED, 30);
		assertEquals(ExportQuality.HIGH, resolved.quality());
		assertEquals(ExportEncodingMode.BALANCED, resolved.encodingMode());
		assertEquals(30, resolved.selectedMp4FrameRate());
	}

	@Test
	@DisplayName("prefers explicit menu settings over editor defaults for normal exports")
	void menuOverridesDefaults() {
		Mp4ExportSettings resolved = Mp4ExportSettings.resolve(NO_SMOKE,
				new ExportSettingsOverrides(ExportQuality.SOURCE, ExportEncodingMode.QUALITY, 60),
				ExportQuality.HIGH, ExportEncodingMode.BALANCED, 30);
		assertEquals(ExportQuality.SOURCE, resolved.quality());
		assertEquals(ExportEncodingMode.QUALITY, resolved.encodingMode());
		assertEquals(60, resolved.selectedMp4FrameRate());
	}

	@Test
	@DisplayName("prefers smoke URL settings over menu settings when smoke export is enabled")
	void smokeOverridesMenu() {
		Mp4ExportSettings resolved = Mp4ExportSettings.resolve(
				new SmokeExportConfig(true, ExportQuality.MEDIUM, ExportEncodingMode.FAST, 24),
				new ExportSettingsOverrides(ExportQuality.SOURCE, ExportEncodingMode.QUALITY, 60),
				ExportQuality.HIGH, ExportEncodingMode.BALANCED, 30);
		assertEquals(ExportQuality.MEDIUM, resolved.quality());
		assertEquals(ExportEncodingMode.FAST, resolved.encodingMode());
		assertEquals(24, resolved.selectedMp4FrameRate());
	}

	@Test
	@DisplayName("falls back from incomplete smoke settings to menu settings and editor defaults")
	void partialSmokeFallsBack() {
		Mp4ExportSettings resolved = Mp4ExportSettings.resolve(
				new SmokeExportConfig(true, ExportQuality.GOOD, null, null),
				new ExportSettingsOverrides(null, ExportEncodingMode.QUALITY, null),
				ExportQuality.HIGH, ExportEncodingMode.BALANCED, 30);
		assertEquals(ExportQuality.GOOD, resolved.quality());
		assertEquals(ExportEncodingMode.QUALITY, resolved.encodingMode());
		assertEquals(30, resolved.selectedMp4FrameRate());
	}
}
