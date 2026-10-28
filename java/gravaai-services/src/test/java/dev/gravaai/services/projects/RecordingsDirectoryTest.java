package dev.gravaai.services.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.storage.UserData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Port of {@code getRecordingsDir} and the recordings-directory setting in {@code ipc/utils.ts}. */
class RecordingsDirectoryTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("creates and returns the default recordings directory")
	void createsDefaultRecordingsDirectory() throws Exception {
		UserData userData = new UserData(tempDir.resolve("Gravaai"));

		Path directory = new RecordingsDirectory(userData).getRecordingsDir();

		assertEquals(userData.recordingsDir(), directory);
		assertTrue(Files.isDirectory(directory));
	}

	@Test
	@DisplayName("uses the custom recordings directory from recordings-settings.json")
	void usesCustomRecordingsDirectory() throws Exception {
		UserData userData = new UserData(tempDir.resolve("Gravaai"));
		Path custom = tempDir.resolve("Videos").resolve("Gravaai");
		Files.createDirectories(userData.dir());
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("recordingsDir", custom.toString());
		Json.writeFile(userData.recordingsSettingsFile(), settings);

		assertEquals(custom, new RecordingsDirectory(userData).getRecordingsDir());
		assertTrue(Files.isDirectory(custom));
	}

	@Test
	@DisplayName("tolerates a byte order mark and falls back to the default for invalid settings")
	void toleratesByteOrderMarkAndInvalidSettings() throws Exception {
		UserData userData = new UserData(tempDir.resolve("Gravaai"));
		Files.createDirectories(userData.dir());
		Path custom = tempDir.resolve("Marked");
		Files.writeString(userData.recordingsSettingsFile(),
				"\uFEFF" + Json.stringify(Map.of("recordingsDir", custom.toString())), StandardCharsets.UTF_8);

		assertEquals(custom, new RecordingsDirectory(userData).getRecordingsDir());

		UserData invalid = new UserData(tempDir.resolve("Broken"));
		Files.createDirectories(invalid.dir());
		Files.writeString(invalid.recordingsSettingsFile(), "not json", StandardCharsets.UTF_8);

		assertEquals(invalid.recordingsDir(), new RecordingsDirectory(invalid).getRecordingsDir());
	}
}
