package dev.gravaai.services.projects;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.UserData;
import tools.jackson.databind.JsonNode;

/**
 * Resolves the recordings directory, honouring the custom folder in
 * {@code recordings-settings.json} (port of {@code getRecordingsDir} and
 * {@code loadRecordingsDirectorySetting} in {@code electron/ipc/utils.ts}).
 *
 * <p>The setting is read once per run, exactly like the Electron module state; a missing, invalid or
 * non-object file falls back to {@code <userData>/recordings}. Only the read side lives here — the
 * recording service owns writing the setting.
 */
public final class RecordingsDirectory {

	private final UserData userData;
	private @Nullable Path customRecordingsDir;
	private boolean recordingsDirLoaded;

	public RecordingsDirectory(UserData userData) {
		this.userData = userData;
	}

	/** {@code getRecordingsDir}: the configured directory, created if missing. */
	public Path getRecordingsDir() throws IOException {
		loadRecordingsDirectorySetting();
		Path targetDir = customRecordingsDir != null ? customRecordingsDir : userData.recordingsDir();
		Files.createDirectories(targetDir);
		return targetDir;
	}

	private synchronized void loadRecordingsDirectorySetting() {
		if (recordingsDirLoaded) {
			return;
		}
		recordingsDirLoaded = true;

		try {
			JsonNode parsed = Json.parseJsonWithByteOrderMark(
					Files.readString(userData.recordingsSettingsFile(), StandardCharsets.UTF_8));
			JsonNode recordingsDir = parsed.get("recordingsDir");
			if (recordingsDir != null && recordingsDir.isString() && !recordingsDir.asString().isBlank()) {
				customRecordingsDir = Path.of(recordingsDir.asString()).toAbsolutePath().normalize();
			}
		} catch (IOException | RuntimeException error) {
			customRecordingsDir = null;
		}
	}
}
