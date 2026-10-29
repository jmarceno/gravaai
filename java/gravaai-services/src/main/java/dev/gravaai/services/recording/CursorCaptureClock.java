package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.List;

import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;

/**
 * The cursor-capture pause clock from {@code electron/ipc/cursor/telemetry.ts}, kept as an instance
 * instead of module state.
 */
public final class CursorCaptureClock {

	private long captureStartTimeMs;
	private long accumulatedPausedMs;
	private @Nullable Long pauseStartedAtMs;

	/** The wall-clock time the recording started; 0 means "not started". */
	public void setStartTimeMs(long startTimeMs) {
		this.captureStartTimeMs = startTimeMs;
	}

	public long startTimeMs() {
		return captureStartTimeMs;
	}

	public void reset() {
		accumulatedPausedMs = 0;
		pauseStartedAtMs = null;
	}

	public boolean isCursorCapturePaused() {
		return pauseStartedAtMs != null;
	}

	public void pauseCursorCapture(long pausedAtMs) {
		if (pauseStartedAtMs != null) {
			return;
		}
		pauseStartedAtMs = pausedAtMs;
	}

	/**
	 * Pauses at the boundary: samples taken after the renderer's pause moment are dropped, so the
	 * persisted telemetry never jumps forward during a pause.
	 */
	public List<CursorTelemetryPoint> pauseCursorCaptureAtBoundary(
			long pausedAtMs, List<CursorTelemetryPoint> samples) {
		if (pauseStartedAtMs != null) {
			return samples;
		}
		long pausedElapsedMs = getCursorCaptureElapsedMs(pausedAtMs);
		List<CursorTelemetryPoint> kept = samples.stream()
				.filter(sample -> sample.timeMs() <= pausedElapsedMs)
				.toList();
		pauseStartedAtMs = pausedAtMs;
		return kept;
	}

	public void resumeCursorCapture(long resumedAtMs) {
		if (pauseStartedAtMs == null) {
			return;
		}
		long pauseDurationMs = Math.max(0, resumedAtMs - pauseStartedAtMs);
		accumulatedPausedMs += pauseDurationMs;
		pauseStartedAtMs = null;
	}

	public long getCursorCaptureElapsedMs() {
		return getCursorCaptureElapsedMs(System.currentTimeMillis());
	}

	public long getCursorCaptureElapsedMs(long nowMs) {
		if (!(captureStartTimeMs > 0)) {
			return 0;
		}
		long safeNowMs = Math.max(captureStartTimeMs, nowMs);
		long activePauseDurationMs = pauseStartedAtMs == null
				? 0
				: Math.max(0, safeNowMs - pauseStartedAtMs);
		return Math.max(
				0,
				safeNowMs
						- captureStartTimeMs
						- Math.max(0, accumulatedPausedMs)
						- activePauseDurationMs);
	}
}
