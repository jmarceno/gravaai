package dev.gravaai.core.project;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Remaps project media paths that point into a renamed profile folder (port of
 * {@code electron/ipc/project/legacyProfilePaths.ts}, plan §5.2).
 *
 * <p>Projects store absolute media paths, so after the profile folder moves ("Grava AI" ->
 * "Gravaai") they point into a folder that no longer exists. The returned tree has the same media
 * files re-pointed at the current profile when the legacy path is missing and the current one
 * exists; the editor writes them back on the next save. The input tree is never modified, and it is
 * returned unchanged (same instance) when nothing needs remapping.
 */
public final class LegacyProfilePaths {

	/** A profile folder rename: {@code { legacyDir, currentDir }} in the TS source. */
	public record Rename(Path legacyDir, Path currentDir) {}

	private LegacyProfilePaths() {}

	/** Both historical profile renames, relative to an app-data directory. */
	public static List<Rename> profileDirRenames(Path appDataPath) {
		return List.of(
				new Rename(appDataPath.resolve("Grava AI"), appDataPath.resolve("Gravaai")),
				new Rename(appDataPath.resolve("GravaAI-dev"), appDataPath.resolve("Gravaai-dev")));
	}

	/** {@code remapLegacyProfilePaths(project, renames)} with {@link Files#exists} as the probe. */
	public static JsonNode remapLegacyProfilePaths(JsonNode project, List<Rename> renames) {
		return remapLegacyProfilePaths(project, renames, Files::exists);
	}

	/**
	 * @param project a parsed project file (any JSON value; non-objects are returned as-is)
	 * @param renames the profile renames to apply, in order
	 * @param exists existence probe for candidate paths (the TS test injects a fake filesystem)
	 */
	public static JsonNode remapLegacyProfilePaths(
			JsonNode project, List<Rename> renames, Predicate<Path> exists) {
		if (project == null || !project.isObject() || renames.isEmpty()) {
			return project;
		}

		ObjectNode source = (ObjectNode) project;
		String videoPath = remapPath(source.get("videoPath"), renames, exists);

		JsonNode editorNode = source.get("editor");
		boolean editorChanged = false;
		String wallpaper = null;
		String webcamSourcePath = null;
		Map<Integer, String> audioRegionPaths = new LinkedHashMap<>();
		Map<Integer, String> audioTrackPaths = new LinkedHashMap<>();

		if (editorNode != null && editorNode.isObject()) {
			ObjectNode editor = (ObjectNode) editorNode;
			wallpaper = remapPath(editor.get("wallpaper"), renames, exists);

			JsonNode webcam = editor.get("webcam");
			if (webcam != null && webcam.isObject()) {
				webcamSourcePath = remapPath(webcam.get("sourcePath"), renames, exists);
			}

			JsonNode regions = editor.get("audioRegions");
			if (regions != null && regions.isArray()) {
				for (int index = 0; index < regions.size(); index++) {
					JsonNode item = regions.get(index);
					if (item != null && item.isObject()) {
						String remapped = remapPath(item.get("audioPath"), renames, exists);
						if (remapped != null) {
							audioRegionPaths.put(index, remapped);
						}
					}
				}
			}

			JsonNode tracks = editor.get("audioTracks");
			if (tracks != null && tracks.isArray()) {
				for (int index = 0; index < tracks.size(); index++) {
					JsonNode item = tracks.get(index);
					if (item != null && item.isObject()) {
						String remapped = remapPath(item.get("sourcePath"), renames, exists);
						if (remapped != null) {
							audioTrackPaths.put(index, remapped);
						}
					}
				}
			}

			editorChanged = wallpaper != null
					|| webcamSourcePath != null
					|| !audioRegionPaths.isEmpty()
					|| !audioTrackPaths.isEmpty();
		}

		if (videoPath == null && !editorChanged) {
			return project;
		}

		ObjectNode result = source.deepCopy();
		if (videoPath != null) {
			result.put("videoPath", videoPath);
		}
		if (editorChanged) {
			ObjectNode editor = (ObjectNode) result.get("editor");
			if (wallpaper != null) {
				editor.put("wallpaper", wallpaper);
			}
			if (webcamSourcePath != null) {
				((ObjectNode) editor.get("webcam")).put("sourcePath", webcamSourcePath);
			}
			if (!audioRegionPaths.isEmpty()) {
				tools.jackson.databind.node.ArrayNode regions =
						(tools.jackson.databind.node.ArrayNode) editor.get("audioRegions");
				audioRegionPaths.forEach(
						(index, remapped) -> ((ObjectNode) regions.get(index)).put("audioPath", remapped));
			}
			if (!audioTrackPaths.isEmpty()) {
				tools.jackson.databind.node.ArrayNode tracks =
						(tools.jackson.databind.node.ArrayNode) editor.get("audioTracks");
				audioTrackPaths.forEach(
						(index, remapped) -> ((ObjectNode) tracks.get(index)).put("sourcePath", remapped));
			}
		}
		return result;
	}

	private static String remapPath(JsonNode node, List<Rename> renames, Predicate<Path> exists) {
		if (node == null || !node.isString()) {
			return null;
		}

		Path resolved;
		try {
			Path filePath = Path.of(node.asString());
			if (!filePath.isAbsolute()) {
				return null;
			}
			resolved = filePath.toAbsolutePath().normalize();
		} catch (InvalidPathException error) {
			return null;
		}

		for (Rename rename : renames) {
			Path legacyDir = rename.legacyDir().toAbsolutePath().normalize();
			Path relativePath;
			try {
				relativePath = legacyDir.relativize(resolved);
			} catch (IllegalArgumentException error) {
				// path.relative returns an absolute path for different roots; the TS code skips those.
				continue;
			}
			String relative = relativePath.toString();
			if (relative.isEmpty() || relative.startsWith("..") || relativePath.isAbsolute()) {
				continue;
			}
			if (exists.test(resolved)) {
				return null;
			}

			Path candidate = rename.currentDir().resolve(relativePath).normalize();
			return exists.test(candidate) ? candidate.toString() : null;
		}
		return null;
	}
}
