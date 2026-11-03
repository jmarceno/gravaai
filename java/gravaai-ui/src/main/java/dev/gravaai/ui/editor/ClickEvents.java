package dev.gravaai.ui.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.CursorTelemetry.CursorInteractionType;
import dev.gravaai.services.recording.RecordingPaths;

/**
 * Reads how many clicks a recording's {@code .cursor.json} sidecar holds, so the "Click sound"
 * block can tell an unrecorded file from a recorded one (the sidecar is the only click source).
 */
final class ClickEvents {

	private ClickEvents() {
	}

	/** Number of click samples; 0 when there is no recording or no readable sidecar. */
	static int count(Path video) {
		if (video == null) {
			return 0;
		}
		Path sidecar = Path.of(RecordingPaths.getTelemetryPathForVideo(video.toString()));
		if (!Files.isRegularFile(sidecar)) {
			return 0;
		}
		try {
			var points = CursorTelemetry.normalizeCursorTelemetrySamples(
					Json.parseJsonWithByteOrderMark(Files.readString(sidecar)));
			int count = 0;
			for (var point : points) {
				CursorInteractionType type = point.interactionType();
				if (type == CursorInteractionType.CLICK
						|| type == CursorInteractionType.DOUBLE_CLICK
						|| type == CursorInteractionType.RIGHT_CLICK
						|| type == CursorInteractionType.MIDDLE_CLICK) {
					count++;
				}
			}
			return count;
		} catch (IOException | RuntimeException error) {
			return 0;
		}
	}
}
