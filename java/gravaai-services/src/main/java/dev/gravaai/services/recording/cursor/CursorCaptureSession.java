package dev.gravaai.services.recording.cursor;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import dev.gravaai.services.recording.CursorCaptureClock;
import dev.gravaai.services.recording.CursorScreen;
import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.CursorTelemetry.CursorVisualType;
import dev.gravaai.services.recording.CursorTelemetrySource;
import dev.gravaai.services.recording.InkLog;
import dev.gravaai.services.recording.InkTelemetry;
import dev.gravaai.services.recording.KeystrokeSource;
import dev.gravaai.services.recording.KeystrokeTypes;
import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;
import dev.gravaai.services.recording.WindowSources;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.sources.SelectedSource;

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
	private final InkLog ink;

	private volatile boolean captureActive;
	private volatile long captureStartTimeMs;
	private volatile @Nullable CursorVisualType currentCursorVisualType;
	/**
	 * The capture stream's own feed (pointer readings and the surface it records), as an explicit
	 * state machine shared by the helper's stderr thread and {@link #start()}/{@link #stop()}:
	 *
	 * <pre>
	 *   Idle(pending) ──start()──▶ Armed ──stop()──▶ Idle(nothing)
	 *   Idle(pending) ──reading─▶ Idle(newest reading)
	 *   Armed         ──reading─▶ Armed   (applied to the mapper at once)
	 * </pre>
	 *
	 * <p>The helper reports its first position and its surface as soon as the stream is up, which
	 * precedes the recording-state message that calls start(), so an Idle state keeps them. Every
	 * transition is one atomic update, so a reading that races start() either lands in the Idle
	 * state start() swaps out (and is adopted) or sees Armed (and is applied): it can no longer be
	 * written after start() looked and be lost.
	 */
	private final AtomicReference<CaptureFeed> captureFeed =
			new AtomicReference<>(CaptureFeed.Idle.EMPTY);

	/** How long a pre-start capture reading stays adoptable; older ones are previous-take leftovers. */
	private static final long ADOPT_PENDING_AFTER_MS = 1000;

	/** See {@link #captureFeed}. */
	private sealed interface CaptureFeed {

		/** Not recording: the newest early reading and surface, kept for the next start(). */
		record Idle(Optional<PendingPoint> point, Optional<WindowSources.CaptureSurface> surface)
				implements CaptureFeed {
			static final Idle EMPTY = new Idle(Optional.empty(), Optional.empty());
		}

		/** Recording: readings go straight to the coordinate mapper. */
		record Armed() implements CaptureFeed {
		}
	}

	/** A pre-start pointer reading and the wall-clock time it arrived. */
	private record PendingPoint(double x, double y, long atMs) {
	}

	public CursorCaptureSession(
			RecordingProcesses processes,
			RecordingEnvironment environment,
			CursorScreen screen,
			Supplier<SelectedSource> selectedSource,
			Supplier<String> keystrokeCaptureModeSetting) {
		this.helper = new CursorHelperSession(processes, environment, keystrokeCaptureModeSetting);
		this.coordinates = new CursorCoordinateMapper(
				screen, selectedSource, helper::isHelperActive);
		this.sampler = new CursorSampler(this::captureTick);
		this.interactions = new CursorInteractionTracker(samples, clock, coordinates);
		helper.setListener(this);
		this.ink = new InkLog(clock::getCursorCaptureElapsedMs, this::recordedFrameBounds);
	}

	/** The live ink of the recording in progress; the draw overlay feeds it. */
	public InkLog ink() {
		return ink;
	}

	@Override
	public InkTelemetry.InkRecording takeRecordedInk() {
		return ink.take();
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
		samples.clear();
		captureStartTimeMs = System.currentTimeMillis();
		clock.setStartTimeMs(captureStartTimeMs);
		clock.reset();
		coordinates.reset();
		interactions.reset();
		ink.reset();
		sampler.start();
		helper.start();
		captureActive = true;
		// Arming comes last: whatever the stream reported before now is in the Idle state this
		// swap takes out, and everything after it is applied directly.
		if (captureFeed.getAndSet(new CaptureFeed.Armed()) instanceof CaptureFeed.Idle idle) {
			idle.surface().ifPresent(coordinates::recordCaptureSurface);
			idle.point()
					.filter(point -> System.currentTimeMillis() - point.atMs()
							<= ADOPT_PENDING_AFTER_MS)
					.ifPresent(point -> applyCapturePoint(point.x(), point.y()));
		}
	}

	/** Stops sampling and the helper; snapshots the samples for persistence. */
	public synchronized void stop() {
		captureActive = false;
		captureFeed.set(CaptureFeed.Idle.EMPTY);
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

	/**
	 * The frame the active recording records, in global logical pixels; empty for a window
	 * recording or when nothing records. Live ink normalizes against it, so ink and cursor share
	 * one mapping.
	 */
	public java.util.Optional<FrameBounds> recordedFrameBounds() {
		return captureActive ? coordinates.recordedFrameBounds() : java.util.Optional.empty();
	}

	/** Recording time on the pause-aware capture clock, the time base of every sidecar. */
	public long captureElapsedMs() {
		return clock.getCursorCaptureElapsedMs();
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

	/**
	 * A pointer position the capture backend read inside the recorded surface (0..1 of the frame).
	 *
	 * <p>Called on the capture helper's stderr thread. The first reading after a recording starts
	 * also backfills the samples taken before it, the same way the cursor helper's first position
	 * does, so a take that starts with a still pointer is still anchored.
	 */
	@Override
	public void onCaptureCursorPoint(double cx, double cy) {
		// Before start() the reading is kept: a still pointer never sends a second line, so
		// dropping this one would leave the whole take unanchored (a window recording falls back
		// to the frame centre).
		PendingPoint point = new PendingPoint(cx, cy, System.currentTimeMillis());
		CaptureFeed state = captureFeed.updateAndGet(current -> switch (current) {
			case CaptureFeed.Idle idle -> new CaptureFeed.Idle(Optional.of(point), idle.surface());
			case CaptureFeed.Armed armed -> armed;
		});
		if (state instanceof CaptureFeed.Armed) {
			applyCapturePoint(cx, cy);
		}
	}

	/**
	 * What the capture stream records, as the helper reported it. A portal user can share a window
	 * from the desktop's dialog while the app selected "a screen": only this tells the mapper that
	 * the global pointer no longer belongs to the recorded frame.
	 */
	@Override
	public void onCaptureSurface(WindowSources.CaptureSurface surface) {
		CaptureFeed state = captureFeed.updateAndGet(current -> switch (current) {
			case CaptureFeed.Idle idle -> new CaptureFeed.Idle(idle.point(), Optional.of(surface));
			case CaptureFeed.Armed armed -> armed;
		});
		if (state instanceof CaptureFeed.Armed) {
			coordinates.recordCaptureSurface(surface);
		}
	}

	/** Records one capture-stream reading and, when it is the first, backfills the samples. */
	private void applyCapturePoint(double cx, double cy) {
		boolean first = !coordinates.hasCapturePoint();
		coordinates.recordCapturePoint(cx, cy);
		if (!first) {
			return;
		}
		try {
			double[] point = coordinates.normalize();
			synchronized (this) {
				samples.backfill(point[0], point[1]);
			}
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
