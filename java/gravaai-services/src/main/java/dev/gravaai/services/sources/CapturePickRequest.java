package dev.gravaai.services.sources;

import org.jspecify.annotations.Nullable;

/**
 * Raw input a capture-picker window reports, before validation. Mirrors the untyped
 * {@code unknown} input of {@code normalizeCapturePick} in {@code electron/ipc/sourceArea.ts}; nullable
 * components stand in for the TypeScript values that may be missing or of the wrong type.
 */
public record CapturePickRequest(
		String kind,
		@Nullable Double x,
		@Nullable Double y,
		@Nullable Double width,
		@Nullable Double height,
		@Nullable String windowId,
		@Nullable Integer displayId,
		boolean record) {
}
