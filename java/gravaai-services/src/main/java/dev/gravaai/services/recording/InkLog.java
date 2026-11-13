package dev.gravaai.services.recording;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import dev.gravaai.services.recording.InkTelemetry.InkPoint;
import dev.gravaai.services.recording.InkTelemetry.InkRecording;
import dev.gravaai.services.recording.InkTelemetry.InkSession;
import dev.gravaai.services.recording.InkTelemetry.InkStroke;
import dev.gravaai.services.recording.InkTelemetry.InkTool;
import dev.gravaai.services.recording.cursor.FrameBounds;

/**
 * Records the live ink of one recording as it is drawn, for the {@code .ink.json} sidecar.
 *
 * <p>The overlay reports strokes in global logical pixels; this log stamps them with the
 * pause-aware capture clock and normalizes them against the recorded frame, through the same
 * {@link FrameBounds} the cursor telemetry uses, so ink and cursor line up in the editor.
 *
 * <p>Thread-safe: the overlay calls it on the FX thread and the recorder takes the result on its
 * own thread when the recording stops.
 */
public final class InkLog {

	private final LongSupplier clockMs;
	private final Supplier<Optional<FrameBounds>> frame;

	private final List<InkSession> sessions = new ArrayList<>();
	private final List<InkStroke> finished = new ArrayList<>();
	/** Strokes on screen now, in drawing order, keyed by id. */
	private final Map<String, OpenStroke> onScreen = new LinkedHashMap<>();
	private long sessionStartMs = -1;

	/** A stroke still on screen; {@code endMs} is -1 while the pen is down. */
	private static final class OpenStroke {
		final String id;
		final InkTool tool;
		final String color;
		final double width;
		final long startMs;
		long endMs = -1;
		final List<InkPoint> points = new ArrayList<>();

		OpenStroke(String id, InkTool tool, String color, double width, long startMs) {
			this.id = id;
			this.tool = tool;
			this.color = color;
			this.width = width;
			this.startMs = startMs;
		}
	}

	/**
	 * @param clockMs the recording's capture clock (pause-aware elapsed ms)
	 * @param frame the recorded frame in global logical pixels; empty when ink cannot be mapped
	 */
	public InkLog(LongSupplier clockMs, Supplier<Optional<FrameBounds>> frame) {
		this.clockMs = clockMs;
		this.frame = frame;
	}

	/** True while draw mode is on. */
	public synchronized boolean inSession() {
		return sessionStartMs >= 0;
	}

	/** Draw mode turned on. A second call while it is on is ignored. */
	public synchronized void beginSession() {
		if (sessionStartMs < 0) {
			sessionStartMs = clockMs.getAsLong();
		}
	}

	/** Draw mode turned off: every stroke still on screen is cleared now. */
	public synchronized void endSession() {
		if (sessionStartMs < 0) {
			return;
		}
		long now = clockMs.getAsLong();
		clearAllAt(now);
		sessions.add(new InkSession(sessionStartMs, Math.max(sessionStartMs, now)));
		sessionStartMs = -1;
	}

	/**
	 * The pen went down.
	 *
	 * @param widthPx the line width in logical pixels
	 * @return the stroke id, or empty outside a session or when the frame is unknown
	 */
	public synchronized Optional<String> beginStroke(InkTool tool, String color, double widthPx,
			double globalX, double globalY) {
		Optional<FrameBounds> bounds = frame.get();
		if (sessionStartMs < 0 || bounds.isEmpty()) {
			return Optional.empty();
		}
		String id = UUID.randomUUID().toString();
		long now = clockMs.getAsLong();
		OpenStroke stroke = new OpenStroke(id, tool, color.toLowerCase(java.util.Locale.ROOT),
				bounds.get().normalizeLength(widthPx), now);
		addPoint(stroke, bounds.get(), now, globalX, globalY);
		onScreen.put(id, stroke);
		return Optional.of(id);
	}

	/** The pen moved while down. */
	public synchronized void extendStroke(String id, double globalX, double globalY) {
		OpenStroke stroke = onScreen.get(id);
		Optional<FrameBounds> bounds = frame.get();
		if (stroke == null || stroke.endMs >= 0 || bounds.isEmpty()
				|| stroke.points.size() >= InkTelemetry.MAX_POINTS_PER_STROKE) {
			return;
		}
		addPoint(stroke, bounds.get(), clockMs.getAsLong(), globalX, globalY);
	}

	/** The pen went up. */
	public synchronized void endStroke(String id) {
		OpenStroke stroke = onScreen.get(id);
		if (stroke != null && stroke.endMs < 0) {
			stroke.endMs = Math.max(stroke.startMs, clockMs.getAsLong());
		}
	}

	/** Undo: the stroke left the screen. */
	public synchronized void removeStroke(String id) {
		OpenStroke stroke = onScreen.remove(id);
		if (stroke != null) {
			finish(stroke, clockMs.getAsLong());
		}
	}

	/** Clear: every stroke left the screen. */
	public synchronized void clearAll() {
		clearAllAt(clockMs.getAsLong());
	}

	/**
	 * Takes what was drawn and starts over. A session still open (the recording stopped while
	 * drawing) is closed at the current time.
	 */
	public synchronized InkRecording take() {
		endSession();
		InkRecording recording = new InkRecording(sessions, finished);
		sessions.clear();
		finished.clear();
		return recording;
	}

	/** Forgets everything (a cancelled or newly started recording). */
	public synchronized void reset() {
		sessions.clear();
		finished.clear();
		onScreen.clear();
		sessionStartMs = -1;
	}

	private void clearAllAt(long now) {
		for (OpenStroke stroke : onScreen.values()) {
			finish(stroke, now);
		}
		onScreen.clear();
	}

	private void finish(OpenStroke stroke, long now) {
		long end = stroke.endMs >= 0 ? stroke.endMs : Math.max(stroke.startMs, now);
		finished.add(new InkStroke(stroke.id, stroke.tool, stroke.color, stroke.width, stroke.startMs, end,
				Math.max(end, now), stroke.points));
	}

	private static void addPoint(OpenStroke stroke, FrameBounds bounds, long now, double globalX, double globalY) {
		double[] point = bounds.normalize(globalX, globalY);
		stroke.points.add(new InkPoint(now, point[0], point[1]));
	}
}
