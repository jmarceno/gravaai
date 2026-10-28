package dev.gravaai.services.automations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Port of {@code electron/automations/store.test.ts}.
 */
class AutomationStoreTest {

	@TempDir
	Path tempDir;

	private Path userData;
	private Path filePath;
	private AutomationStore store;

	@BeforeEach
	void setUp() throws IOException {
		userData = Files.createDirectory(tempDir.resolve("userData"));
		filePath = userData.resolve("automations.json");
		store = new AutomationStore(filePath);
	}

	private static JsonNode json(String text) {
		return Json.MAPPER.readTree(text);
	}

	@Test
	@DisplayName("readAutomationsFile returns an empty file when nothing was saved yet")
	void emptyWhenMissing() {
		assertEquals(new AutomationsFile(1, List.of()), store.read());
	}

	@Test
	@DisplayName("readAutomationsFile returns an empty file for corrupt json")
	void emptyForCorruptJson() throws IOException {
		Files.writeString(filePath, "{not json");
		List<String> warnings = new ArrayList<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				warnings.add(record.getMessage());
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		};
		java.util.logging.Logger logger = java.util.logging.Logger.getLogger("gravaai.automations");
		logger.addHandler(handler);
		logger.setLevel(Level.WARNING);
		try {
			assertEquals(new AutomationsFile(1, List.of()), store.read());
		} finally {
			logger.removeHandler(handler);
		}
		assertTrue(!warnings.isEmpty(), "a corrupt file must be logged");
	}

	@Test
	@DisplayName("readAutomationsFile normalises rules read from disk")
	void normalisesFromDisk() throws IOException {
		Files.writeString(filePath, """
				{
				  "version": 7,
				  "rules": [
				    {"id": "a", "name": "Keep", "enabled": true, "trigger": "export-finished",
				     "actions": [{"type": "reveal"}]},
				    {"id": "b", "name": "Drop", "trigger": "unknown", "actions": []}
				  ]
				}
				""");
		AutomationsFile file = store.read();
		assertEquals(1, file.version());
		assertEquals(1, file.rules().size());
		assertEquals("a", file.rules().get(0).id());
	}

	@Test
	@DisplayName("writeAutomationsFile normalises, writes and returns what was persisted")
	void writesNormalised() throws IOException {
		AutomationsFile written = store.write(json("""
				{
				  "version": 1,
				  "rules": [
				    {"id": "a", "name": "Copy", "enabled": true, "trigger": "recording-saved",
				     "conditions": [], "actions": [{"type": "copy-path"}]}
				  ]
				}
				"""));
		assertEquals("Copy", written.rules().get(0).name());
		String content = Files.readString(filePath);
		assertTrue(content.endsWith("\n"), "the store writes JSON.stringify(x, null, 2) plus a newline");
		JsonNode raw = json(content);
		assertEquals(1, raw.get("rules").size());
		assertEquals(json("[{\"type\":\"copy-path\"}]"), raw.get("rules").get(0).get("actions"));
		assertEquals(List.of(), temporaryFiles());
		store.close();
	}

	@Test
	@DisplayName("writeAutomationsFile serialises concurrent writes without leaving temp files")
	void serialisesWrites() throws Exception {
		CompletableFuture<AutomationsFile> first = store.writeAsync(json(
				"{\"rules\":[{\"id\":\"a\",\"name\":\"A\",\"trigger\":\"recording-saved\",\"actions\":[]}]}"));
		CompletableFuture<AutomationsFile> second = store.writeAsync(json(
				"{\"rules\":[{\"id\":\"b\",\"name\":\"B\",\"trigger\":\"meeting-saved\",\"actions\":[]}]}"));
		CompletableFuture<AutomationsFile> third = store.writeAsync(json(
				"{\"rules\":[{\"id\":\"c\",\"name\":\"C\",\"trigger\":\"export-finished\",\"actions\":[]}]}"));
		CompletableFuture.allOf(first, second, third).join();
		JsonNode raw = json(Files.readString(filePath));
		assertEquals("c", raw.get("rules").get(0).get("id").stringValue());
		assertEquals(List.of(), temporaryFiles());
		store.close();
	}

	private List<String> temporaryFiles() throws IOException {
		try (var entries = Files.list(userData)) {
			return entries.filter(path -> path.getFileName().toString().endsWith(".tmp"))
					.map(path -> path.getFileName().toString())
					.toList();
		}
	}
}
