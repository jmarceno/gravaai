package dev.gravaai.services.recording.cursor;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

import dev.gravaai.services.recording.CursorCaptureClock;
import dev.gravaai.services.recording.CursorScreen;
import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.CursorTelemetry.CursorVisualType;
import dev.gravaai.services.recording.CursorTelemetrySource;
import dev.gravaai.services.recording.KeystrokeSource;
import dev.gravaai.services.recording.KeystrokeTypes;
import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.sources.WindowBounds;

/**
 * Cursor position and mouse-button sampling during a recording, ported from
 * {@code electron/ipc/cursor/telemetry.ts}, {@code electron/ipc/cursor/interaction.ts} and the
 * {@code set-recording-state} handler in {@code electron/ipc/register/recording.ts} (plan §5.6.7).
 *
 * <p>On Wayland a native helper reports the pointer position (ext-image-copy-capture cursor
 * sessions) and mouse buttons (evdev), and — with a capture mode chosen in Settings — key presses,
 * which are only ever written next to the recording. Samples are taken every 33 ms with drift
 * compensation so CPU load cannot create large gaps.
 *
 * <p>This class is the facade the recording service drives. The work is split by concern:
 * {@link CursorHelperSession} owns the child process and its line protocol,
 * {@link CursorSampler} the sampling loop, {@link CursorInteractionTracker} the click semantics,
 * {@link CursorCoordinateMapper} the source-relative normalization and
 * {@link CursorSampleBuffer} the sample sets.
 */
public final class CursorCaptureSession
		implements CursorTelemetrySource, KeystrokeSource, AutoCloseable, CursorHelperSession.Listener {

	private final CursorHelperSession helper;
	private final CursorSampler sampler;
	private final CursorInteractionTracker interactions;
	private final CursorCoordinateMapper coordinates;
	private final CursorSampleBuffer samples = new CursorSampleBuffer();
	private final CursorCaptureClock clock = new CursorCaptureClock();

	private volatile boolean captureActive;
	private volatile long captureStartTimeMs;
	private volatile @Nullable CursorVisualType currentCursorVisualType;

	public CursorCaptureSession(
			RecordingProcesses processes,
			RecordingEnvironment environment,
			CursorScreen screen,
			Supplier<SelectedSource> selectedSource,
			Supplier<WindowBounds> selectedWindowBounds,
			Supplier<String> keystrokeCaptureModeSetting) {
		this.helper = new CursorHelperSession(processes, environment, keystrokeCaptureModeSetting);
		this.coordinates = new CursorCoordinateMapper(
				screen, selectedSource, selectedWindowBounds, helper::isHelperActive);
		this.sampler = new CursorSampler(this::captureTick);
		this.interactions = new CursorInteractionTracker(samples, clock, coordinates);
		helper.setListener(this);
	}

	/** Port of {@code normalizeHookMouseButton}. */
	public static int normalizeHookMouseButton(@Nullable Object rawButton) {
		return CursorInteractionTracker.normalizeHookMouseButton(rawButton);
	}

	public boolean isCursorCaptureActive() {
		return captureActive;
	}

	public void setCurrentCursorVisualType(CursorVisualType cursorType) {
		this.currentCursorVisualType = cursorType;
	}

	/** Starts sampling; called when the renderer reports the recording as started. */
	public synchronized void start() {
		sampler.stop();
		captureActive = true;
		samples.clear();
		captureStartTimeMs = System.currentTimeMillis();
		clock.setStartTimeMs(captureStartTimeMs);
		clock.reset();
		coordinates.reset();
		interactions.reset();
		sampler.start();
		helper.start();
	}

	/** Stops sampling and the helper; snapshots the samples for persistence. */
	public synchronized void stop() {
		captureActive = false;
		sampler.stop();
		helper.stop();
		snapshotCursorTelemetryForPersistence();
		samples.clearActive();
	}

	/** Pauses at the renderer's pause moment, dropping samples taken after it. */
	public synchronized void pause(long pausedAtMs) {
		samples.replaceActive(clock.pauseCursorCaptureAtBoundary(pausedAtMs, samples.active()));
	}

	public synchronized void resume(long resumedAtMs) {
		clock.resumeCursorCapture(resumedAtMs);
		sampleCursorPoint();
	}

	public long cursorCaptureStartTimeMs() {
		return captureStartTimeMs;
	}

	public boolean isPaused() {
		return clock.isCursorCapturePaused();
	}

	/** The latest normalized cursor position, for the HUD/UI. */
	public double[] getNormalizedCursorPoint() {
		return coordinates.normalize();
	}

	/** True while samples should be taken: recording and not paused. */
	private boolean isSamplingLive() {
		return captureActive && !clock.isCursorCapturePaused();
	}

	/** One sample at the current position, if the geometry is ready. */
	public void sampleCursorPoint() {
		double[] point;
		try {
			point = getNormalizedCursorPoint();
		} catch (RuntimeException error) {
			return;
		}
		samples.push(point[0], point[1], clock.getCursorCaptureElapsedMs(),
				CursorInteractionType.MOVE, currentCursorVisualType);
	}

	private void captureTick() {
		if (isSamplingLive()) {
			sampleCursorPoint();
		}
	}

	// ── Helper protocol (CursorHelperSession.Listener) ─────────────────────

	@Override
	public void onPosition(long logicalX, long logicalY) {
		boolean first = coordinates.recordPosition(logicalX, logicalY);
		if (!first) {
			return;
		}
		try {
			double[] point = getNormalizedCursorPoint();
			samples.backfill(point[0], point[1]);
		} catch (RuntimeException error) {
			// Geometry not ready yet.
		}
	}

	@Override
	public void onKeyLine(String line) {
		// Modifier state is always tracked so a Ctrl released during a pause cannot stick;
		// presses are only stored while recording.
		helper.feedKeyLine(line, clock.getCursorCaptureElapsedMs(), isSamplingLive());
	}

	@Override
	public void onInteraction(boolean mouseDown, @Nullable Integer button) {
		if (!isSamplingLive()) {
			return;
		}
		interactions.record(mouseDown, button, currentCursorVisualType);
	}

	// ── Telemetry snapshots ────────────────────────────────────────────────

	/** Port of {@code snapshotCursorTelemetryForPersistence}: merge active samples into pending. */
	public synchronized void snapshotCursorTelemetryForPersistence() {
		samples.snapshotForPersistence();
	}

	@Override
	public synchronized List<CursorTelemetryPoint> snapshotAndTakePendingSamples() {
		samples.snapshotForPersistence();
		return samples.takePending();
	}

	@Override
	public List<KeystrokeEvent> takeRecordedKeystrokes() {
		return helper.takeRecordedKeystrokes();
	}

	/** The modifier order used by the keystroke renderer; exposed for the UI. */
	public static List<KeystrokeTypes.KeyModifier> modifierOrder() {
		return KeystrokeTypes.KEYSTROKE_MODIFIER_ORDER;
	}

	/** Stops the cursor sampler (app shutdown). */
	@Override
	public void close() {
		sampler.close();
	}
}
