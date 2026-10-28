package dev.gravaai.services.sources;

/** A window the capture picker can highlight, from {@code CapturePickerWindow} in electron/ipc/types.ts. */
public record CapturePickerWindow(
		String id,
		String appName,
		String title,
		String displayId,
		double x,
		double y,
		double width,
		double height) {
}
