package dev.gravaai.services.sources;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Validates what a capture-picker window reports and builds the selected sources, ported from
 * {@code electron/ipc/sourceArea.ts}.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class SourceArea {

	/** Smallest area worth recording, in points. */
	public static final int MIN_CAPTURE_AREA_SIZE = 32;

	@Nullable private SourceArea() {
	}

	/** A display the picker can draw on. */
	public @Nullable record PickerDisplay(int id, CaptureArea bounds) {
	}

	/**
	 * Validates what a capture-picker window reports. An area must have a usable size and lie on
	 * the display it was drawn on; a window must be one the picker offered; a screen must be a
	 * connected display.
	 */
	public static @Nullable CapturePick normalizeCapturePick(
			CapturePickRequest input,
			List<PickerDisplay> displays,
			List<CapturePickerWindow> windows) {
		if (input == null || input.kind() == null || input.displayId() == null) {
			return null;
		}
		boolean record = input.record();
		PickerDisplay display = null;
		for (PickerDisplay entry : displays) {
			if (entry.id() == input.displayId()) {
				display = entry;
				break;
			}
		}
		if (display == null) {
			return null;
		}

		if ("screen".equals(input.kind())) {
			return new CapturePick.Screen(display.id(), record);
		}

		if ("window".equals(input.kind())) {
			for (CapturePickerWindow window : windows) {
				if (window.id().equals(input.windowId())) {
					return new CapturePick.Window(window.id(), display.id(), record);
				}
			}
			return null;
		}

		if (!"area".equals(input.kind())) {
			return null;
		}
		Double x = input.x();
		Double y = input.y();
		Double width = input.width();
		Double height = input.height();
		if (!isFiniteNumber(x) || !isFiniteNumber(y) || !isFiniteNumber(width) || !isFiniteNumber(height)) {
			return null;
		}
		CaptureArea bounds = display.bounds();
		double left = Math.max(bounds.x(), Math.round(x));
		double top = Math.max(bounds.y(), Math.round(y));
		double right = Math.min(bounds.x() + bounds.width(), Math.round(x + width));
		double bottom = Math.min(bounds.y() + bounds.height(), Math.round(y + height));
		// Whole, even sizes map the area onto screen pixels one to one at any display
		// scale, so the recording is never resampled.
		double areaWidth = right - left - ((right - left) % 2);
		double areaHeight = bottom - top - ((bottom - top) % 2);
		if (areaWidth < MIN_CAPTURE_AREA_SIZE || areaHeight < MIN_CAPTURE_AREA_SIZE) {
			return null;
		}

		return new CapturePick.Area(left, top, areaWidth, areaHeight, display.id(), record);
	}

	private static boolean isFiniteNumber(@Nullable Double value) {
		return value != null && Double.isFinite(value);
	}

	/** The selected source for a confirmed area pick. */
	public static SelectedSource createAreaSource(CapturePick.Area pick) {
		return SelectedSource.area(
				String.valueOf(pick.displayId()),
				new CaptureArea(pick.x(), pick.y(), pick.width(), pick.height()),
				"Area " + CaptureArea.number(pick.width()) + "×" + CaptureArea.number(pick.height()));
	}

	/** The same source the window list offers for this window. */
	public static @Nullable SelectedSource createWindowSource(CapturePickerWindow window) {
		String title = window.title() == null || window.title().isEmpty()
				? (window.appName() == null || window.appName().isEmpty() ? "Window" : window.appName())
				: window.title();
		String displayId = window.displayId() == null ? "" : window.displayId();
		String appName = window.appName() == null || window.appName().isEmpty() ? null : window.appName();
		return SelectedSource.window(window.id(), title, displayId, appName, title);
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
