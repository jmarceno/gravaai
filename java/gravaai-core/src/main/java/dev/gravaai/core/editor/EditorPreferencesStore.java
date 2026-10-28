package dev.gravaai.core.editor;

import java.io.IOException;
import java.util.List;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.settings.AppSettingsStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Persists editor preferences and presets in {@code app-settings.json} (port of the storage half of
 * {@code editorPreferences.ts}, plan §5.11).
 *
 * <p>The Electron build writes the same values to {@code localStorage} as a mirror; the Java app has
 * one process, so the app-settings store is the single persistence target (recorded narrowing).
 */
public final class EditorPreferencesStore {

	private static final System.Logger LOG =
			System.getLogger("gravaai.editor.preferences");

	private final AppSettingsStore store;

	public EditorPreferencesStore(AppSettingsStore store) {
		this.store = store;
	}

	/** {@code loadEditorPreferences}. */
	public EditorPreferences loadPreferences() {
		JsonNode persisted = store.readSetting(EditorPreferences.EDITOR_PREFERENCES_STORAGE_KEY);
		return persisted != null
				? EditorPreferences.normalize(persisted, EditorPreferences.defaults())
				: EditorPreferences.defaults();
	}

	/**
	 * {@code saveEditorPreferences}: merge the patch over the current preferences, normalise and
	 * persist without the dev-only motion-blur tuning. Storage failures are swallowed so the editor
	 * controls keep working.
	 */
	public void savePreferences(JsonNode patch) {
		try {
			EditorPreferences current = loadPreferences();
			ObjectNode merged = current.toJson();
			if (patch != null && patch.isObject()) {
				for (var entry : patch.properties()) {
					merged.set(entry.getKey(), entry.getValue());
				}
			}
			EditorPreferences normalized = EditorPreferences.normalize(merged, current);
			ObjectNode persisted = normalized.toJson();
			persisted.remove("zoomMotionBlurTuning");
			store.writeSetting(EditorPreferences.EDITOR_PREFERENCES_STORAGE_KEY, persisted);
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING,
					"Could not save editor preferences: " + error);
		}
	}

	/** {@code loadEditorPresets}. */
	public List<EditorPreset> loadPresets() {
		JsonNode persisted = store.readSetting(EditorPreferences.EDITOR_PRESETS_STORAGE_KEY);
		return EditorPresets.normalizePresets(persisted);
	}

	/** {@code saveEditorPresets}: normalise, then persist; false when persistence fails. */
	public boolean savePresets(List<EditorPreset> presets) {
		try {
			List<EditorPreset> normalized = EditorPresets.normalizePresets(presets);
			store.writeSetting(EditorPreferences.EDITOR_PRESETS_STORAGE_KEY,
					EditorPresets.presetsToJson(normalized));
			return true;
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not save editor presets: " + error);
			return false;
		}
	}
}
