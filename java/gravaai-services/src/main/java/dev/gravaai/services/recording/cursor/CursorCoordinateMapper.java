package dev.gravaai.services.recording.cursor;

import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.gravaai.services.recording.CursorScreen;
import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.WindowSources;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.sources.SourceArea;

/**
 * Normalizes the pointer position reported by the native helper into the selected source's
 * 0..1 space, port of the coordinate half of {@code electron/ipc/cursor/telemetry.ts}.
 *
 * <p>The helper reports logical pixels; the cache keeps physical pixels scaled by the primary
 * display's factor. A window source normalizes against the live bounds, an area against its fixed
 * rectangle, and a screen against the display it came from. The portal hides which screen was
 * picked, so the display the pointer was on at capture start is remembered and reused.
 *
 * <p>When the capture backend can measure the pointer inside the surface it is recording — the
 * compositors do this for a window through a cursor session, and for a portal stream through
 * cursor metadata — that reading wins. It is already in the recorded frame's coordinates, which
 * makes it both exact and the only correct answer for a window: a Wayland window's global position
 * is not visible to the app, so the helper's global reading cannot be normalized against it.
 * See {@link #recordCapturePoint}.
 */
final class CursorCoordinateMapper {

	private final CursorScreen screen;
	private final Supplier<SelectedSource> selectedSource;
	private final BooleanSupplier helperActive;

	private volatile @Nullable Long waylandCaptureDisplayId;
	private long linuxCursorX;
	private long linuxCursorY;
	private long linuxCursorUpdatedAt;
	private boolean hasLinuxCursorPoint;

	/** The newest pointer reading taken from the capture stream itself, if any. */
	private volatile double captureCursorX;
	private volatile double captureCursorY;
	private volatile boolean hasCaptureCursorPoint;

	CursorCoordinateMapper(
			CursorScreen screen,
			Supplier<SelectedSource> selectedSource,
			BooleanSupplier helperActive) {
		this.screen = screen;
		this.selectedSource = selectedSource;
		this.helperActive = helperActive;
	}

	/** Forgets the previous recording's pointer and display, keeping the last known point. */
	void reset() {
		waylandCaptureDisplayId = null;
		hasLinuxCursorPoint = false;
		resetCapturePoint();
	}

	/** Forgets the capture-stream reading, so a new recording cannot inherit the old one. */
	void resetCapturePoint() {
		hasCaptureCursorPoint = false;
	}

	/** True once the capture stream has reported a pointer position. */
	boolean hasCapturePoint() {
		return hasCaptureCursorPoint;
	}

	/**
	 * Records one pointer position measured inside the surface being captured.
	 *
	 * <p>This is the reading the compositor produced alongside the frames, so it already is in the
	 * recorded frame's coordinates and needs no display arithmetic — which is the only way a
	 * window recording can map the pointer at all, since a Wayland window's global position is not
	 * exposed to the app.
	 *
	 * @param cx normalized x inside the recorded frame (0..1)
	 * @param cy normalized y inside the recorded frame (0..1)
	 */
	void recordCapturePoint(double cx, double cy) {
		if (!Double.isFinite(cx) || !Double.isFinite(cy)) {
			return;
		}
		captureCursorX = CursorTelemetry.clamp(cx, 0, 1);
		captureCursorY = CursorTelemetry.clamp(cy, 0, 1);
		hasCaptureCursorPoint = true;
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
		// The capture stream's own reading is already in the recorded frame, which is what the
		// sidecar must describe: prefer it whenever there is one. It is not aged out, because the
		// compositors report a position when it changes — a still pointer keeps its last value,
		// and falling back would reintroduce a window-offset error.
		if (hasCaptureCursorPoint) {
			return new double[] {captureCursorX, captureCursorY};
		}
		SelectedSource source = selectedSource.get();
		boolean windowSource = WindowSources.isWindowSourceId(source == null ? null : source.id());
		if (windowSource) {
			// A Wayland window's global position is not exposed to the app, so a global pointer
			// reading cannot be normalized against it. Without a capture-stream reading there is
			// nothing honest to report; the centre keeps the sample in frame instead of drawing
			// the cursor at a spot that belongs to another surface's layout.
			return new double[] {0.5, 0.5};
		}
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

		// Areas are fixed rectangles, so they normalize like a window that never moves.
		var area = SourceArea.getSourceArea(source);
		double boundsX;
		double boundsY;
		double boundsWidth;
		double boundsHeight;
		double scale;
		if (area != null) {
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
