package dev.gravaai.services.recording;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.KeystrokeTypes.KeyModifier;
import dev.gravaai.services.recording.KeystrokeTypes.KeystrokeEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Key-press sidecar next to a recording, ported from
 * {@code electron/ipc/keyboard/keystrokeTelemetry.ts}.
 */
public final class KeystrokeTelemetry {

	// Kept here rather than in the shared constants: the TS comment explains it does not want the
	// app-path resolving constants module dragged into every cosmic.ts importer.
	public static final int KEYSTROKE_TELEMETRY_VERSION = 1;

	private KeystrokeTelemetry() {
	}

	private static boolean isKeystrokeEvent(JsonNode node) {
		if (node == null || !node.isObject()) {
			return false;
		}
		JsonNode key = node.get("key");
		JsonNode timeMs = node.get("timeMs");
		return key != null && key.isString() && !key.asString().isEmpty()
				&& timeMs != null && timeMs.isNumber() && Double.isFinite(timeMs.asDouble());
	}

	/** Accepts the sidecar shape and drops anything unusable. */
	public static List<KeystrokeEvent> normalizeKeystrokeEvents(JsonNode raw) {
		JsonNode eventsNode = null;
		if (raw != null && raw.isArray()) {
			eventsNode = raw;
		} else if (raw != null && raw.isObject() && raw.get("events") != null && raw.get("events").isArray()) {
			eventsNode = raw.get("events");
		}
		if (eventsNode == null) {
			return List.of();
		}
		List<KeystrokeEvent> events = new ArrayList<>();
		for (JsonNode node : eventsNode) {
			if (!isKeystrokeEvent(node)) {
				continue;
			}
			String key = node.get("key").asString();
			double timeMs = Math.max(0, node.get("timeMs").asDouble());
			List<KeyModifier> modifiers = new ArrayList<>();
			JsonNode rawModifiers = node.get("modifiers");
			if (rawModifiers != null && rawModifiers.isArray()) {
				for (KeyModifier modifier : KeystrokeTypes.KEYSTROKE_MODIFIER_ORDER) {
					for (JsonNode candidate : rawModifiers) {
						if (candidate.isString() && modifier.wire().equals(candidate.asString())) {
							modifiers.add(modifier);
							break;
						}
					}
				}
			}
			events.add(new KeystrokeEvent(Math.round(timeMs), key.substring(0, Math.min(32, key.length())), modifiers));
			if (events.size() >= KeystrokeCapture.MAX_KEYSTROKE_EVENTS) {
				break;
			}
		}
		events.sort(Comparator.comparingLong(KeystrokeEvent::timeMs));
		return events;
	}

	private static ObjectNode eventToJson(KeystrokeEvent event) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("timeMs", event.timeMs());
		node.put("key", event.key());
		ArrayNode modifiers = node.putArray("modifiers");
		for (KeyModifier modifier : event.modifiers()) {
			modifiers.add(modifier.wire());
		}
		return node;
	}

	/**
	 * Writes the key presses next to the video. An empty list removes the sidecar, so a recording
	 * without presses never leaves a file behind.
	 */
	public static List<KeystrokeEvent> writeKeystrokeTelemetry(String videoPath, List<KeystrokeEvent> events) throws IOException {
		ArrayNode input = Json.nodeFactory().arrayNode();
		for (KeystrokeEvent event : events) {
			input.add(eventToJson(event));
		}
		List<KeystrokeEvent> normalized = normalizeKeystrokeEvents(input);
		Path keystrokePath = Path.of(RecordingPaths.getKeystrokePathForVideo(videoPath));
		if (normalized.isEmpty()) {
			Files.deleteIfExists(keystrokePath);
			return normalized;
		}
		ObjectNode root = Json.nodeFactory().objectNode();
		root.put("version", KEYSTROKE_TELEMETRY_VERSION);
		ArrayNode normalizedEvents = root.putArray("events");
		for (KeystrokeEvent event : normalized) {
			normalizedEvents.add(eventToJson(event));
		}
		Json.writeFile(keystrokePath, root);
		return normalized;
	}

	public static List<KeystrokeEvent> readKeystrokeTelemetry(String videoPath) throws IOException {
		String content = Files.readString(
				Path.of(RecordingPaths.getKeystrokePathForVideo(videoPath)), StandardCharsets.UTF_8);
		JsonNode parsed = Json.parseJsonWithByteOrderMark(content);
		JsonNode version = parsed != null && parsed.isObject() ? parsed.get("version") : null;
		// A sidecar from an unknown future version is treated as no presses.
		if (version != null && version.isNumber() && version.asInt() != KEYSTROKE_TELEMETRY_VERSION) {
			return List.of();
		}
		return normalizeKeystrokeEvents(parsed);
	}
}
