package dev.gravaai.services.sources;

import org.jspecify.annotations.Nullable;

/**
 * Area-source helpers, ported from {@code electron/ipc/sourceArea.ts}.
 *
 * <p>Only the parts the app uses are kept: a recorded area is stored as an {@code area:} source and
 * the recording pipeline reads its rectangle back. The picker's validation and source-building
 * helpers went with the overlay picker they served.
 */
public final class SourceArea {

	private SourceArea() {
	}

	/** A display the picker can draw on. */
	public record PickerDisplay(int id, CaptureArea bounds) {
	}

	/** The rectangle of an area source, or null when the source is not a usable area. */
	public static @Nullable CaptureArea getSourceArea(SelectedSource source) {
		if (source == null || source.id() == null || !source.id().startsWith("area:")) {
			return null;
		}
		CaptureArea area = source.area();
		return area != null && area.isUsable() ? area : null;
	}
}
