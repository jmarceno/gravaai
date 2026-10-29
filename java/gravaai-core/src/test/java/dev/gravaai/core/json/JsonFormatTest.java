package dev.gravaai.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Byte-compatibility tests for {@link Json} and {@link JsonFile} (plan §5.2, §6.1).
 *
 * <p>Every expected string in {@code src/test/resources/fixtures/json} was generated with Node's
 * {@code JSON.stringify(value, null, 2)}, i.e. by the implementation the Electron app uses, so
 * these are format fixtures rather than hand-written expectations.
 */
class JsonFormatTest {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final List<String> STRINGIFY_FIXTURES = List.of(
			"app-settings.json",
			"recordings-settings.json",
			"recent-projects.json",
			"shape-edges.json");

	@Test
	@DisplayName("pretty-prints settings files exactly like JSON.stringify(value, null, 2)")
	void prettyPrintsSettingsFilesLikeJsonStringify() throws IOException {
		for (String name : STRINGIFY_FIXTURES) {
			String expected = fixture(name);
			JsonNode parsed = Json.parseJsonWithByteOrderMark(expected);
			assertEquals(expected, Json.stringify(parsed), name);
		}
	}

	@Test
	@DisplayName("pretty-prints short documents exactly like JSON.stringify(value, null, 2)")
	void prettyPrintsShortDocumentsLikeJsonStringify() throws IOException {
		JsonNode cases = Json.parseJsonWithByteOrderMark(fixture("json-stringify-cases.json"));
		for (Map.Entry<String, JsonNode> entry : cases.properties()) {
			String expected = entry.getValue().asString();
			JsonNode parsed = Json.parseJsonWithByteOrderMark(expected);
			assertEquals(expected, Json.stringify(parsed), entry.getKey());
		}
		assertEquals("{}", Json.stringify(NODES.objectNode()));
		assertEquals("[]", Json.stringify(NODES.arrayNode()));
		assertEquals("\"plain\"", Json.stringify(NODES.stringNode("plain")));
	}

	@Test
	@DisplayName("tolerates a UTF-8 byte order mark")
	void toleratesByteOrderMark() throws IOException {
		String plain = fixture("app-settings.json");
		String withBom = fixture("app-settings-bom.json");
		assertTrue(withBom.startsWith("\uFEFF"), "fixture must carry the BOM");
		assertEquals(plain, Json.stripJsonByteOrderMark(withBom));
		assertEquals(plain, Json.stripJsonByteOrderMark(plain));
		assertEquals(plain, Json.stringify(Json.parseJsonWithByteOrderMark(withBom)));
	}

	@Test
	@DisplayName("rejects invalid JSON the way JSON.parse does")
	void rejectsInvalidJson() {
		assertThrows(RuntimeException.class, () -> Json.parseJsonWithByteOrderMark("{"));
		assertThrows(RuntimeException.class, () -> Json.parseJsonWithByteOrderMark(""));
		assertThrows(RuntimeException.class, () -> Json.parseJsonWithByteOrderMark("not json"));
	}

	@Test
	@DisplayName("round-trips a settings store without dropping unknown keys or reordering them")
	void roundTripsSettingsStore(@TempDir Path directory) throws IOException {
		String expected = fixture("app-settings.json");
		Path file = directory.resolve("app-settings.json");
		Files.writeString(file, expected, StandardCharsets.UTF_8);

		Map<String, JsonNode> store = JsonFile.readObjectOrEmpty(file);
		assertEquals(
				List.of("gravaai.editor.preferences", "gravaai.theme", "gravaai.recording.settings", "aiControlEnabled"),
				new ArrayList<>(store.keySet()));
		assertTrue(store.get("gravaai.editor.preferences").isObject());

		Path written = directory.resolve("written.json");
		JsonFile.writeObject(written, store);
		assertEquals(expected, Files.readString(written, StandardCharsets.UTF_8));
		assertFalse(Files.readString(written, StandardCharsets.UTF_8).endsWith("\n"));
	}

	@Test
	@DisplayName("reads a missing, invalid or non-object store as an empty store")
	void readsBadStoresAsEmpty(@TempDir Path directory) throws IOException {
		assertTrue(JsonFile.readObjectOrEmpty(directory.resolve("missing.json")).isEmpty());

		for (String contents : List.of("", "{ nope", "[1,2]", "\"text\"", "null", "[]")) {
			Path file = directory.resolve("bad.json");
			Files.writeString(file, contents, StandardCharsets.UTF_8);
			assertTrue(JsonFile.readObjectOrEmpty(file).isEmpty(), contents);
		}

		Path withBom = directory.resolve("bom.json");
		Files.writeString(withBom, "\uFEFF{\"kept\":true}", StandardCharsets.UTF_8);
		Map<String, JsonNode> store = JsonFile.readObjectOrEmpty(withBom);
		assertEquals(1, store.size());
		assertTrue(store.get("kept").booleanValue());
	}

	@Test
	@DisplayName("is safe to use from several threads at once")
	void isSafeToUseConcurrently() throws Exception {
		String expected = fixture("shape-edges.json");
		JsonNode tree = Json.parseJsonWithByteOrderMark(expected);
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			List<Future<String>> results = new ArrayList<>();
			for (int thread = 0; thread < 8; thread++) {
				results.add(executor.submit(() -> {
					String last = null;
					for (int iteration = 0; iteration < 200; iteration++) {
						last = Json.stringify(tree);
					}
					return last;
				}));
			}
			for (Future<String> result : results) {
				assertEquals(expected, result.get());
			}
		} finally {
			executor.shutdownNow();
		}
	}

	private static String fixture(String name) throws IOException {
		try (InputStream input = JsonFormatTest.class.getResourceAsStream("/fixtures/json/" + name)) {
			assertNotNull(input, "missing fixture " + name);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
