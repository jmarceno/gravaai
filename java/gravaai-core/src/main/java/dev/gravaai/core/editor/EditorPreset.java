package dev.gravaai.core.editor;

/** A named, timestamped editor preset (port of {@code EditorPreset}). */
public record EditorPreset(
		String id, String name, String createdAt, String updatedAt, EditorPresetSnapshot snapshot) {}
