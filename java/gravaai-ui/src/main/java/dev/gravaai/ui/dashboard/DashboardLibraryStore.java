package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.json.JsonFile;
import dev.gravaai.core.storage.UserData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores the dashboard preferences that the React app keeps in {@code localStorage}
 * ({@code useProjectFolders}, {@code useRawLibrary}, {@code useDashboardMetadata}).
 *
 * <p>Java cannot read Chromium's localStorage without a LevelDB parser and no library may be added,
 * so the values move to one file, {@code <userData>/dashboard-library.json}, using the original
 * localStorage keys as top-level keys (Lead decision, task-9). Consequence, recorded in the phase
 * report: folders, colours and raw-file renames are <b>not</b> migrated from an Electron profile;
 * they start empty once. Unknown top-level keys survive a round trip (plan §5.2).
 *
 * <p>Writes are atomic (temp file in the same directory + rename). The file is read once at
 * construction like a localStorage snapshot; call {@link #load()} to reread.
 */
public final class DashboardLibraryStore {

	/** {@code localStorage} key names from the React dashboard; one key per JSON value. */
	public static final String FOLDERS_KEY = "gravaai.project-folders.v1";
	public static final String RAW_HIDDEN_KEY = "gravaai.raw-hidden.v1";
	public static final String RAW_NAMES_KEY = "gravaai.raw-names.v1";
	public static final String METADATA_KEY = "gravaai.dashboard-metadata.v1";

	/** Port of the colour validation: {@code /^#[0-9a-f]{6}$/i}. */
	private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-f]{6}$", Pattern.CASE_INSENSITIVE);

	private final Path file;
	private Map<String, JsonNode> unknownKeys = new LinkedHashMap<>();

	public DashboardLibraryStore(UserData userData) {
		this(userData.dir().resolve("dashboard-library.json"));
	}

	public DashboardLibraryStore(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	/** Reads the store; a missing or invalid file reads as the empty preferences. */
	public DashboardLibraryData load() {
		Map<String, JsonNode> store = JsonFile.readObjectOrEmpty(file);
		unknownKeys = new LinkedHashMap<>(store);
		unknownKeys.keySet().removeAll(List.of(FOLDERS_KEY, RAW_HIDDEN_KEY, RAW_NAMES_KEY, METADATA_KEY));

		return new DashboardLibraryData(
				readFolders(store.get(FOLDERS_KEY)),
				readStringArray(store.get(RAW_HIDDEN_KEY)),
				readStringMap(store.get(RAW_NAMES_KEY)),
				readColors(store.get(METADATA_KEY)));
	}

	/** Writes the preferences atomically, keeping unknown top-level keys untouched. */
	public void save(DashboardLibraryData data) throws IOException {
		Map<String, JsonNode> store = new LinkedHashMap<>();
		store.putAll(unknownKeys);

		var nodeFactory = Json.nodeFactory();
		ArrayNode folders = nodeFactory.arrayNode();
		for (ProjectFolder folder : data.folders()) {
			ObjectNode item = nodeFactory.objectNode();
			item.put("id", folder.id());
			item.put("name", folder.name());
			item.put("color", folder.color());
			ArrayNode paths = nodeFactory.arrayNode();
			folder.paths().forEach(paths::add);
			item.set("paths", paths);
			folders.add(item);
		}
		store.put(FOLDERS_KEY, folders);

		ArrayNode hidden = nodeFactory.arrayNode();
		data.hiddenRawPaths().forEach(hidden::add);
		store.put(RAW_HIDDEN_KEY, hidden);

		ObjectNode names = nodeFactory.objectNode();
		data.rawNames().forEach(names::put);
		store.put(RAW_NAMES_KEY, names);

		ObjectNode metadata = nodeFactory.objectNode();
		ArrayNode colors = nodeFactory.arrayNode();
		data.colors().forEach(colors::add);
		metadata.set("colors", colors);
		store.put(METADATA_KEY, metadata);

		writeAtomically(store);
	}

	/** Port of {@code moveProjectFolderReferences}: replaces a project path in every folder. */
	public static DashboardLibraryData moveProjectFolderReferences(DashboardLibraryData data,
			String previous, String next) {
		List<ProjectFolder> folders = new ArrayList<>();
		for (ProjectFolder folder : data.folders()) {
			List<String> paths = new ArrayList<>();
			for (String path : folder.paths()) {
				paths.add(path.equals(previous) ? next : path);
			}
			folders.add(new ProjectFolder(folder.id(), folder.name(), folder.color(), paths));
		}
		return new DashboardLibraryData(folders, data.hiddenRawPaths(), data.rawNames(), data.colors());
	}

	private static List<ProjectFolder> readFolders(@Nullable JsonNode value) {
		List<ProjectFolder> folders = new ArrayList<>();
		if (value == null || !value.isArray()) {
			return folders;
		}
		for (JsonNode item : value) {
			if (item == null || !item.isObject()) {
				continue;
			}
			JsonNode id = item.get("id");
			JsonNode name = item.get("name");
			JsonNode color = item.get("color");
			JsonNode paths = item.get("paths");
			if (id == null || !id.isString() || name == null || !name.isString()
					|| color == null || !color.isString() || !HEX_COLOR.matcher(color.asString()).matches()
					|| paths == null || !paths.isArray()) {
				continue;
			}
			List<String> folderPaths = new ArrayList<>();
			boolean valid = true;
			for (JsonNode path : paths) {
				if (path == null || !path.isString()) {
					valid = false;
					break;
				}
				folderPaths.add(path.asString());
			}
			if (valid) {
				folders.add(new ProjectFolder(id.asString(), name.asString(), color.asString(), folderPaths));
			}
		}
		return folders;
	}

	private static List<String> readStringArray(@Nullable JsonNode value) {
		List<String> values = new ArrayList<>();
		if (value == null || !value.isArray()) {
			return values;
		}
		for (JsonNode item : value) {
			if (item != null && item.isString()) {
				values.add(item.asString());
			}
		}
		return values;
	}

	private static Map<String, String> readStringMap(@Nullable JsonNode value) {
		Map<String, String> values = new LinkedHashMap<>();
		if (value == null || !value.isObject()) {
			return values;
		}
		value.properties().forEach(entry -> {
			if (entry.getValue() != null && entry.getValue().isString()) {
				values.put(entry.getKey(), entry.getValue().asString());
			}
		});
		return values;
	}

	private static List<String> readColors(@Nullable JsonNode metadata) {
		if (metadata == null || !metadata.isObject()) {
			return List.of();
		}
		List<String> colors = new ArrayList<>();
		for (String color : readStringArray(metadata.get("colors"))) {
			if (HEX_COLOR.matcher(color).matches() && !colors.contains(color)) {
				colors.add(color);
			}
		}
		return colors;
	}

	private void writeAtomically(Map<String, JsonNode> store) throws IOException {
		Path parent = java.util.Objects.requireNonNull(
				file.toAbsolutePath().normalize().getParent(),
				"library file has no parent directory: " + file);
		Files.createDirectories(parent);
		Path temporary = parent.resolve("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
		try {
			Files.writeString(temporary, Json.stringify(store), StandardCharsets.UTF_8);
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}
}
