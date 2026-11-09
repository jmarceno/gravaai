package dev.gravaai.services.recording.cursor;

import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.gravaai.services.recording.CursorScreen;
import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.sources.SourceArea;
import dev.gravaai.services.sources.WindowBounds;

/**
 * Normalizes the pointer position reported by the native helper into the selected source's
 * 0..1 space, port of the coordinate half of {@code electron/ipc/cursor/telemetry.ts}.
 *
 * <p>The helper reports logical pixels; the cache keeps physical pixels scaled by the primary
 * display's factor. A window source normalizes against the live bounds, an area against its fixed
 * rectangle, and a screen against the display it came from. The portal hides which screen was
 * picked, so the display the pointer was on at capture start is remembered and reused.
 */
final class CursorCoordinateMapper {

	private final CursorScreen screen;
	private final Supplier<SelectedSource> selectedSource;
	private final Supplier<WindowBounds> selectedWindowBounds;
	private final BooleanSupplier helperActive;

	private volatile @Nullable Long waylandCaptureDisplayId;
	private long linuxCursorX;
	private long linuxCursorY;
	private long linuxCursorUpdatedAt;
	private boolean hasLinuxCursorPoint;

	CursorCoordinateMapper(
			CursorScreen screen,
			Supplier<SelectedSource> selectedSource,
			Supplier<WindowBounds> selectedWindowBounds,
			BooleanSupplier helperActive) {
		this.screen = screen;
		this.selectedSource = selectedSource;
		this.selectedWindowBounds = selectedWindowBounds;
		this.helperActive = helperActive;
	}

	/** Forgets the previous recording's pointer and display, keeping the last known point. */
	void reset() {
		waylandCaptureDisplayId = null;
		hasLinuxCursorPoint = false;
	}


	/**
	 * Stores one helper {@code POS} reading.
	 *
	 * @param logicalX the helper's logical x
	 * @param logicalY the helper's logical y
	 * @return true when this is the first position of the recording
	 */
	boolean recordPosition(long logicalX, long logicalY) {
		double scale = primaryScale();
		boolean first = !hasLinuxCursorPoint;
		hasLinuxCursorPoint = true;
		linuxCursorX = Math.round(logicalX * scale);
		linuxCursorY = Math.round(logicalY * scale);
		linuxCursorUpdatedAt = System.currentTimeMillis();
		return first;
	}

	private boolean cacheFresh() {
		return hasLinuxCursorPoint
				&& (helperActive.getAsBoolean() || System.currentTimeMillis() - linuxCursorUpdatedAt <= 1000);
	}

	private double primaryScale() {
		CursorScreen.Display primary = screen.primaryDisplay();
		return primary != null && primary.scaleFactor() > 0 ? primary.scaleFactor() : 1;
	}

	/** The latest normalized cursor position, for the HUD/UI. */
	double[] normalize() {
		CursorScreen.Point fallback;
		try {
			fallback = screen.cursorScreenPoint();
		} catch (RuntimeException error) {
			fallback = new CursorScreen.Point(0, 0);
		}
		boolean cacheFresh = cacheFresh();
		double primaryScale = primaryScale();

		double cursorX = cacheFresh ? linuxCursorX / primaryScale : fallback.x();
		double cursorY = cacheFresh ? linuxCursorY / primaryScale : fallback.y();

		SelectedSource source = selectedSource.get();
		// Areas are fixed rectangles, so they normalize like a window that never moves.
		WindowBounds windowBounds = source != null && source.id() != null && source.id().startsWith("window:")
				? selectedWindowBounds.get()
				: null;
		var area = SourceArea.getSourceArea(source);
		double boundsX;
		double boundsY;
		double boundsWidth;
		double boundsHeight;
		double scale;
		if (windowBounds != null) {
			boundsX = windowBounds.x();
			boundsY = windowBounds.y();
			boundsWidth = windowBounds.width();
			boundsHeight = windowBounds.height();
			CursorScreen.Display nearest = screen.displayNearestPoint(
					boundsX / primaryScale, boundsY / primaryScale);
			scale = nearest != null && nearest.scaleFactor() > 0 ? nearest.scaleFactor() : 1;
		} else if (area != null) {
			boundsX = area.x();
			boundsY = area.y();
			boundsWidth = area.width();
			boundsHeight = area.height();
			scale = primaryScale;
		} else {
			CursorScreen.Display display = captureDisplay(source, cacheFresh, cursorX, cursorY);
			if (display == null) {
				return new double[] {0.5, 0.5};
			}
			boundsX = display.x();
			boundsY = display.y();
			boundsWidth = display.width();
			boundsHeight = display.height();
			scale = display.scaleFactor() > 0 ? display.scaleFactor() : 1;
		}
		double width = Math.max(1, boundsWidth / scale);
		double height = Math.max(1, boundsHeight / scale);
		double cx = CursorTelemetry.clamp((cursorX - boundsX / scale) / width, 0, 1);
		double cy = CursorTelemetry.clamp((cursorY - boundsY / scale) / height, 0, 1);
		return new double[] {cx, cy};
	}

	/**
	 * The display a screen source normalizes against, or null when no display can be resolved.
	 *
	 * @param source the selected source, or null when nothing is selected
	 */
	private CursorScreen.@Nullable Display captureDisplay(
			@Nullable SelectedSource source, boolean cacheFresh, double cursorX, double cursorY) {
		CursorScreen.Display display = null;
		String displayId = source == null ? null : source.displayId();
		if (displayId != null && !displayId.isBlank()) {
			try {
				int id = Integer.parseInt(displayId);
				for (CursorScreen.Display candidate : screen.allDisplays()) {
					if (candidate.id() == id) {
						display = candidate;
						break;
					}
				}
			} catch (NumberFormatException error) {
				display = null;
			}
		}
		if (display == null && helperActive.getAsBoolean() && cacheFresh) {
			// The portal hides which screen was picked; assume the one the cursor was on when
			// recording started and keep normalizing against it.
			if (waylandCaptureDisplayId != null) {
				for (CursorScreen.Display candidate : screen.allDisplays()) {
					if (candidate.id() == waylandCaptureDisplayId) {
						display = candidate;
						break;
					}
				}
			}
			if (display == null) {
				display = screen.displayNearestPoint(cursorX, cursorY);
				if (display != null) {
					waylandCaptureDisplayId = (long) display.id();
				}
			}
		}
		if (display == null) {
			display = screen.displayNearestPoint(cursorX, cursorY);
		}
		return display;
	}
}
