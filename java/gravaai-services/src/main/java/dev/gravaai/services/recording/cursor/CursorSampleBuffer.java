package dev.gravaai.services.recording.cursor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.CursorTelemetry.CursorVisualType;

/**
 * The active and pending cursor sample sets, ported from the module state of
 * {@code electron/ipc/cursor/telemetry.ts}.
 *
 * <p>Active samples are taken while recording. Persisting merges them into pending, and taking
 * pending hands them to the recorder once, so a sample is never written twice.
 */
final class CursorSampleBuffer {

	/** The largest sample count the sidecar keeps, from the shared storage contract. */
	private static final int MAX_SAMPLES = CursorTelemetry.MAX_CURSOR_SAMPLES;

	private final List<CursorTelemetryPoint> active = new ArrayList<>();
	private final List<CursorTelemetryPoint> pending = new ArrayList<>();

	/** The live sample list; the clock prunes it at a pause boundary. */
	List<CursorTelemetryPoint> active() {
		return active;
	}

	void clear() {
		active.clear();
		pending.clear();
	}

	void clearActive() {
		active.clear();
	}

	/** Swaps in the samples that survived a pause boundary, keeping insertion order. */
	void replaceActive(List<CursorTelemetryPoint> kept) {
		active.clear();
		active.addAll(kept);
	}

	/**
	 * Appends one sample, keeping the list bounded.
	 *
	 * @param cursorType the current visual, or null when the helper has not reported one
	 */
	void push(double cx, double cy, double timeMs, CursorInteractionType interactionType,
			@Nullable CursorVisualType cursorType) {
		active.add(new CursorTelemetryPoint(Math.max(0, timeMs), cx, cy, interactionType, cursorType));
		if (active.size() > MAX_SAMPLES) {
			active.remove(0);
		}
	}

	/** Replaces samples taken before the helper reported a position. */
	void backfill(double cx, double cy) {
		for (int index = 0; index < active.size(); index++) {
			CursorTelemetryPoint sample = active.get(index);
			active.set(index, new CursorTelemetryPoint(
					sample.timeMs(), cx, cy, sample.interactionType(), sample.cursorType()));
		}
	}

	/** Port of {@code snapshotCursorTelemetryForPersistence}: merge active samples into pending. */
	void snapshotForPersistence() {
		if (active.isEmpty()) {
			return;
		}
		List<CursorTelemetryPoint> merged = CursorTelemetry.mergeForPersistence(active, pending);
		pending.clear();
		pending.addAll(merged);
	}

	/** Returns the pending samples and clears them. */
	List<CursorTelemetryPoint> takePending() {
		List<CursorTelemetryPoint> taken = List.copyOf(pending);
		pending.clear();
		return taken;
	}
}
