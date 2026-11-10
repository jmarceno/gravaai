package dev.gravaai.services.projects;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.json.JsonFile;
import dev.gravaai.core.storage.UserData;
import tools.jackson.databind.JsonNode;

/**
 * Resolves the recordings directory, honouring the custom folder in
 * {@code recordings-settings.json} (port of {@code getRecordingsDir} and
 * {@code loadRecordingsDirectorySetting} in {@code electron/ipc/utils.ts}).
 *
 * <p>A missing, invalid or non-object file falls back to {@code <userData>/recordings}. The
 * setting is re-read on every resolution, so a folder changed in Settings → Recording is followed
 * at once by the recorder, the library and every other consumer of the supplier (card "Recording
 * settings"); this class also owns writing the setting.
 */
public final class RecordingsDirectory {

	private final UserData userData;

	public RecordingsDirectory(UserData userData) {
		this.userData = userData;
	}

	/** {@code getRecordingsDir}: the configured directory, created if missing. */
	public Path getRecordingsDir() throws IOException {
		Path custom = customRecordingsDir();
		Path targetDir = custom != null ? custom : userData.recordingsDir();
		Files.createDirectories(targetDir);
		return targetDir;
	}

	/**
	 * Writes the custom recordings folder (card "Recording settings"); {@code null} removes the
	 * setting and falls back to the default folder. Every other key in the file survives, and the
	 * next {@link #getRecordingsDir()} returns the new folder.
	 */
	public void setCustomRecordingsDir(@Nullable Path dir) throws IOException {
		Map<String, JsonNode> store = JsonFile.readObjectOrEmpty(userData.recordingsSettingsFile());
		if (dir == null) {
			store.remove("recordingsDir");
		} else {
			store.put("recordingsDir", Json.nodeFactory().stringNode(
					dir.toAbsolutePath().normalize().toString()));
		}
		JsonFile.writeObject(userData.recordingsSettingsFile(), store);
	}

	private @Nullable Path customRecordingsDir() {
		try {
			JsonNode parsed = Json.parseJsonWithByteOrderMark(
					Files.readString(userData.recordingsSettingsFile(), StandardCharsets.UTF_8));
			JsonNode recordingsDir = parsed.get("recordingsDir");
			if (recordingsDir != null && recordingsDir.isString() && !recordingsDir.asString().isBlank()) {
				return Path.of(recordingsDir.asString()).toAbsolutePath().normalize();
			}
		} catch (IOException | RuntimeException error) {
			return null;
		}
		return null;
	}
}
