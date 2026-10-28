package dev.gravaai.core.editor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.KeystrokeOverlaySettings;
import dev.gravaai.core.project.Padding;
import dev.gravaai.core.project.ProjectPersistence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Editor-preset normalisation and JSON encoding (port of the preset half of
 * {@code src/components/video-editor/editorPreferences.ts}).
 */
public final class EditorPresets {

	private static final Pattern WHITESPACE_RUN =
			Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
	private static final DateTimeFormatter ISO_MILLIS =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	private EditorPresets() {}

	/** {@code normalizePresetName}: trim, collapse spaces, null when blank. */
	public static String normalizePresetName(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		String collapsed = WHITESPACE_RUN.matcher(trimmed).replaceAll(" ");
		return collapsed.isEmpty() ? null : collapsed;
	}

	/** {@code new Date(parsed).toISOString()}; lenient like {@code Date.parse}. */
	public static String normalizePresetTimestamp(String value, String fallback) {
		Long millis = parseTimestamp(value);
		return millis == null ? fallback
				: ISO_MILLIS.format(Instant.ofEpochMilli(millis));
	}

	private static Long parseTimestamp(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		try {
			return Instant.parse(trimmed).toEpochMilli();
		} catch (DateTimeParseException ignored) {
			// try the other accepted shapes below
		}
		try {
			return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli();
		} catch (DateTimeParseException ignored) {
			// try the other accepted shapes below
		}
		try {
			return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toEpochMilli();
		} catch (DateTimeParseException ignored) {
			// try the other accepted shapes below
		}
		try {
			return LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	/** {@code normalizeEditorPresetSnapshot}. */
	public static EditorPresetSnapshot normalizeSnapshot(JsonNode candidate) {
		EditorPreferences preferences = EditorPreferences.normalize(
				candidate, EditorPreferences.defaults());
		JsonNode raw = candidate != null && candidate.isObject()
				? candidate : Json.nodeFactory().objectNode();
		CropRegion cropRegion = ProjectPersistence.normalizeProjectEditor(
				singleField(raw, "cropRegion")).cropRegion();
		AutoCaptionSettings autoCaptionSettings = ProjectPersistence.normalizeProjectEditor(
				singleField(raw, "autoCaptionSettings")).autoCaptionSettings();
		KeystrokeOverlaySettings keystrokeOverlay = ProjectPersistence.normalizeProjectEditor(
				singleField(raw, "keystrokeOverlay")).keystrokeOverlay();

		return new EditorPresetSnapshot(
				preferences.wallpaper(), preferences.shadowIntensity(), preferences.backgroundBlur(),
				preferences.zoomMotionBlur(), preferences.zoomMotionBlurTuning(),
				preferences.connectZooms(), preferences.zoomInDurationMs(),
				preferences.zoomInOverlapMs(), preferences.zoomOutDurationMs(),
				preferences.connectedZoomGapMs(), preferences.connectedZoomDurationMs(),
				preferences.zoomInEasing(), preferences.zoomOutEasing(),
				preferences.connectedZoomEasing(), preferences.showCursor(),
				preferences.loopCursor(), preferences.cursorStyle(), preferences.cursorSize(),
				preferences.cursorSmoothing(), preferences.cursorSpringStiffnessMultiplier(),
				preferences.cursorSpringDampingMultiplier(), preferences.cursorSpringMassMultiplier(),
				preferences.cameraSpringStiffnessMultiplier(),
				preferences.cameraSpringDampingMultiplier(), preferences.cameraSpringMassMultiplier(),
				preferences.cursorMotionBlur(), preferences.cursorClickEffect(),
				preferences.cursorClickEffectColor(), preferences.cursorClickEffectScale(),
				preferences.cursorClickEffectOpacity(), preferences.cursorClickEffectDurationMs(),
				preferences.cursorClickBounce(), preferences.cursorClickBounceDuration(),
				preferences.cursorSway(), preferences.borderRadius(), preferences.padding(),
				preferences.aspectRatio(), "percent",
				WebcamPresetSettings.from(preferences.webcam()), cropRegion, autoCaptionSettings,
				keystrokeOverlay);
	}

	private static JsonNode singleField(JsonNode raw, String field) {
		ObjectNode node = Json.nodeFactory().objectNode();
		if (raw != null && raw.isObject() && raw.has(field)) {
			node.set(field, raw.get(field));
		}
		return node;
	}

	/** {@code normalizeEditorPreset}: null when the name is missing/blank. */
	public static EditorPreset normalizePreset(JsonNode candidate) {
		if (candidate == null || !candidate.isObject()) {
			return null;
		}
		JsonNode rawName = candidate.get("name");
		String name = normalizePresetName(
				rawName != null && rawName.isTextual() ? rawName.asText() : null);
		if (name == null) {
			return null;
		}

		JsonNode rawId = candidate.get("id");
		String id = rawId != null && rawId.isTextual() && !rawId.asText().strip().isEmpty()
				? rawId.asText() : UUID.randomUUID().toString();
		String now = ISO_MILLIS.format(Instant.now());
		JsonNode rawCreatedAt = candidate.get("createdAt");
		JsonNode rawUpdatedAt = candidate.get("updatedAt");
		String createdAt = normalizePresetTimestamp(
				rawCreatedAt != null && rawCreatedAt.isTextual() ? rawCreatedAt.asText() : null, now);
		String updatedAt = normalizePresetTimestamp(
				rawUpdatedAt != null && rawUpdatedAt.isTextual() ? rawUpdatedAt.asText() : null, now);

		return new EditorPreset(id, name, createdAt, updatedAt,
				normalizeSnapshot(candidate.get("snapshot")));
	}

	/** {@code normalizeEditorPresets}: sorted newest first. */
	public static List<EditorPreset> normalizePresets(JsonNode candidates) {
		if (candidates == null || !candidates.isArray()) {
			return List.of();
		}
		List<EditorPreset> presets = new ArrayList<>();
		for (JsonNode candidate : candidates) {
			EditorPreset preset = normalizePreset(candidate);
			if (preset != null) {
				presets.add(preset);
			}
		}
		presets.sort((left, right) -> right.updatedAt().compareTo(left.updatedAt()));
		return List.copyOf(presets);
	}

	/** Normalises typed presets by round-tripping them through their JSON form. */
	public static List<EditorPreset> normalizePresets(List<EditorPreset> presets) {
		return normalizePresets(presetsToJson(presets));
	}

	/** {@code serializeEditorPresetSnapshot}: compact JSON of the normalised snapshot. */
	public static String serializeSnapshot(EditorPresetSnapshot snapshot) {
		EditorPresetSnapshot normalized = normalizeSnapshot(snapshotToJson(snapshot));
		return Json.MAPPER.writeValueAsString(snapshotToJson(normalized));
	}

	public static ObjectNode snapshotToJson(EditorPresetSnapshot snapshot) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("wallpaper", snapshot.wallpaper());
		node.put("shadowIntensity", snapshot.shadowIntensity());
		node.put("backgroundBlur", snapshot.backgroundBlur());
		node.put("zoomMotionBlur", snapshot.zoomMotionBlur());
		node.put("connectZooms", snapshot.connectZooms());
		node.put("zoomInDurationMs", snapshot.zoomInDurationMs());
		node.put("zoomInOverlapMs", snapshot.zoomInOverlapMs());
		node.put("zoomOutDurationMs", snapshot.zoomOutDurationMs());
		node.put("connectedZoomGapMs", snapshot.connectedZoomGapMs());
		node.put("connectedZoomDurationMs", snapshot.connectedZoomDurationMs());
		node.put("zoomInEasing", snapshot.zoomInEasing().wire());
		node.put("zoomOutEasing", snapshot.zoomOutEasing().wire());
		node.put("connectedZoomEasing", snapshot.connectedZoomEasing().wire());
		node.put("showCursor", snapshot.showCursor());
		node.put("loopCursor", snapshot.loopCursor());
		node.put("cursorStyle", snapshot.cursorStyle());
		node.put("cursorSize", snapshot.cursorSize());
		node.put("cursorSmoothing", snapshot.cursorSmoothing());
		node.put("cursorSpringStiffnessMultiplier", snapshot.cursorSpringStiffnessMultiplier());
		node.put("cursorSpringDampingMultiplier", snapshot.cursorSpringDampingMultiplier());
		node.put("cursorSpringMassMultiplier", snapshot.cursorSpringMassMultiplier());
		node.put("cameraSpringStiffnessMultiplier", snapshot.cameraSpringStiffnessMultiplier());
		node.put("cameraSpringDampingMultiplier", snapshot.cameraSpringDampingMultiplier());
		node.put("cameraSpringMassMultiplier", snapshot.cameraSpringMassMultiplier());
		node.put("cursorMotionBlur", snapshot.cursorMotionBlur());
		node.put("cursorClickEffect", snapshot.cursorClickEffect().wire());
		node.put("cursorClickEffectColor", snapshot.cursorClickEffectColor());
		node.put("cursorClickEffectScale", snapshot.cursorClickEffectScale());
		node.put("cursorClickEffectOpacity", snapshot.cursorClickEffectOpacity());
		node.put("cursorClickEffectDurationMs", snapshot.cursorClickEffectDurationMs());
		node.put("cursorClickBounce", snapshot.cursorClickBounce());
		node.put("cursorClickBounceDuration", snapshot.cursorClickBounceDuration());
		node.put("cursorSway", snapshot.cursorSway());
		node.put("borderRadius", snapshot.borderRadius());
		node.set("padding", paddingToJson(snapshot.padding()));
		node.put("aspectRatio", snapshot.aspectRatio());
		node.put("borderRadiusUnit", snapshot.borderRadiusUnit());
		node.set("webcam", snapshot.webcam().toJson());
		node.set("cropRegion", cropRegionToJson(snapshot.cropRegion()));
		node.set("autoCaptionSettings", autoCaptionSettingsToJson(snapshot.autoCaptionSettings()));
		node.set("keystrokeOverlay", keystrokeOverlayToJson(snapshot.keystrokeOverlay()));
		return node;
	}

	public static ObjectNode presetToJson(EditorPreset preset) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("id", preset.id());
		node.put("name", preset.name());
		node.put("createdAt", preset.createdAt());
		node.put("updatedAt", preset.updatedAt());
		node.set("snapshot", snapshotToJson(preset.snapshot()));
		return node;
	}

	public static ArrayNode presetsToJson(List<EditorPreset> presets) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (EditorPreset preset : presets) {
			array.add(presetToJson(preset));
		}
		return array;
	}

	private static ObjectNode paddingToJson(Padding padding) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("top", padding.top());
		node.put("bottom", padding.bottom());
		node.put("left", padding.left());
		node.put("right", padding.right());
		node.put("linked", Boolean.TRUE.equals(padding.linked()));
		return node;
	}

	private static ObjectNode cropRegionToJson(CropRegion crop) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("x", crop.x());
		node.put("y", crop.y());
		node.put("width", crop.width());
		node.put("height", crop.height());
		return node;
	}

	private static ObjectNode autoCaptionSettingsToJson(AutoCaptionSettings settings) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("enabled", settings.enabled());
		node.put("timelineQuickAdd", settings.timelineQuickAdd());
		node.put("language", settings.language());
		node.put("fontFamily", settings.fontFamily());
		node.put("fontSize", settings.fontSize());
		node.put("bottomOffset", settings.bottomOffset());
		node.put("maxWidth", settings.maxWidth());
		node.put("maxRows", settings.maxRows());
		node.put("animationStyle", settings.animationStyle().wire());
		node.put("boxRadius", settings.boxRadius());
		node.put("textColor", settings.textColor());
		node.put("inactiveTextColor", settings.inactiveTextColor());
		node.put("backgroundOpacity", settings.backgroundOpacity());
		return node;
	}

	private static ObjectNode keystrokeOverlayToJson(KeystrokeOverlaySettings settings) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("enabled", settings.enabled());
		node.put("position", settings.position().wire());
		node.put("fontSize", settings.fontSize());
		node.put("lingerMs", settings.lingerMs());
		node.put("fadeMs", settings.fadeMs());
		node.put("groupingMs", settings.groupingMs());
		node.put("bottomOffset", settings.bottomOffset());
		return node;
	}
}
