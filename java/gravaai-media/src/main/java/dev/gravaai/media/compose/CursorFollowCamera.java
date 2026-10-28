package dev.gravaai.media.compose;

import java.util.List;

/**
 * Port of {@code src/components/video-editor/videoPlayback/cursorFollowCamera.ts} (plan §5.8
 * stage 3): the cursor-follow camera keeps a persistent centre while zoomed and only recentres
 * after the cursor leaves an inner safe zone, shifting just enough to bring it back inside.
 */
public final class CursorFollowCamera {

	/** Default snap ratio for manual zoom regions. */
	public static final double SNAP_TO_EDGES_RATIO_MANUAL = 0.25;

	/** Snap ratio for system/auto zoom regions. */
	public static final double SNAP_TO_EDGES_RATIO_AUTO = 0.25;

	/** Cursor-follow configuration (TS {@code CursorFollowConfig}). */
	public record CursorFollowConfig(double snapToEdgesRatio) {

		/** {@code DEFAULT_CURSOR_FOLLOW_CONFIG}. */
		public static final CursorFollowConfig DEFAULT =
				new CursorFollowConfig(SNAP_TO_EDGES_RATIO_AUTO);
	}

	/** Mutable cursor-follow state (TS {@code CursorFollowCameraState}). */
	public static final class CursorFollowCameraState {
		public boolean initialized;
		public double lastTimeMs;
		public double focusX = 0.5;
		public double focusY = 0.5;
		public boolean wasZoomed;
		public boolean reachedFullZoom;
		public double frozenFocusX = 0.5;
		public double frozenFocusY = 0.5;
	}

	/** {@code createCursorFollowCameraState}. */
	public static CursorFollowCameraState createCursorFollowCameraState() {
		CursorFollowCameraState state = new CursorFollowCameraState();
		state.initialized = false;
		state.lastTimeMs = 0;
		state.focusX = 0.5;
		state.focusY = 0.5;
		state.wasZoomed = false;
		state.reachedFullZoom = false;
		state.frozenFocusX = 0.5;
		state.frozenFocusY = 0.5;
		return state;
	}

	/** {@code resetCursorFollowCamera}. */
	public static void resetCursorFollowCamera(CursorFollowCameraState state) {
		state.initialized = false;
		state.lastTimeMs = 0;
		state.focusX = 0.5;
		state.focusY = 0.5;
		state.wasZoomed = false;
		state.reachedFullZoom = false;
		state.frozenFocusX = 0.5;
		state.frozenFocusY = 0.5;
	}

	private static double clampSafeZoneRatio(double ratio) {
		if (!Double.isFinite(ratio)) {
			return SNAP_TO_EDGES_RATIO_AUTO;
		}
		return Math.max(0, Math.min(0.49, ratio));
	}

	private static double getVisibleHalfSpan(double zoomScale) {
		return 1 / (2 * Math.max(1, zoomScale));
	}

	private static ZoomFocus recenterFocusWhenCursorLeavesSafeZone(ZoomFocus currentFocus,
			ZoomFocus cursorFocus, double zoomScale, double safeZoneRatio) {
		double halfSpan = getVisibleHalfSpan(zoomScale);
		double visibleSpan = halfSpan * 2;
		double safeZoneInset = visibleSpan * clampSafeZoneRatio(safeZoneRatio);

		double safeLeft = currentFocus.cx() - halfSpan + safeZoneInset;
		double safeRight = currentFocus.cx() + halfSpan - safeZoneInset;
		double safeTop = currentFocus.cy() - halfSpan + safeZoneInset;
		double safeBottom = currentFocus.cy() + halfSpan - safeZoneInset;

		double nextFocusX = currentFocus.cx();
		double nextFocusY = currentFocus.cy();
		if (cursorFocus.cx() < safeLeft) {
			nextFocusX = cursorFocus.cx();
		} else if (cursorFocus.cx() > safeRight) {
			nextFocusX = cursorFocus.cx();
		}
		if (cursorFocus.cy() < safeTop) {
			nextFocusY = cursorFocus.cy();
		} else if (cursorFocus.cy() > safeBottom) {
			nextFocusY = cursorFocus.cy();
		}
		return FocusUtils.clampFocusToScale(new ZoomFocus(nextFocusX, nextFocusY), zoomScale);
	}

	/** {@code computeCursorFollowFocus} with the default config. */
	public static ZoomFocus computeCursorFollowFocus(CursorFollowCameraState state,
			List<CursorSample> cursorSamples, double timeMs, double zoomScale,
			double zoomStrength, ZoomFocus regionFocus) {
		return computeCursorFollowFocus(state, cursorSamples, timeMs, zoomScale, zoomStrength,
				regionFocus, CursorFollowConfig.DEFAULT);
	}

	/**
	 * {@code computeCursorFollowFocus}: the target camera focus for this frame.
	 *
	 * @param state the persistent cursor-follow state (mutated)
	 * @param cursorSamples the cursor telemetry samples
	 * @param timeMs the cursor time in media milliseconds
	 * @param zoomScale the active zoom scale
	 * @param zoomStrength the region strength (0-1)
	 * @param regionFocus the region's focus point
	 * @param config the safe-zone config
	 * @return the target focus point for this frame (normalised 0-1)
	 */
	public static ZoomFocus computeCursorFollowFocus(CursorFollowCameraState state,
			List<CursorSample> cursorSamples, double timeMs, double zoomScale,
			double zoomStrength, ZoomFocus regionFocus, CursorFollowConfig config) {
		CursorFollowConfig safeConfig = config == null ? CursorFollowConfig.DEFAULT : config;
		ZoomFocus clampedRegionFocus = FocusUtils.clampFocusToScale(regionFocus, zoomScale);

		if (zoomStrength < 0.01) {
			if (state.wasZoomed) {
				state.wasZoomed = false;
				state.initialized = false;
				state.reachedFullZoom = false;
			}
			return clampedRegionFocus;
		}

		CursorSample cursorPos = CursorPosition.interpolateCursorPosition(cursorSamples, timeMs);
		if (cursorPos == null) {
			if (state.initialized) {
				return new ZoomFocus(state.focusX, state.focusY);
			}
			return clampedRegionFocus;
		}

		if (zoomStrength >= 0.99) {
			state.reachedFullZoom = true;
		}
		if (state.reachedFullZoom && zoomStrength < 0.99) {
			return new ZoomFocus(state.frozenFocusX, state.frozenFocusY);
		}

		boolean timeWentBackwards = state.initialized && timeMs + 0.5 < state.lastTimeMs;
		if (!state.initialized || !state.wasZoomed || timeWentBackwards) {
			ZoomFocus initialFocus = clampedRegionFocus;
			state.lastTimeMs = timeMs;
			state.initialized = true;
			state.wasZoomed = true;
			state.focusX = initialFocus.cx();
			state.focusY = initialFocus.cy();
			state.frozenFocusX = initialFocus.cx();
			state.frozenFocusY = initialFocus.cy();
			return initialFocus;
		}

		state.lastTimeMs = timeMs;
		ZoomFocus targetFocus = recenterFocusWhenCursorLeavesSafeZone(
				new ZoomFocus(state.focusX, state.focusY),
				new ZoomFocus(cursorPos.x(), cursorPos.y()),
				zoomScale, safeConfig.snapToEdgesRatio());
		state.focusX = targetFocus.cx();
		state.focusY = targetFocus.cy();
		state.frozenFocusX = targetFocus.cx();
		state.frozenFocusY = targetFocus.cy();
		return targetFocus;
	}
}
