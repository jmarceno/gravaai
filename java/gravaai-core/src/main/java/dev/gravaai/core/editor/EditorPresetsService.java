package dev.gravaai.core.editor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Editor-preset management (port of {@code presets/useEditorPresets.ts} and the pure part of
 * {@code useVideoEditorPresets.ts}).
 *
 * <p>Owns the loaded preset list and the active preset id; saving/deleting persists through
 * {@link EditorPreferencesStore}. Applying a snapshot to the editor is a store/UI operation the
 * caller performs with the returned snapshot.
 */
public final class EditorPresetsService {

	private static final Pattern WHITESPACE_RUN =
			Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
	private static final DateTimeFormatter ISO_MILLIS =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	/** Result of {@link #savePreset}. */
	public enum SaveStatus {
		SAVED, EMPTY_NAME, DUPLICATE_NAME, SAVE_FAILED
	}

	/** Result of {@link #deletePreset}. */
	public enum DeleteStatus {
		DELETED, NOT_FOUND, SAVE_FAILED
	}

	/** Result of {@link #savePreset}: the status plus the persisted preset list and active id. */
	public record SaveResult(SaveStatus status, List<EditorPreset> presets, String activePresetId) {}

	/** Result of {@link #deletePreset}. */
	public record DeleteResult(DeleteStatus status, List<EditorPreset> presets,
			String activePresetId) {}

	private final EditorPreferencesStore store;
	private List<EditorPreset> presets;
	private String activePresetId;

	public EditorPresetsService(EditorPreferencesStore store) {
		this.store = store;
		this.presets = store.loadPresets();
	}

	public List<EditorPreset> presets() {
		return presets;
	}

	public String activePresetId() {
		return activePresetId;
	}

	public void setActivePresetId(String id) {
		this.activePresetId = id;
	}

	/** {@code currentEditorPreset}: the preset with the active id, if any. */
	public EditorPreset currentEditorPreset() {
		return find(activePresetId);
	}

	/** The preset with the given id, or null. */
	public EditorPreset find(String id) {
		if (id == null) {
			return null;
		}
		for (EditorPreset preset : presets) {
			if (preset.id().equals(id)) {
				return preset;
			}
		}
		return null;
	}

	/**
	 * The preset whose serialised snapshot equals the current one ({@code currentSignature}), i.e.
	 * what the hook's effect sets the active id to when the editor exactly matches a preset.
	 */
	public String matchingPresetId(EditorPresetSnapshot currentSnapshot) {
		String signature = EditorPresets.serializeSnapshot(currentSnapshot);
		for (EditorPreset preset : presets) {
			if (EditorPresets.serializeSnapshot(preset.snapshot()).equals(signature)) {
				return preset.id();
			}
		}
		return null;
	}

	/** {@code handleSaveEditorPreset}. */
	public SaveResult savePreset(String name, EditorPresetSnapshot currentSnapshot) {
		String normalizedName = name == null ? ""
				: WHITESPACE_RUN.matcher(name.strip()).replaceAll(" ");
		if (normalizedName.isEmpty()) {
			return new SaveResult(SaveStatus.EMPTY_NAME, presets, activePresetId);
		}
		String normalizedLower = normalizedName.toLowerCase(Locale.ROOT);
		for (EditorPreset preset : presets) {
			if (preset.name().toLowerCase(Locale.ROOT).equals(normalizedLower)) {
				return new SaveResult(SaveStatus.DUPLICATE_NAME, presets, activePresetId);
			}
		}

		String timestamp = ISO_MILLIS.format(Instant.now());
		EditorPreset nextPreset = new EditorPreset(UUID.randomUUID().toString(), normalizedName,
				timestamp, timestamp, currentSnapshot);
		List<EditorPreset> nextPresets = new ArrayList<>(presets.size() + 1);
		nextPresets.add(nextPreset);
		nextPresets.addAll(presets);
		if (!store.savePresets(nextPresets)) {
			return new SaveResult(SaveStatus.SAVE_FAILED, presets, activePresetId);
		}
		presets = EditorPresets.normalizePresets(nextPresets);
		activePresetId = nextPreset.id();
		return new SaveResult(SaveStatus.SAVED, presets, activePresetId);
	}

	/** {@code handleDeleteEditorPreset}. */
	public DeleteResult deletePreset(String presetId) {
		EditorPreset preset = find(presetId);
		if (preset == null) {
			return new DeleteResult(DeleteStatus.NOT_FOUND, presets, activePresetId);
		}
		List<EditorPreset> nextPresets = new ArrayList<>(presets.size());
		for (EditorPreset candidate : presets) {
			if (!candidate.id().equals(presetId)) {
				nextPresets.add(candidate);
			}
		}
		if (!store.savePresets(nextPresets)) {
			return new DeleteResult(DeleteStatus.SAVE_FAILED, presets, activePresetId);
		}
		presets = EditorPresets.normalizePresets(nextPresets);
		if (presetId.equals(activePresetId)) {
			activePresetId = null;
		}
		return new DeleteResult(DeleteStatus.DELETED, presets, activePresetId);
	}
}
