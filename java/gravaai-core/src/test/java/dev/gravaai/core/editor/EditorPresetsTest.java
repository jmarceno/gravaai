package dev.gravaai.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.settings.AppSettingsStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Port of the preset half of {@code editorPreferences.test.ts} (storage adapted to AppSettingsStore). */
class EditorPresetsTest {

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

	private static ObjectNode snapshotJson() {
		ObjectNode snapshot = EditorPreferences.defaults().toJson();
		snapshot.set("cropRegion", json("{\"x\":0.08,\"y\":0.12,\"width\":0.8,\"height\":0.7}"));
		snapshot.set("autoCaptionSettings", json("{}"));
		snapshot.set("keystrokeOverlay", json("{}"));
		return snapshot;
	}

	private static EditorPreset preset(String id, String name, String updatedAt, ObjectNode snapshot) {
		ObjectNode raw = Json.nodeFactory().objectNode();
		raw.put("id", id);
		raw.put("name", name);
		raw.put("createdAt", "2026-05-01T00:00:00.000Z");
		raw.put("updatedAt", updatedAt);
		raw.set("snapshot", snapshot);
		EditorPreset preset = EditorPresets.normalizePreset(raw);
		assertNotNull(preset);
		return preset;
	}

	@Test
	@DisplayName("saves editor presets and reports success")
	void savesPresets() {
		assertTrue(store.savePresets(List.of(
				preset("preset-1", " Demo Preset ", "2026-05-01T00:00:00.000Z", snapshotJson()))));

		assertNotNull(settings.readSetting(EditorPreferences.EDITOR_PRESETS_STORAGE_KEY));
		List<EditorPreset> loaded = store.loadPresets();
		assertEquals(1, loaded.size());
		assertEquals("preset-1", loaded.get(0).id());
		assertEquals("Demo Preset", loaded.get(0).name());
	}

	@Test
	@DisplayName("preserves crop region in editor preset snapshots")
	void preservesCropRegion() {
		store.savePresets(List.of(
				preset("preset-1", "Cropped Demo", "2026-05-01T00:00:00.000Z", snapshotJson())));

		assertEquals(new dev.gravaai.core.project.CropRegion(0.08, 0.12, 0.8, 0.7),
				store.loadPresets().get(0).snapshot().cropRegion());
	}

	@Test
	@DisplayName("does not copy imported source ranges into a reusable style preset")
	void doesNotCopyImportedSourceRanges() {
		ObjectNode snapshot = snapshotJson();
		ObjectNode webcam = (ObjectNode) snapshot.get("webcam");
		webcam.put("sourcePath", "/imported-webcam.mp4");
		var ranges = Json.nodeFactory().arrayNode();
		ranges.add(json("{\"startMs\":1000,\"endMs\":2000}"));
		webcam.set("visibleRanges", ranges);

		store.savePresets(List.of(
				preset("webcam", "Webcam", "2026-09-20T00:00:00.000Z", snapshot)));

		JsonNode storedWebcam = EditorPresets.snapshotToJson(
				store.loadPresets().get(0).snapshot()).get("webcam");
		assertFalse(storedWebcam.has("visibleRanges"));
		assertFalse(storedWebcam.has("sourcePath"));
	}

	@Test
	@DisplayName("drops export settings and machine paths from legacy presets")
	void dropsLegacyExportAndMachinePaths() {
		ObjectNode snapshot = snapshotJson();
		snapshot.put("exportQuality", "high");
		snapshot.put("mp4FrameRate", 60);
		snapshot.put("publishDestination", "local");
		snapshot.put("whisperModelPath", "/machine/model.bin");
		snapshot.put("whisperExecutablePath", "/machine/whisper-cli");

		store.savePresets(List.of(
				preset("legacy", "Legacy", "2026-01-01T00:00:00.000Z", snapshot)));

		JsonNode stored = EditorPresets.snapshotToJson(store.loadPresets().get(0).snapshot());
		assertEquals(EditorPreferences.defaults().wallpaper(), stored.get("wallpaper").asText());
		for (String key : List.of("exportQuality", "mp4FrameRate", "publishDestination",
				"whisperExecutablePath", "whisperModelPath")) {
			assertFalse(stored.has(key), key);
		}
	}

	@Test
	@DisplayName("sorts presets newest first")
	void sortsPresetsNewestFirst() {
		store.savePresets(List.of(
				preset("old", "Old", "2026-01-01T00:00:00.000Z", snapshotJson()),
				preset("new", "New", "2026-06-01T00:00:00.000Z", snapshotJson())));

		assertEquals(List.of("new", "old"),
				store.loadPresets().stream().map(EditorPreset::id).toList());
	}

	@Test
	@DisplayName("returns false when preset persistence fails")
	void reportsFailure() throws Exception {
		Files.createDirectory(directory.resolve("app-settings.json"));

		assertFalse(store.savePresets(List.of(
				preset("preset-1", "Demo", "2026-05-01T00:00:00.000Z", snapshotJson()))));
	}

	@Test
	@DisplayName("serializes a normalised snapshot as compact JSON")
	void serializesSnapshotCompactly() {
		EditorPresetSnapshot snapshot = store.loadPresets() == null ? null
				: EditorPresets.normalizeSnapshot(snapshotJson());

		String serialized = EditorPresets.serializeSnapshot(snapshot);
		assertFalse(serialized.contains("\n"));
		JsonNode parsed = json(serialized);
		// Re-normalising the serialised snapshot produces the same compact JSON again.
		assertEquals(serialized, EditorPresets.serializeSnapshot(EditorPresets.normalizeSnapshot(parsed)));
	}
}
