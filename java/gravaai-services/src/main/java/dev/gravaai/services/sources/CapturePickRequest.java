package dev.gravaai.services.sources;

/**
 * Raw input a capture-picker window reports, before validation. Mirrors the untyped
 * {@code unknown} input of {@code normalizeCapturePick} in {@code electron/ipc/sourceArea.ts}; nullable
 * components stand in for the TypeScript values that may be missing or of the wrong type.
 */
public record CapturePickRequest(
		String kind,
		Double x,
		Double y,
		Double width,
		Double height,
		String windowId,
		Integer displayId,
		boolean record) {
}
