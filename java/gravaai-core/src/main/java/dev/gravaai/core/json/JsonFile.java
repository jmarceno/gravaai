package dev.gravaai.core.json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Settings-file read/write rules (plan §5.2).
 *
 * <p>Ported from {@code electron/appSettingsStore.ts} and
 * {@code electron/ipc/settings/recordingPreferencesStore.ts}: a missing, invalid or non-object file
 * reads as an empty store instead of an error, and stores are untyped maps so unknown fields survive
 * a round trip. Typed views sit on top of the map; the map itself is never filtered.
 */
public final class JsonFile {

	private JsonFile() {}

	/**
	 * Reads a JSON object store, or an empty store when the file is missing, unparsable, or holds
	 * something that is not a JSON object (array, string, {@code null}). The insertion order of the
	 * file is preserved so a rewrite keeps the original key order.
	 */
	public static Map<String, JsonNode> readObjectOrEmpty(Path file) {
		try {
			JsonNode parsed = Json.parseJsonWithByteOrderMark(Files.readString(file, StandardCharsets.UTF_8));
			if (!parsed.isObject()) {
				return new LinkedHashMap<>();
			}

			Map<String, JsonNode> store = new LinkedHashMap<>();
			parsed.properties().forEach(entry -> store.put(entry.getKey(), entry.getValue()));
			return store;
		} catch (IOException | RuntimeException error) {
			// Matches the TS stores: a read or parse failure means "use the defaults".
			return new LinkedHashMap<>();
		}
	}

	/** Writes a store as {@code JSON.stringify(store, null, 2)} (no trailing newline). */
	public static void writeObject(Path file, Map<String, JsonNode> store) throws IOException {
		Json.writeFile(file, store);
	}
}
