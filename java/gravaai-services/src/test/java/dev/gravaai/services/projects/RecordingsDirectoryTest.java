package dev.gravaai.services.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.json.JsonFile;
import dev.gravaai.core.storage.UserData;
import tools.jackson.databind.JsonNode;
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

	@Test
	@DisplayName("a folder written through the store is followed by the next resolution")
	void writtenFolderIsFollowed() throws Exception {
		UserData userData = new UserData(tempDir.resolve("Gravaai"));
		RecordingsDirectory directory = new RecordingsDirectory(userData);
		assertEquals(userData.recordingsDir(), directory.getRecordingsDir());

		Path custom = tempDir.resolve("Clips");
		directory.setCustomRecordingsDir(custom);

		assertEquals(custom, directory.getRecordingsDir());
		assertTrue(Files.isDirectory(custom), "the chosen folder is created on the next use");
	}

	@Test
	@DisplayName("resetting removes the setting and keeps the other keys in the file")
	void resetRemovesTheSettingAndKeepsOtherKeys() throws Exception {
		UserData userData = new UserData(tempDir.resolve("Gravaai"));
		Files.createDirectories(userData.dir());
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("recordingsDir", tempDir.resolve("Old").toString());
		settings.put("microphoneEnabled", true);
		Json.writeFile(userData.recordingsSettingsFile(), settings);
		RecordingsDirectory directory = new RecordingsDirectory(userData);

		Path custom = tempDir.resolve("New");
		directory.setCustomRecordingsDir(custom);
		assertEquals(custom, directory.getRecordingsDir());

		directory.setCustomRecordingsDir(null);

		assertEquals(userData.recordingsDir(), directory.getRecordingsDir());
		Map<String, JsonNode> stored = JsonFile.readObjectOrEmpty(userData.recordingsSettingsFile());
		assertEquals(true, stored.get("microphoneEnabled").asBoolean(), "sibling keys survive");
		assertTrue(!stored.containsKey("recordingsDir"), "the reset removes the key");
	}
}
