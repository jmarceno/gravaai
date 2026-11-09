package dev.gravaai.services.recording.cursor;

import org.jspecify.annotations.Nullable;

import dev.gravaai.services.recording.CursorCaptureClock;
import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.CursorTelemetry.CursorVisualType;

/**
 * Pointer and mouse-button interaction state, port of {@code electron/ipc/cursor/interaction.ts}.
 *
 * <p>A left press within the time and distance thresholds of the previous one is a double click,
 * and the thresholds are the TS values so the sidecars stay identical.
 */
final class CursorInteractionTracker {

	/** A second left click within these bounds is a double-click. */
	private static final long DOUBLE_CLICK_THRESHOLD_MS = 350;
	private static final double DOUBLE_CLICK_MAX_DISTANCE = 0.04;

	private final CursorSampleBuffer samples;
	private final CursorCaptureClock clock;
	private final CursorCoordinateMapper coordinates;

	private @Nullable LastLeftClick lastLeftClick;

	private record LastLeftClick(long timeMs, double cx, double cy) {
	}

	CursorInteractionTracker(
			CursorSampleBuffer samples,
			CursorCaptureClock clock,
			CursorCoordinateMapper coordinates) {
		this.samples = samples;
		this.clock = clock;
		this.coordinates = coordinates;
	}

	/** Port of {@code normalizeHookMouseButton}. */
	static int normalizeHookMouseButton(@Nullable Object rawButton) {
		if (!(rawButton instanceof Number number) || !Double.isFinite(number.doubleValue())) {
			return 1;
		}
		double value = number.doubleValue();
		if (value == 2 || value == 39) {
			return 2;
		}
		if (value == 3 || value == 38) {
			return 3;
		}
		return 1;
	}

	void reset() {
		lastLeftClick = null;
	}

	/**
	 * Records one press or release at the current pointer position.
	 *
	 * @param mouseDown true for a press, false for a release
	 * @param button the helper's raw button, or null when it did not report one
	 * @param cursorType the current cursor visual, or null when unknown
	 */
	void record(boolean mouseDown, @Nullable Integer button, @Nullable CursorVisualType cursorType) {
		double[] point;
		try {
			point = coordinates.normalize();
		} catch (RuntimeException error) {
			return;
		}
		long timeMs = clock.getCursorCaptureElapsedMs();
		CursorInteractionType interactionType;
		if (!mouseDown) {
			interactionType = CursorInteractionType.MOUSEUP;
		} else {
			interactionType = pressType(normalizeHookMouseButton(button), point, timeMs);
		}
		samples.push(point[0], point[1], timeMs, interactionType, cursorType);
	}

	/** Classifies a press, recording the left-click pair that a double click is detected from. */
	private CursorInteractionType pressType(int button, double[] point, long timeMs) {
		if (button == 2) {
			return CursorInteractionType.RIGHT_CLICK;
		}
		if (button == 3) {
			return CursorInteractionType.MIDDLE_CLICK;
		}
		double distance = lastLeftClick == null
				? Double.POSITIVE_INFINITY
				: Math.hypot(point[0] - lastLeftClick.cx(), point[1] - lastLeftClick.cy());
		CursorInteractionType interactionType = CursorInteractionType.CLICK;
		if (lastLeftClick != null
				&& timeMs - lastLeftClick.timeMs() <= DOUBLE_CLICK_THRESHOLD_MS
				&& distance <= DOUBLE_CLICK_MAX_DISTANCE) {
			interactionType = CursorInteractionType.DOUBLE_CLICK;
		}
		lastLeftClick = new LastLeftClick(timeMs, point[0], point[1]);
		return interactionType;
	}
}
