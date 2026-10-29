package dev.gravaai.core.settings;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import dev.gravaai.core.json.JsonFile;
import tools.jackson.databind.JsonNode;

/**
 * The untyped {@code app-settings.json} store (port of {@code electron/appSettingsStore.ts}, plan
 * §5.2).
 *
 * <p>Every value stays a {@link JsonNode} so keys this build does not know survive a read/write
 * cycle. Typed views are layered on top by the services; the store never drops fields.
 */
public final class AppSettingsStore {

	private final Path file;

	public AppSettingsStore(Path file) {
		this.file = file;
	}

	/** {@code readAppSettingsStore}: unknown keys are kept; invalid/missing/non-object reads empty. */
	public Map<String, JsonNode> read() {
		return JsonFile.readObjectOrEmpty(file);
	}

	/** {@code writeAppSettingsStore}: {@code JSON.stringify(store, null, 2)}, no trailing newline. */
	public void write(Map<String, JsonNode> store) throws IOException {
		JsonFile.writeObject(file, store);
	}

	/** {@code hasAppSetting}: an own property on the parsed object, even when its value is null. */
	public static boolean hasAppSetting(Map<String, JsonNode> store, String key) {
		return store.containsKey(key);
	}

	/**
	 * {@code readAppSetting}: the TS function returns {@code unknown | null}, so both a missing key
	 * and an explicit JSON {@code null} read as {@code null} here.
	 */
	public @Nullable JsonNode readSetting(String key) {
		JsonNode value = read().get(key);
		return value == null || value.isNull() ? null : value;
	}

	/** {@code writeAppSetting}: read-modify-write so every other key survives. */
	public void writeSetting(String key, JsonNode value) throws IOException {
		Map<String, JsonNode> store = read();
		store.put(key, value);
		write(store);
	}
}
