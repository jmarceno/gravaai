package dev.gravaai.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code recent-projects.json} history (port of {@code loadRecentProjectPaths},
 * {@code saveRecentProjectPaths} and {@code rememberRecentProject} in
 * {@code electron/ipc/project/manager.ts}, plan §5.2).
 *
 * <p>The file is a single object, {@code { "paths": [...] }}, capped at
 * {@link StorageFiles#MAX_RECENT_PROJECTS} entries. Paths are stored absolute and normalized
 * ({@code path.resolve}); invalid or missing files read as an empty history.
 */
public final class RecentProjectsStore {

	private final Path file;

	public RecentProjectsStore(Path file) {
		this.file = file;
	}

	/** {@code loadRecentProjectPaths}: non-blank strings only; any failure reads as empty. */
	public List<String> loadRecentProjectPaths() {
		try {
			JsonNode parsed = Json.parseJsonWithByteOrderMark(Files.readString(file, StandardCharsets.UTF_8));
			JsonNode paths = parsed.get("paths");
			if (paths == null || !paths.isArray()) {
				return new ArrayList<>();
			}

			List<String> result = new ArrayList<>();
			for (JsonNode value : paths) {
				if (value != null && value.isString() && !value.asString().isBlank()) {
					result.add(value.asString());
				}
			}
			return result;
		} catch (IOException | RuntimeException error) {
			return new ArrayList<>();
		}
	}

	/** {@code saveRecentProjectPaths}: normalize, drop duplicates keeping the first, cap at 16. */
	public void saveRecentProjectPaths(List<String> paths) throws IOException {
		Set<String> normalizedPaths = new LinkedHashSet<>();
		for (String path : paths) {
			normalizedPaths.add(normalizePath(path));
		}

		ArrayNode array = Json.nodeFactory().arrayNode();
		normalizedPaths.stream().limit(StorageFiles.MAX_RECENT_PROJECTS).forEach(array::add);
		ObjectNode document = Json.nodeFactory().objectNode();
		document.set("paths", array);
		Json.writeFile(file, document);
	}

	/** {@code rememberRecentProject}: prepend a project path; non-project files are ignored. */
	public void rememberRecentProject(String projectPath) throws IOException {
		if (!StorageFiles.hasProjectFileExtension(projectPath)) {
			return;
		}

		List<String> existingPaths = loadRecentProjectPaths();
		List<String> nextPaths = new ArrayList<>();
		nextPaths.add(projectPath);
		nextPaths.addAll(existingPaths);
		saveRecentProjectPaths(nextPaths);
	}

	/** Port of {@code normalizePath} in electron/ipc/utils.ts: {@code path.resolve}. */
	private static String normalizePath(String path) {
		try {
			return Path.of(path).toAbsolutePath().normalize().toString();
		} catch (InvalidPathException error) {
			return path;
		}
	}
}
