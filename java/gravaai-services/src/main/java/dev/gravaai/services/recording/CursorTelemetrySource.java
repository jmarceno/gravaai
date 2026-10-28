package dev.gravaai.services.recording;

import java.util.List;

import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;

/** The cursor-capture session state the recorder reads when it saves telemetry. */
public interface CursorTelemetrySource {

	/** Wall-clock time the renderer reported the recording as started. */
	long cursorCaptureStartTimeMs();

	/**
	 * Snapshots the active samples into the pending set (port of
	 * {@code snapshotCursorTelemetryForPersistence}) and returns them, clearing the pending set.
	 */
	List<CursorTelemetryPoint> snapshotAndTakePendingSamples();
}
