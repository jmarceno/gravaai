package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.CursorClickEffectStyle;
import dev.gravaai.core.project.Padding;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.WebcamOverlaySettings;
import dev.gravaai.core.project.ZoomMotionBlurTuning;
import dev.gravaai.core.project.ZoomTransitionEasing;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Editor preferences persisted outside a project (port of
 * {@code src/components/video-editor/editorPreferences.ts}, plan §5.11).
 *
 * <p>Kept flat like the TS {@code EditorPreferences} interface so the JSON names stay one-to-one.
 * The Electron build mirrors these values into {@code localStorage} and {@code electronAPI}
 * settings; the Java app has a single process, so {@link EditorPreferencesStore} persists them to
 * {@code app-settings.json} only (recorded narrowing).
 */
public record EditorPreferences(
		@Nullable String wallpaper,
		double shadowIntensity,
		double backgroundBlur,
		double zoomMotionBlur,
		ZoomMotionBlurTuning zoomMotionBlurTuning,
		boolean connectZooms,
		double zoomInDurationMs,
		double zoomInOverlapMs,
		double zoomOutDurationMs,
		double connectedZoomGapMs,
		double connectedZoomDurationMs,
		ZoomTransitionEasing zoomInEasing,
		ZoomTransitionEasing zoomOutEasing,
		ZoomTransitionEasing connectedZoomEasing,
		boolean showCursor,
		boolean loopCursor,
		String cursorStyle,
		double cursorSize,
		double cursorSmoothing,
		double cursorSpringStiffnessMultiplier,
		double cursorSpringDampingMultiplier,
		double cursorSpringMassMultiplier,
		double cameraSpringStiffnessMultiplier,
		double cameraSpringDampingMultiplier,
		double cameraSpringMassMultiplier,
		double cursorMotionBlur,
		CursorClickEffectStyle cursorClickEffect,
		String cursorClickEffectColor,
		double cursorClickEffectScale,
		double cursorClickEffectOpacity,
		double cursorClickEffectDurationMs,
		double cursorClickBounce,
		double cursorClickBounceDuration,
		double cursorSway,
		double borderRadius,
		Padding padding,
		WebcamOverlaySettings webcam,
		String aspectRatio,
		ExportPreferences.ExportEncodingMode exportEncodingMode,
		ExportPreferences.ExportBackendPreference exportBackendPreference,
		ExportPreferences.ExportPipelineModel exportPipelineModel,
		ExportPreferences.ExportQuality exportQuality,
		int mp4FrameRate,
		ExportPreferences.ExportFormat exportFormat,
		int gifFrameRate,
		boolean gifLoop,
		ExportPreferences.GifSizePreset gifSizePreset,
		ExportPreferences.PublishDestination publishDestination,
		boolean includeCaptionSidecar,
		String borderRadiusUnit,
		String customAspectWidth,
		String customAspectHeight,
		List<String> customWallpapers,
		boolean autoApplyFreshRecordingAutoZooms,
		@Nullable String whisperExecutablePath,
		@Nullable String whisperModelPath,
		@Nullable WhisperModel whisperModel) {

	public static final String EDITOR_PREFERENCES_STORAGE_KEY = "gravaai.editor.preferences";
	public static final String EDITOR_PRESETS_STORAGE_KEY = "gravaai.editor.presets";

	/** {@code DEFAULT_EDITOR_PREFERENCES}. */
	public static EditorPreferences defaults() {
		var controls = ProjectPersistence.normalizeProjectEditor(Json.nodeFactory().objectNode());
		ExportPreferences export = ExportPreferences.defaults();
		return new EditorPreferences(
				controls.wallpaper(), controls.shadowIntensity(), controls.backgroundBlur(),
				controls.zoomMotionBlur(), controls.zoomMotionBlurTuning(), controls.connectZooms(),
				controls.zoomInDurationMs(), controls.zoomInOverlapMs(), controls.zoomOutDurationMs(),
				controls.connectedZoomGapMs(), controls.connectedZoomDurationMs(),
				controls.zoomInEasing(), controls.zoomOutEasing(), controls.connectedZoomEasing(),
				controls.showCursor(), controls.loopCursor(), controls.cursorStyle(),
				controls.cursorSize(), controls.cursorSmoothing(),
				controls.cursorSpringStiffnessMultiplier(), controls.cursorSpringDampingMultiplier(),
				controls.cursorSpringMassMultiplier(), controls.cameraSpringStiffnessMultiplier(),
				controls.cameraSpringDampingMultiplier(), controls.cameraSpringMassMultiplier(),
				controls.cursorMotionBlur(), controls.cursorClickEffect(),
				controls.cursorClickEffectColor(), controls.cursorClickEffectScale(),
				controls.cursorClickEffectOpacity(), controls.cursorClickEffectDurationMs(),
				controls.cursorClickBounce(), controls.cursorClickBounceDuration(),
				controls.cursorSway(), controls.borderRadius(), controls.padding(),
				controls.webcam(), controls.aspectRatio(),
				export.exportEncodingMode(), export.exportBackendPreference(),
				export.exportPipelineModel(), export.exportQuality(), export.mp4FrameRate(),
				export.exportFormat(), export.gifFrameRate(), export.gifLoop(),
				export.gifSizePreset(), export.publishDestination(), export.includeCaptionSidecar(),
				"percent", "16", "9", List.of(), true, null, null, null);
	}

	/**
	 * {@code normalizeEditorPreferences}; {@code null} means "nothing stored", so every control
	 * comes from {@code fallback}.
	 */
	public static EditorPreferences normalize(@Nullable JsonNode candidate, EditorPreferences fallback) {
		JsonNode raw = candidate != null && candidate.isObject()
				? candidate : Json.nodeFactory().objectNode();
		JsonNode controlsRaw = controlsInput(raw);

		ObjectNode exportRaw = fallback.exportToJson();
		copyExportFields(raw, exportRaw);
		ExportPreferences export = ExportPreferences.normalizeExportPreferences(exportRaw);

		EditorPreferences controls = normalizeControls(controlsRaw, fallback);
		String whisperExecutablePath = normalizeNullablePath(raw.get("whisperExecutablePath"));
		String whisperModelPath = normalizeNullablePath(raw.get("whisperModelPath"));
		WhisperModel whisperModel = null;
		JsonNode rawWhisperModel = raw.get("whisperModel");
		if (rawWhisperModel != null && rawWhisperModel.isString()) {
			whisperModel = WhisperModel.fromWire(rawWhisperModel.asString());
		}

		return new EditorPreferences(
				controls.wallpaper(), controls.shadowIntensity(), controls.backgroundBlur(),
				controls.zoomMotionBlur(), controls.zoomMotionBlurTuning(), controls.connectZooms(),
				controls.zoomInDurationMs(), controls.zoomInOverlapMs(), controls.zoomOutDurationMs(),
				controls.connectedZoomGapMs(), controls.connectedZoomDurationMs(),
				controls.zoomInEasing(), controls.zoomOutEasing(), controls.connectedZoomEasing(),
				controls.showCursor(), controls.loopCursor(), controls.cursorStyle(),
				controls.cursorSize(), controls.cursorSmoothing(),
				controls.cursorSpringStiffnessMultiplier(), controls.cursorSpringDampingMultiplier(),
				controls.cursorSpringMassMultiplier(), controls.cameraSpringStiffnessMultiplier(),
				controls.cameraSpringDampingMultiplier(), controls.cameraSpringMassMultiplier(),
				controls.cursorMotionBlur(), controls.cursorClickEffect(),
				controls.cursorClickEffectColor(), controls.cursorClickEffectScale(),
				controls.cursorClickEffectOpacity(), controls.cursorClickEffectDurationMs(),
				controls.cursorClickBounce(), controls.cursorClickBounceDuration(),
				controls.cursorSway(), controls.borderRadius(), controls.padding(),
				controls.webcam(), controls.aspectRatio(),
				export.exportEncodingMode(), export.exportBackendPreference(),
				export.exportPipelineModel(), export.exportQuality(), export.mp4FrameRate(),
				export.exportFormat(), export.gifFrameRate(), export.gifLoop(),
				export.gifSizePreset(), export.publishDestination(), export.includeCaptionSidecar(),
				"percent",
				normalizePositiveIntegerString(raw.get("customAspectWidth"), fallback.customAspectWidth()),
				normalizePositiveIntegerString(raw.get("customAspectHeight"), fallback.customAspectHeight()),
				normalizeCustomWallpapers(raw.get("customWallpapers"), fallback.customWallpapers()),
				normalizeBoolean(raw.get("autoApplyFreshRecordingAutoZooms"),
						fallback.autoApplyFreshRecordingAutoZooms()),
				whisperExecutablePath != null
						? whisperExecutablePath : fallback.whisperExecutablePath(),
				whisperModelPath != null ? whisperModelPath : fallback.whisperModelPath(),
				whisperModel != null ? whisperModel : fallback.whisperModel());
	}

	/** Border-radius migration: pixels are converted once, then marked as percent. */
	private static JsonNode controlsInput(JsonNode raw) {
		JsonNode unit = raw.get("borderRadiusUnit");
		boolean unitIsPercent = unit != null && unit.isString() && "percent".equals(unit.asString());
		JsonNode radius = raw.get("borderRadius");
		boolean radiusIsNumber = radius != null && radius.isNumber();
		if (unitIsPercent || !radiusIsNumber) {
			return raw;
		}

		ObjectNode migrated = ((ObjectNode) raw).deepCopy();
		double legacy = radius.doubleValue();
		migrated.put("borderRadius", legacy == 0
				? ProjectPersistence.getDefaultBorderRadiusPercent()
				: ProjectPersistence.legacyBorderRadiusPixelsToPercent(legacy));
		return migrated;
	}

	private static void copyExportFields(JsonNode raw, ObjectNode target) {
		for (String field : List.of("exportEncodingMode", "exportBackendPreference",
				"exportPipelineModel", "exportQuality", "mp4FrameRate", "exportFormat",
				"gifFrameRate", "gifLoop", "gifSizePreset", "publishDestination",
				"includeCaptionSidecar")) {
			if (raw.has(field)) {
				target.set(field, raw.get(field));
			}
		}
	}

	/** {@code normalizeEditorControls}: pick raw-or-fallback per control, then normalise. */
	private static EditorPreferences normalizeControls(JsonNode raw, EditorPreferences fallback) {
		ObjectNode sanitized = (ObjectNode) raw;
		ObjectNode candidate = Json.nodeFactory().objectNode();
		ObjectNode fallbackControls = fallback.controlsToJson();
		for (var entry : fallbackControls.properties()) {
			JsonNode rawValue = sanitized.get(entry.getKey());
			candidate.set(entry.getKey(), rawValue != null && !rawValue.isNull()
					? rawValue : entry.getValue());
		}

		var normalized = ProjectPersistence.normalizeProjectEditor(candidate);
		return fallback.replaceControls(normalized);
	}

	/** The export half as a JSON object (used as the merge base in {@link #normalize}). */
	ObjectNode exportToJson() {
		return new ExportPreferences(exportEncodingMode, exportBackendPreference,
				exportPipelineModel, exportQuality, mp4FrameRate, exportFormat, gifFrameRate,
				gifLoop, gifSizePreset, publishDestination, includeCaptionSidecar).toJson();
	}

	private ObjectNode controlsToJson() {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("wallpaper", wallpaper);
		node.put("shadowIntensity", shadowIntensity);
		node.put("backgroundBlur", backgroundBlur);
		node.put("zoomMotionBlur", zoomMotionBlur);
		node.put("connectZooms", connectZooms);
		node.put("zoomInDurationMs", zoomInDurationMs);
		node.put("zoomInOverlapMs", zoomInOverlapMs);
		node.put("zoomOutDurationMs", zoomOutDurationMs);
		node.put("connectedZoomGapMs", connectedZoomGapMs);
		node.put("connectedZoomDurationMs", connectedZoomDurationMs);
		node.put("zoomInEasing", zoomInEasing.wire());
		node.put("zoomOutEasing", zoomOutEasing.wire());
		node.put("connectedZoomEasing", connectedZoomEasing.wire());
		node.put("showCursor", showCursor);
		node.put("loopCursor", loopCursor);
		node.put("cursorStyle", cursorStyle);
		node.put("cursorSize", cursorSize);
		node.put("cursorSmoothing", cursorSmoothing);
		node.put("cursorSpringStiffnessMultiplier", cursorSpringStiffnessMultiplier);
		node.put("cursorSpringDampingMultiplier", cursorSpringDampingMultiplier);
		node.put("cursorSpringMassMultiplier", cursorSpringMassMultiplier);
		node.put("cameraSpringStiffnessMultiplier", cameraSpringStiffnessMultiplier);
		node.put("cameraSpringDampingMultiplier", cameraSpringDampingMultiplier);
		node.put("cameraSpringMassMultiplier", cameraSpringMassMultiplier);
		node.put("cursorMotionBlur", cursorMotionBlur);
		node.put("cursorClickEffect", cursorClickEffect.wire());
		node.put("cursorClickEffectColor", cursorClickEffectColor);
		node.put("cursorClickEffectScale", cursorClickEffectScale);
		node.put("cursorClickEffectOpacity", cursorClickEffectOpacity);
		node.put("cursorClickEffectDurationMs", cursorClickEffectDurationMs);
		node.put("cursorClickBounce", cursorClickBounce);
		node.put("cursorClickBounceDuration", cursorClickBounceDuration);
		node.put("cursorSway", cursorSway);
		node.put("borderRadius", borderRadius);
		node.set("padding", paddingToJson(padding));
		node.set("webcam", webcamToJson(webcam));
		node.put("aspectRatio", aspectRatio);
		return node;
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

	private static ObjectNode webcamToJson(WebcamOverlaySettings webcam) {
		ObjectNode node = Json.nodeFactory().objectNode();
		if (webcam.visibleRanges() != null) {
			var ranges = Json.nodeFactory().arrayNode();
			for (WebcamOverlaySettings.TimeRange range : webcam.visibleRanges()) {
				ObjectNode rangeNode = Json.nodeFactory().objectNode();
				rangeNode.put("startMs", range.startMs());
				rangeNode.put("endMs", range.endMs());
				ranges.add(rangeNode);
			}
			node.set("visibleRanges", ranges);
		}
		node.put("enabled", webcam.enabled());
		if (webcam.sourcePath() != null) {
			node.put("sourcePath", webcam.sourcePath());
		} else {
			node.putNull("sourcePath");
		}
		node.put("mirror", webcam.mirror());
		ObjectNode crop = Json.nodeFactory().objectNode();
		crop.put("x", webcam.cropRegion().x());
		crop.put("y", webcam.cropRegion().y());
		crop.put("width", webcam.cropRegion().width());
		crop.put("height", webcam.cropRegion().height());
		node.set("cropRegion", crop);
		node.put("positionPreset", webcam.positionPreset().wire());
		node.put("positionX", webcam.positionX());
		node.put("positionY", webcam.positionY());
		node.put("corner", webcam.corner().wire());
		node.put("size", webcam.size());
		node.put("width", webcam.width());
		node.put("height", webcam.height());
		node.put("reactToZoom", webcam.reactToZoom());
		node.put("roundness", webcam.roundness());
		node.put("shadow", webcam.shadow());
		node.put("timeOffsetMs", webcam.timeOffsetMs());
		node.put("margin", webcam.margin());
		return node;
	}

	private EditorPreferences replaceControls(dev.gravaai.core.project.ProjectEditorState state) {
		return new EditorPreferences(
				state.wallpaper(), state.shadowIntensity(), state.backgroundBlur(),
				state.zoomMotionBlur(), state.zoomMotionBlurTuning(), state.connectZooms(),
				state.zoomInDurationMs(), state.zoomInOverlapMs(), state.zoomOutDurationMs(),
				state.connectedZoomGapMs(), state.connectedZoomDurationMs(), state.zoomInEasing(),
				state.zoomOutEasing(), state.connectedZoomEasing(), state.showCursor(),
				state.loopCursor(), state.cursorStyle(), state.cursorSize(), state.cursorSmoothing(),
				state.cursorSpringStiffnessMultiplier(), state.cursorSpringDampingMultiplier(),
				state.cursorSpringMassMultiplier(), state.cameraSpringStiffnessMultiplier(),
				state.cameraSpringDampingMultiplier(), state.cameraSpringMassMultiplier(),
				state.cursorMotionBlur(), state.cursorClickEffect(), state.cursorClickEffectColor(),
				state.cursorClickEffectScale(), state.cursorClickEffectOpacity(),
				state.cursorClickEffectDurationMs(), state.cursorClickBounce(),
				state.cursorClickBounceDuration(), state.cursorSway(), state.borderRadius(),
				state.padding(), state.webcam(), state.aspectRatio(),
				exportEncodingMode, exportBackendPreference, exportPipelineModel, exportQuality,
				mp4FrameRate, exportFormat, gifFrameRate, gifLoop, gifSizePreset,
				publishDestination, includeCaptionSidecar, borderRadiusUnit, customAspectWidth,
				customAspectHeight, customWallpapers, autoApplyFreshRecordingAutoZooms,
				whisperExecutablePath, whisperModelPath, whisperModel);
	}

	/** Full persisted JSON (callers strip the dev-only tuning before writing). */
	public ObjectNode toJson() {
		ObjectNode node = controlsToJson();
		node.setAll(exportToJson());
		node.put("borderRadiusUnit", borderRadiusUnit);
		node.put("customAspectWidth", customAspectWidth);
		node.put("customAspectHeight", customAspectHeight);
		var wallpapers = Json.nodeFactory().arrayNode();
		customWallpapers.forEach(wallpapers::add);
		node.set("customWallpapers", wallpapers);
		node.put("autoApplyFreshRecordingAutoZooms", autoApplyFreshRecordingAutoZooms);
		if (whisperExecutablePath != null) {
			node.put("whisperExecutablePath", whisperExecutablePath);
		} else {
			node.putNull("whisperExecutablePath");
		}
		if (whisperModelPath != null) {
			node.put("whisperModelPath", whisperModelPath);
		} else {
			node.putNull("whisperModelPath");
		}
		if (whisperModel != null) {
			node.put("whisperModel", whisperModel.wire());
		} else {
			node.putNull("whisperModel");
		}
		return node;
	}

	/** Boolean normaliser; {@code null} when the stored field is missing. */
	private static boolean normalizeBoolean(@Nullable JsonNode value, boolean fallback) {
		return value != null && value.isBoolean() ? value.booleanValue() : fallback;
	}

	/** Positive-integer string normaliser; {@code null} when the stored field is missing. */
	private static String normalizePositiveIntegerString(@Nullable JsonNode value, String fallback) {
		if (value == null || !value.isString() || value.asString().strip().isEmpty()) {
			return fallback;
		}
		try {
			long parsed = Long.parseLong(value.asString().strip());
			return parsed > 0 ? Long.toString(parsed) : fallback;
		} catch (NumberFormatException error) {
			return fallback;
		}
	}

	private static List<String> normalizeCustomWallpapers(@Nullable JsonNode value, List<String> fallback) {
		if (value == null || !value.isArray()) {
			return fallback;
		}
		Set<String> unique = new LinkedHashSet<>();
		for (JsonNode item : value) {
			if (item != null && item.isString() && !item.asString().isEmpty()) {
				unique.add(item.asString());
			}
		}
		return List.copyOf(unique);
	}

	/** Optional path normaliser: {@code null} result when the stored value is absent or blank. */
	private static @Nullable String normalizeNullablePath(@Nullable JsonNode value) {
		if (value == null || !value.isString()) {
			return null;
		}
		String trimmed = value.asString().strip();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
