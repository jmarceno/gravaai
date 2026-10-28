package dev.gravaai.services.sources;

/**
 * A capture source the user selected, from {@code SelectedSource} in
 * {@code electron/ipc/types.ts}.
 *
 * <p>Optional TypeScript fields are nullable record components (plan §8); callers compare them
 * with null checks, never with {@link java.util.Optional}.
 */
public record SelectedSource(
		String id,
		String name,
		String displayId,
		SourceType sourceType,
		String appName,
		String windowTitle,
		CaptureArea area,
		String originalName) {

	/** A screen the source list offers. */
	public static SelectedSource screen(String id, String name, String displayId) {
		return new SelectedSource(id, name, displayId, SourceType.SCREEN, null, null, null, null);
	}

	/** A window the source list offers. */
	public static SelectedSource window(String id, String name, String displayId, String appName, String windowTitle) {
		return new SelectedSource(id, name, displayId, SourceType.WINDOW, appName, windowTitle, null, null);
	}

	/** An area rectangle on a display. */
	public static SelectedSource area(String displayId, CaptureArea area, String name) {
		return new SelectedSource("area:" + displayId, name, displayId, SourceType.AREA, null, null, area, null);
	}
}
