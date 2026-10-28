package dev.gravaai.ui.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.core.storage.StorageFiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads how many key presses a recording's {@code .keys.json} sidecar holds (the editor's
 * {@code keystrokeEvents.length} in SettingsPanel.tsx), so the "Key presses" block can tell an
 * unrecorded file from a recorded one.
 */
final class KeystrokeEvents {

	private KeystrokeEvents() {
	}

	/** Number of events with a key label; 0 when there is no recording or no readable sidecar. */
	static int count(Path video) {
		if (video == null) {
			return 0;
		}
		Path sidecar = Path.of(video + StorageFiles.KEYSTROKE_TELEMETRY_SUFFIX);
		if (!Files.isRegularFile(sidecar)) {
			return 0;
		}
		try {
			JsonNode events = JsonMapper.shared().readTree(Files.readString(sidecar)).path("events");
			int count = 0;
			if (events.isArray()) {
				for (JsonNode event : events) {
					if (event.path("key").isString() && !event.path("key").asString().isBlank()) {
						count++;
					}
				}
			}
			return count;
		} catch (IOException | RuntimeException error) {
			return 0;
		}
	}
}
