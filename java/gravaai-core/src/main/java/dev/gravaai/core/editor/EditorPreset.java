package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

/** A named, timestamped editor preset (port of {@code EditorPreset}). */
public record EditorPreset(
		String id, String name, @Nullable String createdAt, @Nullable String updatedAt, @Nullable EditorPresetSnapshot snapshot) {}
