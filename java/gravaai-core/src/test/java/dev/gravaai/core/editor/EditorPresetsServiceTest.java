package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.settings.AppSettingsStore;
import tools.jackson.databind.node.ObjectNode;

/** Port of {@code presets/useEditorPresets.ts} (storage and UI toasts adapted to core). */
class EditorPresetsServiceTest {

	@TempDir
	Path directory;

	private AppSettingsStore settings;
	private EditorPreferencesStore store;
	private EditorPresetsService service;

	@BeforeEach
	void setUp() {
		settings = new AppSettingsStore(directory.resolve("app-settings.json"));
		store = new EditorPreferencesStore(settings);
		service = new EditorPresetsService(store);
	}

	private static EditorPresetSnapshot snapshot(String cropJson) {
		ObjectNode snapshot = EditorPreferences.defaults().toJson();
		snapshot.set("cropRegion", Json.parseJsonWithByteOrderMark(cropJson));
		snapshot.set("autoCaptionSettings", Json.parseJsonWithByteOrderMark("{}"));
		snapshot.set("keystrokeOverlay", Json.parseJsonWithByteOrderMark("{}"));
		return EditorPresets.normalizeSnapshot(snapshot);
	}

	@Test
	@DisplayName("rejects an empty preset name")
	void rejectsEmptyName() {
		assertEquals(EditorPresetsService.SaveStatus.EMPTY_NAME,
				service.savePreset("   ", snapshot("{}")).status());
	}

	@Test
	@DisplayName("saves a preset with a normalised name and activates it")
	void savesPreset() {
		EditorPresetsService.SaveResult result = service.savePreset("  My   Preset ",
				snapshot("{\"x\":0.1,\"y\":0.2,\"width\":0.7,\"height\":0.6}"));

		assertEquals(EditorPresetsService.SaveStatus.SAVED, result.status());
		assertEquals(1, result.presets().size());
		assertEquals("My Preset", result.presets().get(0).name());
		assertEquals(result.presets().get(0).id(), result.activePresetId());
		assertEquals(result.presets().get(0).id(), service.activePresetId());
		assertNotNull(settings.readSetting(EditorPreferences.EDITOR_PRESETS_STORAGE_KEY));

		EditorPresetsService reloaded = new EditorPresetsService(store);
		assertEquals(1, reloaded.presets().size());
		assertEquals(new CropRegion(0.1, 0.2, 0.7, 0.6), reloaded.presets().get(0).snapshot().cropRegion());
	}

	@Test
	@DisplayName("rejects duplicate names case-insensitively")
	void rejectsDuplicateNames() {
		service.savePreset("Demo", snapshot("{}"));

		assertEquals(EditorPresetsService.SaveStatus.DUPLICATE_NAME,
				service.savePreset("demo", snapshot("{}")).status());
	}

	@Test
	@DisplayName("reports save failure when persistence fails")
	void reportsSaveFailure() throws Exception {
		Files.createDirectory(directory.resolve("app-settings.json"));
		AppSettingsStore failing = new AppSettingsStore(directory.resolve("app-settings.json"));
		EditorPresetsService failingService = new EditorPresetsService(new EditorPreferencesStore(failing));

		assertEquals(EditorPresetsService.SaveStatus.SAVE_FAILED,
				failingService.savePreset("Demo", snapshot("{}")).status());
	}

	@Test
	@DisplayName("deletes a preset and clears the active id")
	void deletesPreset() {
		EditorPresetsService.SaveResult saved = service.savePreset("Demo", snapshot("{}"));
		String id = saved.presets().get(0).id();

		EditorPresetsService.DeleteResult result = service.deletePreset(id);

		assertEquals(EditorPresetsService.DeleteStatus.DELETED, result.status());
		assertEquals(List.of(), result.presets());
		assertNull(result.activePresetId());
		assertEquals(EditorPresetsService.DeleteStatus.NOT_FOUND,
				service.deletePreset("missing").status());
	}

	@Test
	@DisplayName("finds the preset that matches the current snapshot signature")
	void matchesCurrentSnapshot() {
		EditorPresetSnapshot first = snapshot("{\"x\":0.1,\"y\":0.2,\"width\":0.7,\"height\":0.6}");
		EditorPresetSnapshot second = snapshot("{}");
		String id = service.savePreset("First", first).presets().get(0).id();

		assertEquals(id, service.matchingPresetId(first));
		assertNull(service.matchingPresetId(second));
	}
}
