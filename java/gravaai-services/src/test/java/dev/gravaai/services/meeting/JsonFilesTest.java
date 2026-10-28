package dev.gravaai.services.meeting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan 5.2: JSON writes must match Electron's JSON.stringify(value, null, 2) byte for byte. */
class JsonFilesTest {

	@TempDir
	Path temp;

	@Test
	@DisplayName("pretty-prints exactly like JSON.stringify(value, null, 2)")
	void prettyMatchesJsonStringify() {
		Map<String, Object> object = new LinkedHashMap<>();
		object.put("a", 1);
		object.put("b", List.of(1, 2));
		Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("d", "x");
		object.put("c", nested);
		String expected = String.join("\n",
				"{",
				"  \"a\": 1,",
				"  \"b\": [",
				"    1,",
				"    2",
				"  ],",
				"  \"c\": {",
				"    \"d\": \"x\"",
				"  }",
				"}");
		assertEquals(expected, JsonFiles.pretty(object));
	}

	@Test
	@DisplayName("writes atomically and reads the same object back with key order")
	void writesAtomicallyAndReadsBack() {
		Path file = temp.resolve("a.meeting.json");
		Map<String, Object> object = new LinkedHashMap<>();
		object.put("kind", "meeting");
		object.put("hasVideo", true);
		JsonFiles.writeJsonAtomic(file, object);
		assertEquals(object, JsonFiles.readObjectOrEmpty(file));
		assertTrue(Files.notExists(Path.of(file + ".tmp")));
	}

	@Test
	@DisplayName("tolerates a UTF-8 byte order mark and broken files")
	void toleratesBomAndBrokenFiles() throws java.io.IOException {
		Path bom = temp.resolve("bom.json");
		Files.writeString(bom, "\uFEFF{\"a\":1}", StandardCharsets.UTF_8);
		assertEquals(Map.of("a", 1), JsonFiles.readObjectOrEmpty(bom));
		Path broken = temp.resolve("broken.json");
		Files.writeString(broken, "{nope", StandardCharsets.UTF_8);
		assertEquals(Map.of(), JsonFiles.readObjectOrEmpty(broken));
		assertNull(JsonFiles.readTreeOrNull(temp.resolve("missing.json")));
	}
}
