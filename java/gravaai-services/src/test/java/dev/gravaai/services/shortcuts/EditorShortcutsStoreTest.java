package dev.gravaai.services.shortcuts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.result.Result;
import dev.gravaai.core.result.Result.Unit;
import tools.jackson.databind.JsonNode;

/**
 * Covers the editor-shortcuts half of {@code electron/ipc/register/settings.ts} (the TS file has
 * no unit test for these handlers).
 */
class EditorShortcutsStoreTest {

	@TempDir
	Path tempDir;

	private EditorShortcutsStore store;

	@BeforeEach
	void setUp() {
		store = new EditorShortcutsStore(tempDir.resolve("shortcuts.json"));
	}

	private static JsonNode json(String text) {
		return Json.MAPPER.readTree(text);
	}

	@Test
	@DisplayName("read returns null when nothing was saved yet")
	void nullWhenMissing() {
		assertNull(store.read());
	}

	@Test
	@DisplayName("read returns null for corrupt json")
	void nullForCorruptJson() throws IOException {
		Files.writeString(store.filePath(), "{not json");
		assertNull(store.read());
	}

	@Test
	@DisplayName("read tolerates a UTF-8 byte order mark")
	void toleratesBom() throws IOException {
		Files.writeString(store.filePath(), "\uFEFF{\"a\": 1}");
		assertEquals(json("{\"a\": 1}"), store.read());
	}

	@Test
	@DisplayName("write stores JSON.stringify(value, null, 2) without a trailing newline and read returns it")
	void writesAndReadsBack() throws IOException {
		JsonNode value = json("{\"playPause\": {\"key\": \"space\", \"ctrl\": false}, \"undo\": null}");
		Result<Unit> result = store.write(value);
		assertTrue(result.isOk(), result.errorMessage());
		String raw = Files.readString(store.filePath());
		assertEquals(Json.stringify(value), raw);
		assertEquals(value, store.read());
	}

	@Test
	@DisplayName("write reports an error instead of throwing")
	void writeReportsError() throws IOException {
		Path blocker = tempDir.resolve("blocker");
		Files.writeString(blocker, "");
		EditorShortcutsStore broken = new EditorShortcutsStore(blocker.resolve("shortcuts.json"));
		Result<Unit> result = broken.write(json("{}"));
		assertTrue(result.isErr());
		assertTrue(!result.errorMessage().isEmpty());
	}
}
