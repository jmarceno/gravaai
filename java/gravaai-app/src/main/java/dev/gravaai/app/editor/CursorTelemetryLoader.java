package dev.gravaai.app.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.json.Json;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.RecordingPaths;

/**
 * Loads a recording's cursor telemetry sidecar ({@code <video>.cursor.json}) as compositor
 * samples, keeping the cursor type (sprite choice) and interaction type (click bounce/effects).
 * Preview and export share it so both draw the same cursor.
 */
public final class CursorTelemetryLoader {

	private CursorTelemetryLoader() {}

	/** The normalised samples, or an empty list when the sidecar is absent or invalid. */
	public static List<CursorSample> load(String videoPath) {
		if (videoPath == null || videoPath.isBlank()) {
			return List.of();
		}
		try {
			Path telemetry = Path.of(RecordingPaths.getTelemetryPathForVideo(videoPath));
			if (!Files.isRegularFile(telemetry)) {
				return List.of();
			}
			var points = CursorTelemetry.normalizeCursorTelemetrySamples(
					Json.parseJsonWithByteOrderMark(Files.readString(telemetry)));
			List<CursorSample> samples = new ArrayList<>(points.size());
			for (var point : points) {
				samples.add(new CursorSample(point.timeMs() / 1000.0, point.cx(), point.cy(),
						point.cursorType() == null ? null : point.cursorType().wire(),
						point.interactionType() == null ? null : point.interactionType().wire()));
			}
			return List.copyOf(samples);
		} catch (IOException | RuntimeException error) {
			return List.of();
		}
	}
}
