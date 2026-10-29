package dev.gravaai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Tests for {@link AppSettingsStore}, ported from the behaviour of
 * {@code electron/appSettingsStore.ts} (the TS file has no separate vitest file).
 */
class AppSettingsStoreTest {

	@TempDir
	Path directory;

	private Path file;
	private AppSettingsStore store;

	@BeforeEach
	void setUp() {
		file = directory.resolve("app-settings.json");
		store = new AppSettingsStore(file);
	}

	@Test
	@DisplayName("reads a missing store as empty")
	void readsMissingStoreAsEmpty() {
		assertTrue(store.read().isEmpty());
		assertFalse(AppSettingsStore.hasAppSetting(store.read(), "gravaai.theme"));
		assertNull(store.readSetting("gravaai.theme"));
	}

	@Test
	@DisplayName("reads an invalid or non-object store as empty")
	void readsInvalidStoreAsEmpty() throws IOException {
		for (String contents : List.of("", "{ nope", "[1,2]", "\"text\"", "null")) {
			Files.writeString(file, contents, StandardCharsets.UTF_8);
			assertTrue(store.read().isEmpty(), contents);
		}
	}

	@Test
	@DisplayName("reads a store with a UTF-8 byte order mark")
	void readsStoreWithByteOrderMark() throws IOException {
		try (InputStream input = getClass().getResourceAsStream("/fixtures/json/app-settings-bom.json")) {
			assertNotNull(input);
			Files.writeString(file, new String(input.readAllBytes(), StandardCharsets.UTF_8),
					StandardCharsets.UTF_8);
		}

		Map<String, JsonNode> storeContents = store.read();
		assertEquals(List.of("gravaai.editor.preferences", "gravaai.theme",
				"gravaai.recording.settings", "aiControlEnabled"), List.copyOf(storeContents.keySet()));
		assertEquals("system", store.readSetting("gravaai.theme").asString());
	}

	@Test
	@DisplayName("treats an explicit null value as present but reads it as null")
	void explicitNullIsPresent() throws IOException {
		Files.writeString(file, "{\"flag\":true,\"nil\":null}", StandardCharsets.UTF_8);

		Map<String, JsonNode> storeContents = store.read();
		assertTrue(AppSettingsStore.hasAppSetting(storeContents, "nil"));
		assertTrue(AppSettingsStore.hasAppSetting(storeContents, "flag"));
		assertNull(store.readSetting("nil"));
		assertTrue(store.readSetting("flag").booleanValue());
	}

	@Test
	@DisplayName("writes one setting without dropping or reordering the other keys")
	void writesOneSetting() throws IOException {
		String fixture = fixture("app-settings.json");
		Files.writeString(file, fixture, StandardCharsets.UTF_8);
		Map<String, JsonNode> before = store.read();

		store.writeSetting("gravaai.theme", Json.nodeFactory().stringNode("dark"));

		Map<String, JsonNode> expected = new java.util.LinkedHashMap<>(before);
		expected.put("gravaai.theme", Json.nodeFactory().stringNode("dark"));
		Map<String, JsonNode> after = store.read();
		assertEquals(expected, after);
		assertEquals(Json.stringify(expected), Files.readString(file, StandardCharsets.UTF_8));
		assertTrue(Files.readString(file, StandardCharsets.UTF_8)
				.contains("\"gravaai.theme\": \"dark\""));
		assertFalse(Files.readString(file, StandardCharsets.UTF_8).endsWith("\n"));
	}

	@Test
	@DisplayName("round-trips an unknown nested value unchanged")
	void roundTripsUnknownValues() throws IOException {
		String fixture = fixture("app-settings.json");
		Files.writeString(file, fixture, StandardCharsets.UTF_8);

		store.write(store.read());

		assertEquals(fixture, Files.readString(file, StandardCharsets.UTF_8));
	}

	private String fixture(String name) throws IOException {
		try (InputStream input = getClass().getResourceAsStream("/fixtures/json/" + name)) {
			assertNotNull(input, "missing fixture " + name);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
