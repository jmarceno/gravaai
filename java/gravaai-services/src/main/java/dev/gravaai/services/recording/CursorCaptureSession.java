package dev.gravaai.services.recording;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.CursorTelemetry.CursorVisualType;
import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;
import dev.gravaai.services.recording.ports.RecordingEnvironment;
import dev.gravaai.services.recording.ports.RecordingProcesses;
import dev.gravaai.services.recording.ports.RecordingProcesses.SpawnOptions;
import dev.gravaai.services.recording.ports.SpawnedProcess;
import dev.gravaai.services.sources.SourceArea;
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
 */
public final class CursorCaptureSession implements CursorTelemetrySource, KeystrokeSource {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	private static final Pattern POSITION_LINE = Pattern.compile("^POS:(-?\\d+):(-?\\d+)$");
	private static final Pattern INTERACTION_LINE =
			Pattern.compile("^INTERACTION:(mousedown|mouseup)(?::([123]))?$");
	/** A second left click within these bounds is a double-click. */
	private static final long DOUBLE_CLICK_THRESHOLD_MS = 350;
	private static final double DOUBLE_CLICK_MAX_DISTANCE = 0.04;

	private final RecordingProcesses processes;
	private final RecordingEnvironment environment;
	private final CursorScreen screen;
	private final Supplier<SelectedSource> selectedSource;
	private final Supplier<WindowBounds> selectedWindowBounds;
	private final Supplier<String> keystrokeCaptureModeSetting;
	private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "gravaai-cursor-sampler");
		thread.setDaemon(true);
		return thread;
	});

	private final CursorCaptureClock clock = new CursorCaptureClock();
	private final List<CursorTelemetryPoint> activeSamples = new ArrayList<>();
	private final List<CursorTelemetryPoint> pendingSamples = new ArrayList<>();

	private volatile boolean captureActive;
	private volatile long captureStartTimeMs;
	private volatile CursorVisualType currentCursorVisualType;
	private volatile SpawnedProcess helper;
	private volatile boolean helperActive;
	private volatile Long waylandCaptureDisplayId;
	private long linuxCursorX;
	private long linuxCursorY;
	private long linuxCursorUpdatedAt;
	private boolean hasLinuxCursorPoint;
	private ScheduledFuture<?> sampleTimer;
	private long nextExpectedMs;
	private KeystrokeCapture.KeystrokeRecorder keystrokeRecorder;
	private LastLeftClick lastLeftClick;

	private record LastLeftClick(long timeMs, double cx, double cy) {
	}

	public CursorCaptureSession(
			RecordingProcesses processes,
			RecordingEnvironment environment,
			CursorScreen screen,
			Supplier<SelectedSource> selectedSource,
			Supplier<WindowBounds> selectedWindowBounds,
			Supplier<String> keystrokeCaptureModeSetting) {
		this.processes = processes;
		this.environment = environment;
		this.screen = screen;
		this.selectedSource = selectedSource;
		this.selectedWindowBounds = selectedWindowBounds;
		this.keystrokeCaptureModeSetting = keystrokeCaptureModeSetting;
	}

	/** Port of {@code normalizeHookMouseButton}. */
	public static int normalizeHookMouseButton(Object rawButton) {
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

	public boolean isCursorCaptureActive() {
		return captureActive;
	}

	public void setCurrentCursorVisualType(CursorVisualType cursorType) {
		this.currentCursorVisualType = cursorType;
	}

	/** Starts sampling; called when the renderer reports the recording as started. */
	public synchronized void start() {
		stopSampling();
		captureActive = true;
		activeSamples.clear();
		pendingSamples.clear();
		captureStartTimeMs = System.currentTimeMillis();
		clock.setStartTimeMs(captureStartTimeMs);
		clock.reset();
		waylandCaptureDisplayId = null;
		hasLinuxCursorPoint = false;
		lastLeftClick = null;
		startSampling();
		startInteractionCapture();
	}

	/** Stops sampling and the helper; snapshots the samples for persistence. */
	public synchronized void stop() {
		captureActive = false;
		stopSampling();
		stopInteractionCapture();
		snapshotCursorTelemetryForPersistence();
		activeSamples.clear();
	}

	public synchronized void pause(long pausedAtMs) {
		List<CursorTelemetryPoint> kept = clock.pauseCursorCaptureAtBoundary(pausedAtMs, activeSamples);
		activeSamples.clear();
		activeSamples.addAll(kept);
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
		CursorScreen.Point fallback;
		try {
			fallback = screen.cursorScreenPoint();
		} catch (RuntimeException error) {
			fallback = new CursorScreen.Point(0, 0);
		}
		boolean cacheFresh = hasLinuxCursorPoint
				&& (helperActive || System.currentTimeMillis() - linuxCursorUpdatedAt <= 1000);
		CursorScreen.Display primary = screen.primaryDisplay();
		double primaryScale = primary != null && primary.scaleFactor() > 0 ? primary.scaleFactor() : 1;

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
			if (display == null && helperActive && cacheFresh) {
				// The portal hides which screen was picked; assume the one the cursor
				// was on when recording started and keep normalizing against it.
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

	private void startSampling() {
		nextExpectedMs = System.currentTimeMillis() + CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS;
		sampleTimer = sampler.schedule(this::sampleTick, CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS,
				TimeUnit.MILLISECONDS);
	}

	private void stopSampling() {
		if (sampleTimer != null) {
			sampleTimer.cancel(false);
			sampleTimer = null;
		}
	}

	// Use recursive scheduling with drift compensation instead of a fixed-rate timer:
	// under CPU load a fixed-rate timer bunches or skips callbacks, creating large gaps
	// in telemetry data.
	private void sampleTick() {
		if (captureActive && !clock.isCursorCapturePaused()) {
			sampleCursorPoint();
		}
		long now = System.currentTimeMillis();
		long drift = now - nextExpectedMs;
		nextExpectedMs += CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS;
		// If we fell behind by more than one full interval, reset the baseline
		// so we don't try to "catch up" with a burst of rapid samples.
		if (drift > CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS) {
			nextExpectedMs = now + CursorTelemetry.CURSOR_SAMPLE_INTERVAL_MS;
		}
		long delay = Math.max(1, nextExpectedMs - now);
		synchronized (this) {
			if (!captureActive) {
				return;
			}
			sampleTimer = sampler.schedule(this::sampleTick, delay, TimeUnit.MILLISECONDS);
		}
	}

	public void sampleCursorPoint() {
		double[] point;
		try {
			point = getNormalizedCursorPoint();
		} catch (RuntimeException error) {
			return;
		}
		CursorTelemetry.pushSample(
				activeSamples,
				point[0],
				point[1],
				clock.getCursorCaptureElapsedMs(),
				CursorInteractionType.MOVE,
				currentCursorVisualType);
	}

	/** Replaces samples taken before the Wayland helper reported a position. */
	private void backfillCursorSamples(double cx, double cy) {
		for (int index = 0; index < activeSamples.size(); index++) {
			CursorTelemetryPoint sample = activeSamples.get(index);
			activeSamples.set(index, new CursorTelemetryPoint(
					sample.timeMs(), cx, cy, sample.interactionType(), sample.cursorType()));
		}
	}

	private void recordCursorMouseDown(int button) {
		if (!captureActive || clock.isCursorCapturePaused()) {
			return;
		}
		double[] point;
		try {
			point = getNormalizedCursorPoint();
		} catch (RuntimeException error) {
			return;
		}
		long timeMs = clock.getCursorCaptureElapsedMs();
		CursorInteractionType interactionType = CursorInteractionType.CLICK;
		if (button == 2) {
			interactionType = CursorInteractionType.RIGHT_CLICK;
		} else if (button == 3) {
			interactionType = CursorInteractionType.MIDDLE_CLICK;
		} else {
			double distance = lastLeftClick == null
					? Double.POSITIVE_INFINITY
					: Math.hypot(point[0] - lastLeftClick.cx(), point[1] - lastLeftClick.cy());
			if (lastLeftClick != null
					&& timeMs - lastLeftClick.timeMs() <= DOUBLE_CLICK_THRESHOLD_MS
					&& distance <= DOUBLE_CLICK_MAX_DISTANCE) {
				interactionType = CursorInteractionType.DOUBLE_CLICK;
			}
			lastLeftClick = new LastLeftClick(timeMs, point[0], point[1]);
		}
		CursorTelemetry.pushSample(
				activeSamples, point[0], point[1], timeMs, interactionType, currentCursorVisualType);
	}

	private void recordCursorMouseUp() {
		if (!captureActive || clock.isCursorCapturePaused()) {
			return;
		}
		double[] point;
		try {
			point = getNormalizedCursorPoint();
		} catch (RuntimeException error) {
			return;
		}
		CursorTelemetry.pushSample(
				activeSamples,
				point[0],
				point[1],
				clock.getCursorCaptureElapsedMs(),
				CursorInteractionType.MOUSEUP,
				currentCursorVisualType);
	}

	private void startInteractionCapture() {
		if (!captureActive) {
			return;
		}
		stopInteractionCapture();
		// Privacy first: keys are off unless the user picked a mode, and only the
		// sidecar next to the video is ever written.
		KeystrokeCaptureMode mode =
				KeystrokeCapture.normalizeKeystrokeCaptureMode(keystrokeCaptureModeSetting.get());
		keystrokeRecorder = mode == KeystrokeCaptureMode.OFF
				? null
				: KeystrokeCapture.createKeystrokeRecorder(mode);
		KeystrokeCapture.setActiveKeystrokeRecorder(keystrokeRecorder);
		List<String> command = new ArrayList<>();
		command.add(environment.cursorHelper().toString());
		if (keystrokeRecorder != null) {
			command.add("--keys");
		}
		try {
			helper = processes.spawn(command, new SpawnOptions(true, true, true));
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Wayland cursor helper failed: " + error);
			helper = null;
			return;
		}
		helperActive = true;
		SpawnedProcess currentHelper = helper;
		Thread.ofPlatform().daemon().name("gravaai-wayland-cursor").start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(currentHelper.stdout(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					handleHelperLine(line);
				}
			} catch (IOException error) {
				// Helper exited.
			}
		});
		drainHelperErrors(currentHelper);
	}

	private static void drainHelperErrors(SpawnedProcess helper) {
		if (helper.stderr() == null) {
			return;
		}
		Thread.ofPlatform().daemon().name("gravaai-wayland-cursor-err").start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(helper.stderr(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					LOG.log(System.Logger.Level.WARNING, "Wayland cursor helper: " + line.trim());
				}
			} catch (IOException error) {
				// Helper exited.
			}
		});
	}

	private void handleHelperLine(String line) {
		Matcher position = POSITION_LINE.matcher(line);
		if (position.matches()) {
			// Helper points are logical; the telemetry cache stores physical pixels.
			CursorScreen.Display primary = screen.primaryDisplay();
			double scale = primary != null && primary.scaleFactor() > 0 ? primary.scaleFactor() : 1;
			boolean first = !hasLinuxCursorPoint;
			hasLinuxCursorPoint = true;
			linuxCursorX = Math.round(Long.parseLong(position.group(1)) * scale);
			linuxCursorY = Math.round(Long.parseLong(position.group(2)) * scale);
			linuxCursorUpdatedAt = System.currentTimeMillis();
			if (first) {
				try {
					double[] point = getNormalizedCursorPoint();
					backfillCursorSamples(point[0], point[1]);
				} catch (RuntimeException error) {
					// Geometry not ready yet.
				}
			}
			return;
		}
		if (keystrokeRecorder != null && line.startsWith("KEY:")) {
			// Modifier state is always tracked so a Ctrl released during a
			// pause cannot stick; presses are only stored while recording.
			keystrokeRecorder.handleLine(
					line,
					clock.getCursorCaptureElapsedMs(),
					captureActive && !clock.isCursorCapturePaused());
			return;
		}
		Matcher interaction = INTERACTION_LINE.matcher(line);
		if (!interaction.matches()) {
			return;
		}
		if ("mouseup".equals(interaction.group(1))) {
			recordCursorMouseUp();
		} else {
			recordCursorMouseDown(normalizeHookMouseButton(
					interaction.group(2) == null ? null : Integer.valueOf(interaction.group(2))));
		}
	}

	private void stopInteractionCapture() {
		helperActive = false;
		SpawnedProcess currentHelper = helper;
		helper = null;
		if (currentHelper != null) {
			try {
				OutputStream stdin = currentHelper.stdin();
				if (stdin != null) {
					stdin.close();
				}
			} catch (IOException error) {
				// Already closed.
			}
			currentHelper.destroy();
		}
	}

	/** Port of {@code snapshotCursorTelemetryForPersistence}: merge active samples into pending. */
	public synchronized void snapshotCursorTelemetryForPersistence() {
		if (activeSamples.isEmpty()) {
			return;
		}
		List<CursorTelemetryPoint> merged =
				CursorTelemetry.mergeForPersistence(activeSamples, pendingSamples);
		pendingSamples.clear();
		pendingSamples.addAll(merged);
	}

	@Override
	public synchronized List<CursorTelemetryPoint> snapshotAndTakePendingSamples() {
		snapshotCursorTelemetryForPersistence();
		List<CursorTelemetryPoint> taken = List.copyOf(pendingSamples);
		pendingSamples.clear();
		return taken;
	}

	@Override
	public List<KeystrokeEvent> takeRecordedKeystrokes() {
		KeystrokeCapture.KeystrokeRecorder recorder = keystrokeRecorder;
		return recorder == null ? List.of() : recorder.takeEvents();
	}

	/** The modifier order used by the keystroke renderer; exposed for the UI. */
	public static List<KeyModifier> modifierOrder() {
		return KeystrokeTypes.KEYSTROKE_MODIFIER_ORDER;
	}
}
