package dev.gravaai.services.recording.cosmic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;
import dev.gravaai.services.recording.CursorTelemetrySource;
import dev.gravaai.services.recording.InkTelemetry;
import dev.gravaai.services.recording.KeystrokeSource;
import dev.gravaai.services.recording.KeystrokeTelemetry;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;

/**
 * Writes the cursor, key-press and ink sidecars next to a finished screen recording, ported from
 * {@code electron/ipc/cursor/telemetry.ts} and {@code electron/ipc/keyboard/keystrokeCapture.ts}.
 *
 * <p>The cursor clock starts when the renderer reports the recording as started, a little after
 * the first frame, so every sample is shifted onto the video's clock. Keys ride the same shift so
 * they line up with the first frame.
 */
final class CosmicTelemetryWriter {

	private static final System.Logger LOG = System.getLogger("gravaai.recording");

	/** Longest plausible gap between the video's first frame and the renderer's start. */
	private static final long MAX_SHIFT_MS = 5000;

	private final CursorTelemetrySource cursorTelemetry;
	private final KeystrokeSource keystrokes;

	CosmicTelemetryWriter(CursorTelemetrySource cursorTelemetry, KeystrokeSource keystrokes) {
		this.cursorTelemetry = cursorTelemetry;
		this.keystrokes = keystrokes;
	}

	/** Saves both sidecars for {@code outputPath}, whose first frame is at {@code startedAtMs}. */
	void persist(String outputPath, long startedAtMs) {
		long shiftMs = cursorTelemetry.cursorCaptureStartTimeMs() - startedAtMs;
		long appliedShiftMs = shiftMs > 0 && shiftMs < MAX_SHIFT_MS ? shiftMs : 0;
		persistCursor(outputPath, appliedShiftMs);
		persistKeystrokes(outputPath, appliedShiftMs);
		persistInk(outputPath, appliedShiftMs);
	}

	private void persistInk(String outputPath, long shiftMs) {
		try {
			InkTelemetry.InkRecording ink = cursorTelemetry.takeRecordedInk().shifted(shiftMs);
			InkTelemetry.write(outputPath, ink);
			if (!ink.isEmpty()) {
				LOG.log(System.Logger.Level.INFO, "Saved " + ink.sessions().size() + " draw sessions, "
						+ ink.strokes().size() + " strokes");
			}
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Failed to save ink: " + error);
		}
	}

	private void persistCursor(String outputPath, long shiftMs) {
		List<CursorTelemetryPoint> samples = new ArrayList<>();
		for (CursorTelemetryPoint sample : cursorTelemetry.snapshotAndTakePendingSamples()) {
			samples.add(new CursorTelemetryPoint(
					sample.timeMs() + shiftMs,
					sample.cx(),
					sample.cy(),
					sample.interactionType(),
					sample.cursorType()));
		}
		try {
			CursorTelemetry.writeCursorTelemetry(outputPath, samples);
			LOG.log(System.Logger.Level.INFO,
					"Saved " + samples.size() + " cursor samples (shifted " + shiftMs + "ms)");
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Failed to save cursor telemetry: " + error);
		}
	}

	private void persistKeystrokes(String outputPath, long shiftMs) {
		// Only the count is logged: the presses themselves are never logged.
		try {
			List<KeystrokeEvent> shifted = new ArrayList<>();
			for (KeystrokeEvent event : keystrokes.takeRecordedKeystrokes()) {
				shifted.add(new KeystrokeEvent(event.timeMs() + shiftMs, event.key(), event.modifiers()));
			}
			KeystrokeTelemetry.writeKeystrokeTelemetry(outputPath, shifted);
			LOG.log(System.Logger.Level.INFO, "Saved " + shifted.size() + " key presses");
		} catch (IOException error) {
			LOG.log(System.Logger.Level.WARNING, "Failed to save key presses: " + error);
		}
	}
}
