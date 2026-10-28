package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.settings.AppSettingsStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Port of the preference half of {@code editorPreferences.test.ts} (storage adapted to AppSettingsStore). */
class EditorPreferencesTest {

	@TempDir
	Path directory;

	private AppSettingsStore settings;
	private EditorPreferencesStore store;

	@BeforeEach
	void setUp() {
		settings = new AppSettingsStore(directory.resolve("app-settings.json"));
		store = new EditorPreferencesStore(settings);
	}

	private static JsonNode json(String contents) {
		return Json.parseJsonWithByteOrderMark(contents);
	}

	private static EditorPreferences normalize(String contents) {
		return EditorPreferences.normalize(json(contents), EditorPreferences.defaults());
	}

	@Test
	@DisplayName("migrates legacy pixels once and marks the stored unit")
	void migratesLegacyPixelsOnce() {
		assertEquals(5, normalize("{\"borderRadius\":54}").borderRadius());
		assertEquals("percent", normalize("{\"borderRadius\":54}").borderRadiusUnit());
		assertEquals(8, normalize("{\"borderRadius\":8,\"borderRadiusUnit\":\"percent\"}").borderRadius());
	}

	@Test
	@DisplayName("only replaces a legacy zero radius on macOS")
	void replacesLegacyZeroRadiusOnMacOnly() {
		double platformDefault = ProjectPersistence.getDefaultBorderRadiusPercent();
		assertEquals(platformDefault, normalize("{\"borderRadius\":0}").borderRadius());
		// The macOS branch is covered by ProjectPersistence.getDefaultBorderRadiusPercent("MacIntel").
	}

	@Test
	@DisplayName("normalizes invalid values back to safe defaults")
	void normalizesInvalidValues() {
		EditorPreferences preferences = normalize("""
				{"wallpaper":123,"showCursor":"yes","cropRegion":{"x":2,"width":-1},
				"aspectRatio":"bad-value","customAspectWidth":"0","customAspectHeight":"",
				"customWallpapers":"not-an-array"}
				""");
		EditorPreferences defaults = EditorPreferences.defaults();
		assertEquals(defaults.wallpaper(), preferences.wallpaper());
		assertEquals(defaults.showCursor(), preferences.showCursor());
		assertEquals(defaults.aspectRatio(), preferences.aspectRatio());
		assertEquals(defaults.cursorStyle(), preferences.cursorStyle());
		assertEquals(defaults.cursorSize(), preferences.cursorSize());
		assertEquals("16", preferences.customAspectWidth());
		assertEquals("9", preferences.customAspectHeight());
		assertEquals(java.util.List.of(), preferences.customWallpapers());
	}

	@Test
	@DisplayName("defaults MP4 exports to source quality and the modern pipeline")
	void defaultsExportMigration() {
		EditorPreferences defaults = EditorPreferences.defaults();
		assertEquals(ExportPreferences.ExportQuality.SOURCE, defaults.exportQuality());
		assertEquals(ExportPreferences.ExportPipelineModel.MODERN, defaults.exportPipelineModel());
		assertEquals("modern", normalize("{\"exportPipelineModel\":\"legacy\"}").exportPipelineModel().wire());
	}

	@Test
	@DisplayName("defaults cursor preferences to Tahoe at 2.5x with gentler sway")
	void defaultsCursorPreferences() {
		EditorPreferences defaults = EditorPreferences.defaults();
		assertEquals("tahoe", defaults.cursorStyle());
		assertEquals(2.5, defaults.cursorSize());
		assertEquals(0.4, defaults.cursorSway());
	}

	@Test
	@DisplayName("bakes in the stronger split motion blur defaults")
	void bakesInMotionBlurDefaults() {
		var tuning = EditorPreferences.defaults().zoomMotionBlurTuning();
		assertEquals(0, tuning.panVelocityThreshold());
		assertEquals(0, tuning.zoomVelocityThreshold());
		assertEquals(41.8, tuning.maxDirectionalBlurPx());
		assertEquals(1, tuning.maxRadialBlurStrength());
	}

	@Test
	@DisplayName("ignores legacy persisted dev-only motion blur overrides")
	void ignoresPersistedDevMotionBlurOverrides() throws Exception {
		settings.writeSetting(EditorPreferences.EDITOR_PREFERENCES_STORAGE_KEY, json("""
				{"zoomMotionBlurTuning":{"panVelocityThreshold":40,"zoomVelocityThreshold":0.2,
				"maxDirectionalBlurPx":96,"maxRadialBlurStrength":1.5,"panResponsePerSecond":22,
				"zoomResponsePerSecond":18,"zoomSafeZoneRadiusPx":40}}
				"""));

		assertEquals(EditorPreferences.defaults().zoomMotionBlurTuning(),
				store.loadPreferences().zoomMotionBlurTuning());
	}

	@Test
	@DisplayName("does not save dev-only split blur tuning overrides to editor preferences")
	void doesNotSaveDevMotionBlurOverrides() {
		store.savePreferences(json("""
				{"zoomMotionBlurTuning":{"panVelocityThreshold":0,"zoomVelocityThreshold":0,
				"maxDirectionalBlurPx":96,"maxRadialBlurStrength":1.5,"panResponsePerSecond":11,
				"zoomResponsePerSecond":9,"zoomSafeZoneRadiusPx":6}}
				"""));

		JsonNode stored = settings.readSetting(EditorPreferences.EDITOR_PREFERENCES_STORAGE_KEY);
		assertTrue(stored != null && !stored.has("zoomMotionBlurTuning"));
	}

	@Test
	@DisplayName("loads stored editor control preferences")
	void loadsStoredControlPreferences() throws Exception {
		settings.writeSetting(EditorPreferences.EDITOR_PREFERENCES_STORAGE_KEY, json("""
				{"wallpaper":"#123456","backgroundBlur":3.5,"showCursor":false,
				"cropRegion":{"x":0.1,"y":0.2,"width":0.7,"height":0.6},"aspectRatio":"native",
				"exportFormat":"gif","gifFrameRate":30,"gifLoop":false,
				"customAspectWidth":"21","customAspectHeight":"9",
				"customWallpapers":["data:image/jpeg;base64,abc"]}
				"""));

		EditorPreferences loaded = store.loadPreferences();
		assertEquals("#123456", loaded.wallpaper());
		assertEquals(3.5, loaded.backgroundBlur());
		assertEquals(false, loaded.showCursor());
		assertEquals("native", loaded.aspectRatio());
		assertEquals(200, loaded.zoomInOverlapMs());
		assertEquals(ExportPreferences.ExportFormat.GIF, loaded.exportFormat());
		assertEquals(30, loaded.gifFrameRate());
		assertEquals(false, loaded.gifLoop());
		assertEquals("21", loaded.customAspectWidth());
		assertEquals("9", loaded.customAspectHeight());
		assertEquals(java.util.List.of("data:image/jpeg;base64,abc"), loaded.customWallpapers());
	}

	@Test
	@DisplayName("preserves the last valid custom aspect inputs while typing")
	void preservesLastValidCustomAspectInputs() throws Exception {
		settings.writeSetting(EditorPreferences.EDITOR_PREFERENCES_STORAGE_KEY, json("""
				{"aspectRatio":"16:9","customAspectWidth":"21","customAspectHeight":"9"}
				"""));

		store.savePreferences(json("{\"customAspectWidth\":\"\",\"customAspectHeight\":\"abc\"}"));

		EditorPreferences loaded = store.loadPreferences();
		assertEquals("16:9", loaded.aspectRatio());
		assertEquals("21", loaded.customAspectWidth());
		assertEquals("9", loaded.customAspectHeight());
	}

	@Test
	@DisplayName("preserves custom Whisper paths from stored preferences")
	void preservesWhisperPaths() throws Exception {
		settings.writeSetting(EditorPreferences.EDITOR_PREFERENCES_STORAGE_KEY, json("""
				{"whisperExecutablePath":"/usr/local/bin/whisper-cli",
				"whisperModelPath":"/Users/test/models/ggml-base.bin"}
				"""));
		EditorPreferences loaded = store.loadPreferences();
		assertEquals("/usr/local/bin/whisper-cli", loaded.whisperExecutablePath());
		assertEquals("/Users/test/models/ggml-base.bin", loaded.whisperModelPath());
	}

	@Test
	@DisplayName("saves all editor controls with normalization")
	void savesAllEditorControls() {
		store.savePreferences(json("""
				{"wallpaper":"linear-gradient(to right, #000000, #ffffff)","shadowIntensity":0.4,
				"backgroundBlur":1.5,"zoomMotionBlur":0.75,"connectZooms":false,
				"showCursor":false,"loopCursor":true,"cursorStyle":"figma","cursorSize":3,
				"cursorSmoothing":1.25,"cursorSway":1.5,"borderRadius":18,
				"padding":{"top":30,"right":30,"bottom":30,"left":30,"linked":true},
				"aspectRatio":"4:5","exportEncodingMode":"quality","exportFormat":"gif",
				"gifFrameRate":20,"gifLoop":false,"gifSizePreset":"large",
				"customAspectWidth":"4","customAspectHeight":"5",
				"customWallpapers":["data:image/jpeg;base64,abc","data:image/jpeg;base64,abc"],
				"autoApplyFreshRecordingAutoZooms":false}
				"""));

		EditorPreferences loaded = store.loadPreferences();
		assertEquals("linear-gradient(to right, #000000, #ffffff)", loaded.wallpaper());
		assertEquals(0.4, loaded.shadowIntensity());
		assertEquals(1.5, loaded.backgroundBlur());
		assertEquals(0.75, loaded.zoomMotionBlur());
		assertEquals(false, loaded.connectZooms());
		assertEquals(200, loaded.zoomInOverlapMs());
		assertEquals(false, loaded.showCursor());
		assertEquals(true, loaded.loopCursor());
		assertEquals("figma", loaded.cursorStyle());
		assertEquals(EditorPreferences.defaults().cursorSize(), loaded.cursorSize());
		assertEquals(1.5, loaded.cursorSway());
		assertEquals(18, loaded.borderRadius());
		assertEquals(new dev.gravaai.core.project.Padding(30, 30, 30, 30, true), loaded.padding());
		assertEquals("4:5", loaded.aspectRatio());
		assertEquals(ExportPreferences.ExportEncodingMode.QUALITY, loaded.exportEncodingMode());
		assertEquals(20, loaded.gifFrameRate());
		assertEquals(ExportPreferences.GifSizePreset.LARGE, loaded.gifSizePreset());
		assertEquals(java.util.List.of("data:image/jpeg;base64,abc"), loaded.customWallpapers());
		assertEquals(false, loaded.autoApplyFreshRecordingAutoZooms());
	}

	@Test
	@DisplayName("saves custom Whisper paths")
	void savesCustomWhisperPaths() {
		store.savePreferences(json("""
				{"whisperExecutablePath":"/opt/homebrew/bin/whisper-cli",
				"whisperModelPath":"/Users/test/models/ggml-small.bin"}
				"""));
		EditorPreferences loaded = store.loadPreferences();
		assertEquals("/opt/homebrew/bin/whisper-cli", loaded.whisperExecutablePath());
		assertEquals("/Users/test/models/ggml-small.bin", loaded.whisperModelPath());
	}

	@Test
	@DisplayName("saves and reloads publish and export settings")
	void savesPublishAndExportSettings() {
		assertEquals(ExportPreferences.PublishDestination.LINK,
				store.loadPreferences().publishDestination());
		store.savePreferences(json("""
				{"publishDestination":"local","exportQuality":"high","mp4FrameRate":60,
				"includeCaptionSidecar":true}
				"""));
		EditorPreferences loaded = store.loadPreferences();
		assertEquals(ExportPreferences.PublishDestination.LOCAL, loaded.publishDestination());
		assertEquals(ExportPreferences.ExportQuality.HIGH, loaded.exportQuality());
		assertEquals(60, loaded.mp4FrameRate());
		assertEquals(true, loaded.includeCaptionSidecar());

		store.savePreferences(json("{\"wallpaper\":\"#123456\"}"));
		assertEquals(ExportPreferences.PublishDestination.LOCAL,
				store.loadPreferences().publishDestination());
		assertEquals(ExportPreferences.PublishDestination.LINK,
				EditorPreferences.normalize(json("{\"publishDestination\":null}"),
						EditorPreferences.defaults()).publishDestination());
	}

	@Test
	@DisplayName("ignores unknown whisper model ids")
	void ignoresUnknownWhisperModels() throws Exception {
		settings.writeSetting(EditorPreferences.EDITOR_PREFERENCES_STORAGE_KEY,
				json("{\"whisperModel\":\"ggml-medium\"}"));
		assertNull(store.loadPreferences().whisperModel());
		assertEquals(WhisperModel.SMALL,
				EditorPreferences.normalize(json("{\"whisperModel\":\"small\"}"),
						EditorPreferences.defaults()).whisperModel());
	}
}
