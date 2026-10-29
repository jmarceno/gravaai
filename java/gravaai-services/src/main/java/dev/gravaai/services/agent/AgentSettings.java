package dev.gravaai.services.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The editor-settings surface of the agent (port of
 * {@code src/components/video-editor/agent/settings.ts}, plan §5.16): dotted-path writes validated
 * through the project normaliser, plus the {@code captions_style} shorthand.
 */
public final class AgentSettings {

	private static final Set<String> ARRAY_SETTINGS = Set.of("zoomRegions", "trimRegions",
			"clipRegions", "speedRegions", "annotationRegions", "audioRegions", "autoCaptions",
			"sourceAudioTrackSettingsByClip", "defaultSourceAudioTrackSettings",
			"autoFullTrackClipId", "autoFullTrackClipEndMs", "visibleRanges", "sourcePath");

	private static final Pattern HEX_COLOUR = Pattern.compile("^#(?:[0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$",
			Pattern.CASE_INSENSITIVE);

	private AgentSettings() {}

	/** Applies a {@code set} patch; throws {@link AgentOpReader.OpError} for invalid input. */
	public static ProjectEditorState applySettingPatch(ProjectEditorState state,
			Map<String, JsonNode> values) {
		if (values == null || values.isEmpty()) {
			throw new AgentOpReader.OpError("set needs at least one key");
		}
		Set<String> roots = new LinkedHashSet<>();
		for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
			String path = entry.getKey();
			List<String> segments = splitPath(path);
			String root = segments.get(0);
			if (!settableKeys().contains(root)) {
				throw new AgentOpReader.OpError("unknown setting \"" + path
						+ "\"; call inspect settings view:\"settings\" for the key list");
			}
			String nested = segments.size() > 1 ? segments.get(1) : root;
			if (ARRAY_SETTINGS.contains(nested)) {
				throw new AgentOpReader.OpError("\"" + path
						+ "\" is edited with its own op (clips, zooms, captions), not with set");
			}
			if (entry.getValue() == null || entry.getValue().isNull()) {
				throw new AgentOpReader.OpError("\"" + path + "\" needs a value");
			}
			if ("wallpaper".equals(root)) {
				assertWallpaper(entry.getValue());
			}
			String blocked = findBlockedChange(entry.getValue(),
					readStoredPath(AgentStatePatch.toJson(state), segments));
			if (blocked != null) {
				throw new AgentOpReader.OpError("\"" + path + "." + blocked
						+ "\" cannot be changed with set");
			}
			roots.add(root);
		}

		JsonNode patched = AgentStatePatch.toJson(state);
		for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
			List<String> segments = splitPath(entry.getKey());
			writePath(patched, segments, entry.getValue());
		}
		ProjectEditorState normalized = AgentStatePatch.normalize(patched);

		JsonNode normalizedJson = AgentStatePatch.toJson(normalized);
		for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
			List<String> segments = splitPath(entry.getKey());
			JsonNode actual = readStoredPath(normalizedJson, segments);
			JsonNode expected = entry.getValue();
			if (!deepEqual(actual, expected)) {
				throw new AgentOpReader.OpError("invalid value for \"" + entry.getKey() + "\": "
						+ compact(expected) + " becomes " + compact(actual)
						+ " after validation (check the range or type via inspect settings)");
			}
		}

		ProjectEditorState.Builder next = state.toBuilder();
		for (String root : roots) {
			next.copy(root, normalized);
		}
		return next.build();
	}

	/** {@code applyCaptionStylePatch}. */
	public static ProjectEditorState applyCaptionStylePatch(ProjectEditorState state,
			Map<String, JsonNode> values) {
		Map<String, JsonNode> renamed = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
			String key = entry.getKey();
			if (key.equals("op") || key.equals("values") || key.equals("settings")) {
				continue;
			}
			renamed.put("autoCaptionSettings." + key, entry.getValue());
		}
		if (renamed.isEmpty()) {
			throw new AgentOpReader.OpError("captions_style needs at least one caption setting");
		}
		return applySettingPatch(state, renamed);
	}

	private static Set<String> settableKeys() {
		return new LinkedHashSet<>(AgentStatePatch.componentNames());
	}

	private static void assertWallpaper(JsonNode value) {
		if (!value.isString()) {
			throw new AgentOpReader.OpError("\"wallpaper\" must be a string");
		}
		String text = value.asString();
		if (text.contains("wallpapers/") || HEX_COLOUR.matcher(text).matches()
				|| text.startsWith("linear-gradient(") || text.startsWith("radial-gradient(")) {
			return;
		}
		throw new AgentOpReader.OpError("unknown wallpaper \"" + text
				+ "\"; use a built-in one, a #RRGGBB colour or a linear-gradient(...)");
	}

	private static List<String> splitPath(String path) {
		List<String> segments = new ArrayList<>();
		for (String segment : path.split("\\.")) {
			if (!segment.isBlank()) {
				segments.add(segment);
			}
		}
		if (segments.isEmpty()) {
			throw new AgentOpReader.OpError("set needs a dotted key, e.g. wallpaper or padding.top");
		}
		return segments;
	}

	private static JsonNode readStoredPath(JsonNode value, List<String> segments) {
		JsonNode cursor = value;
		for (String segment : segments) {
			if (cursor == null || !cursor.isObject() || !cursor.has(segment)) {
				return null;
			}
			cursor = cursor.get(segment);
		}
		return cursor;
	}

	private static void writePath(JsonNode target, List<String> segments, JsonNode value) {
		if (segments.isEmpty() || !target.isObject()) {
			return;
		}
		ObjectNode object = (ObjectNode) target;
		String head = segments.get(0);
		if (segments.size() == 1) {
			object.set(head, value);
			return;
		}
		JsonNode child = object.get(head);
		if (child == null || !child.isObject()) {
			child = Json.nodeFactory().objectNode();
			object.set(head, child);
		}
		writePath(child, segments.subList(1, segments.size()), value);
	}

	private static String findBlockedChange(JsonNode value, JsonNode stored) {
		if (value == null || !value.isObject()) {
			return null;
		}
		JsonNode storedObject = stored != null && stored.isObject() ? stored : Json.nodeFactory().objectNode();
		for (Map.Entry<String, JsonNode> entry : value.properties()) {
			if (ARRAY_SETTINGS.contains(entry.getKey())) {
				if (!deepEqual(entry.getValue(), storedObject.get(entry.getKey()))) {
					return entry.getKey();
				}
				continue;
			}
			String nested = findBlockedChange(entry.getValue(), storedObject.get(entry.getKey()));
			if (nested != null) {
				return entry.getKey() + "." + nested;
			}
		}
		return null;
	}

	private static boolean deepEqual(JsonNode left, JsonNode right) {
		if (left == null || left.isNull() || left.isMissingNode()) {
			return right == null || right.isNull() || right.isMissingNode();
		}
		if (right == null || right.isNull() || right.isMissingNode()) {
			return false;
		}
		if (left.isNumber() && right.isNumber()) {
			return Double.compare(left.asDouble(), right.asDouble()) == 0;
		}
		if (left.isArray() && right.isArray()) {
			if (left.size() != right.size()) {
				return false;
			}
			for (int index = 0; index < left.size(); index++) {
				if (!deepEqual(left.get(index), right.get(index))) {
					return false;
				}
			}
			return true;
		}
		if (left.isObject() && right.isObject()) {
			if (left.size() != right.size()) {
				return false;
			}
			for (Map.Entry<String, JsonNode> entry : left.properties()) {
				if (!right.has(entry.getKey()) || !deepEqual(entry.getValue(), right.get(entry.getKey()))) {
					return false;
				}
			}
			return true;
		}
		return left.equals(right);
	}

	private static String compact(JsonNode node) {
		return node == null ? "undefined" : Json.MAPPER.writeValueAsString(node);
	}
}
