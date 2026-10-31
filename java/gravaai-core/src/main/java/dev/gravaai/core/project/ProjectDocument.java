package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A loaded project file: the typed editor state plus the raw JSON tree it came from (plan §5.11).
 *
 * <p>Unknown fields in a project must survive a round trip. The raw tree is kept as loaded and
 * {@link #toJson()} merges the typed state on top of it: known fields are rewritten canonically,
 * everything the Java build does not know about is preserved, and retired app preferences / the
 * developer motion-blur tuning are dropped exactly like {@code createProjectData} +
 * {@code stripPersistedDevMotionBlurSettings} do in the TS sources.
 */
public final class ProjectDocument {

	/** Every editor field this build knows; they are rewritten from the typed state on save. */
	private static final List<String> KNOWN_EDITOR_FIELDS = List.of(
			"wallpaper", "shadowIntensity", "backgroundBlur", "zoomMotionBlur", "zoomMotionBlurTuning",
			"connectZooms", "zoomInDurationMs", "zoomInOverlapMs", "zoomOutDurationMs",
			"connectedZoomGapMs", "connectedZoomDurationMs", "zoomInEasing", "zoomOutEasing",
			"connectedZoomEasing", "showCursor", "loopCursor", "cursorStyle", "cursorClickEffect",
			"cursorClickEffectColor", "cursorClickEffectScale", "cursorClickEffectOpacity",
			"cursorClickEffectDurationMs", "cursorSize", "cursorSmoothing",
			"cursorSpringStiffnessMultiplier", "cursorSpringDampingMultiplier",
			"cursorSpringMassMultiplier", "cameraSpringStiffnessMultiplier",
			"cameraSpringDampingMultiplier", "cameraSpringMassMultiplier", "zoomSmoothness",
			"zoomClassicMode", "cursorMotionBlur", "cursorClickBounce", "cursorClickBounceDuration",
			"cursorSway", "borderRadius", "padding", "cropRegion", "zoomRegions", "trimRegions",
			"clipRegions", "autoFullTrackClipId", "autoFullTrackClipEndMs", "speedRegions",
			"annotationRegions", "audioRegions", "autoCaptions", "autoCaptionSettings",
			"keystrokeOverlay", "webcam", "aspectRatio", "sourceAudioTrackSettingsByClip",
			"defaultSourceAudioTrackSettings", "keystrokeElements");

	private final ObjectNode raw;
	private final String videoPath;
	private final @Nullable String projectId;
	private final ProjectEditorState editor;

	private ProjectDocument(ObjectNode raw, String videoPath, @Nullable String projectId, ProjectEditorState editor) {
		this.raw = raw;
		this.videoPath = videoPath;
		this.projectId = projectId;
		this.editor = editor;
	}

	/**
	 * Loads and normalizes a project file.
	 *
	 * @throws IOException when the file cannot be read
	 * @throws IllegalArgumentException when the JSON is not a valid project file
	 */
	public static ProjectDocument load(Path file) throws IOException {
		JsonNode parsed = Json.readFile(file);
		if (parsed == null || !parsed.isObject() || !ProjectPersistence.validateProjectData(parsed)) {
			throw new IllegalArgumentException("Not a Gravaai project file: " + file);
		}
		ObjectNode raw = (ObjectNode) parsed;
		String loadedVideoPath = raw.get("videoPath").asString();
		String loadedProjectId = raw.hasNonNull("projectId") ? raw.get("projectId").asString() : null;
		ProjectEditorState normalized = ProjectPersistence.normalizeProjectEditor(raw.get("editor"));
		return new ProjectDocument(raw, loadedVideoPath, loadedProjectId, normalized);
	}

	/** A new, unsaved project: no unknown fields to preserve yet. */
	public static ProjectDocument create(String videoPath, ProjectEditorState editor) {
		return new ProjectDocument(Json.nodeFactory().objectNode(), videoPath, null, editor);
	}

	public ObjectNode raw() {
		return raw;
	}

	public String videoPath() {
		return videoPath;
	}

	public @Nullable String projectId() {
		return projectId;
	}

	public ProjectEditorState editor() {
		return editor;
	}

	public ProjectDocument withEditor(ProjectEditorState nextEditor) {
		return new ProjectDocument(raw, videoPath, projectId, nextEditor);
	}

	public ProjectDocument withVideoPath(String nextVideoPath) {
		return new ProjectDocument(raw, nextVideoPath, projectId, editor);
	}

	public ProjectDocument withProjectId(String nextProjectId) {
		return new ProjectDocument(raw, videoPath, nextProjectId, editor);
	}

	/** The exact JSON a save writes: raw unknown fields + the canonical typed state. */
	public ObjectNode toJson() {
		ObjectNode result = raw.deepCopy();
		result.put("version", ProjectPersistence.PROJECT_VERSION);
		result.put("videoPath", videoPath);
		if (projectId != null) {
			result.put("projectId", projectId);
		}
		result.set("editor", writeEditor(editor, rawEditor()));
		return result;
	}

	/** Writes the project as UTF-8 JSON; callers normally write it through the atomic saver. */
	public void write(Path file) throws IOException {
		Json.writeFile(file, toJson());
	}

	private ObjectNode rawEditor() {
		JsonNode rawEditor = raw.get("editor");
		return rawEditor != null && rawEditor.isObject()
				? (ObjectNode) rawEditor : Json.nodeFactory().objectNode();
	}

	/** Serializes the typed editor state, keeping the raw tree's unknown fields. */
	public static ObjectNode writeEditor(ProjectEditorState state, ObjectNode rawEditor) {
		ObjectNode editor = Json.nodeFactory().objectNode();

		editor.put("wallpaper", state.wallpaper());
		editor.put("shadowIntensity", state.shadowIntensity());
		editor.put("backgroundBlur", state.backgroundBlur());
		editor.put("zoomMotionBlur", state.zoomMotionBlur());
		editor.put("connectZooms", state.connectZooms());
		editor.put("zoomInDurationMs", state.zoomInDurationMs());
		editor.put("zoomInOverlapMs", state.zoomInOverlapMs());
		editor.put("zoomOutDurationMs", state.zoomOutDurationMs());
		editor.put("connectedZoomGapMs", state.connectedZoomGapMs());
		editor.put("connectedZoomDurationMs", state.connectedZoomDurationMs());
		editor.put("zoomInEasing", state.zoomInEasing().wire());
		editor.put("zoomOutEasing", state.zoomOutEasing().wire());
		editor.put("connectedZoomEasing", state.connectedZoomEasing().wire());
		editor.put("showCursor", state.showCursor());
		editor.put("loopCursor", state.loopCursor());
		editor.put("cursorStyle", state.cursorStyle());
		editor.put("cursorClickEffect", state.cursorClickEffect().wire());
		editor.put("cursorClickEffectColor", state.cursorClickEffectColor());
		editor.put("cursorClickEffectScale", state.cursorClickEffectScale());
		editor.put("cursorClickEffectOpacity", state.cursorClickEffectOpacity());
		editor.put("cursorClickEffectDurationMs", state.cursorClickEffectDurationMs());
		editor.put("cursorSize", state.cursorSize());
		editor.put("cursorSmoothing", state.cursorSmoothing());
		editor.put("cursorSpringStiffnessMultiplier", state.cursorSpringStiffnessMultiplier());
		editor.put("cursorSpringDampingMultiplier", state.cursorSpringDampingMultiplier());
		editor.put("cursorSpringMassMultiplier", state.cursorSpringMassMultiplier());
		editor.put("cameraSpringStiffnessMultiplier", state.cameraSpringStiffnessMultiplier());
		editor.put("cameraSpringDampingMultiplier", state.cameraSpringDampingMultiplier());
		editor.put("cameraSpringMassMultiplier", state.cameraSpringMassMultiplier());
		editor.put("zoomSmoothness", state.zoomSmoothness());
		editor.put("zoomClassicMode", state.zoomClassicMode());
		editor.put("cursorMotionBlur", state.cursorMotionBlur());
		editor.put("cursorClickBounce", state.cursorClickBounce());
		editor.put("cursorClickBounceDuration", state.cursorClickBounceDuration());
		editor.put("cursorSway", state.cursorSway());
		editor.put("borderRadius", state.borderRadius());
		editor.set("padding", writePadding(state.padding()));
		editor.set("cropRegion", writeCropRegion(state.cropRegion()));
		editor.set("zoomRegions", writeZoomRegions(state.zoomRegions()));
		editor.set("trimRegions", writeTrimRegions(state.trimRegions()));
		editor.set("clipRegions", writeClipRegions(state.clipRegions()));
		if (state.autoFullTrackClipId() != null) {
			editor.put("autoFullTrackClipId", state.autoFullTrackClipId());
		}
		if (state.autoFullTrackClipEndMs() != null) {
			editor.put("autoFullTrackClipEndMs", state.autoFullTrackClipEndMs());
		}
		editor.set("speedRegions", writeSpeedRegions(state.speedRegions()));
		editor.set("annotationRegions", writeAnnotationRegions(state.annotationRegions()));
		editor.set("audioRegions", writeAudioRegions(state.audioRegions()));
		editor.set("autoCaptions", writeAutoCaptions(state.autoCaptions()));
		editor.set("autoCaptionSettings", writeAutoCaptionSettings(state.autoCaptionSettings()));
		editor.set("keystrokeOverlay", writeKeystrokeOverlay(state.keystrokeOverlay()));
		editor.set("webcam", writeWebcam(state.webcam()));
		editor.put("aspectRatio", state.aspectRatio());
		editor.set("sourceAudioTrackSettingsByClip", state.sourceAudioTrackSettingsByClip().deepCopy());
		editor.set("defaultSourceAudioTrackSettings", state.defaultSourceAudioTrackSettings().deepCopy());
		editor.set("keystrokeElements", writeKeystrokeElements(state.keystrokeElements()));

		// Keep the fields this build does not know; drop retired preferences and dev-only tuning.
		ObjectNode persistedRaw = ProjectPersistence.stripPersistedDevMotionBlurSettings(
				ProjectPersistence.stripProjectUserPreferences(rawEditor));
		for (Map.Entry<String, JsonNode> entry : persistedRaw.properties()) {
			if (!KNOWN_EDITOR_FIELDS.contains(entry.getKey())) {
				editor.set(entry.getKey(), entry.getValue());
			}
		}
		return editor;
	}

	private static ObjectNode writePadding(Padding padding) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("top", padding.top());
		node.put("bottom", padding.bottom());
		node.put("left", padding.left());
		node.put("right", padding.right());
		node.put("linked", Boolean.TRUE.equals(padding.linked()));
		return node;
	}

	private static ObjectNode writeCropRegion(CropRegion crop) {
		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("x", crop.x());
		node.put("y", crop.y());
		node.put("width", crop.width());
		node.put("height", crop.height());
		return node;
	}

	public static ArrayNode writeZoomRegions(List<ZoomRegion> regions) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (ZoomRegion region : regions) {
			ObjectNode node = Json.nodeFactory().objectNode();
			node.put("id", region.id());
			node.put("startMs", region.startMs());
			node.put("endMs", region.endMs());
			node.put("depth", region.depth().wire());
			ObjectNode focus = Json.nodeFactory().objectNode();
			focus.put("cx", region.focus().cx());
			focus.put("cy", region.focus().cy());
			node.set("focus", focus);
			if (region.mode() != null) {
				node.put("mode", region.mode().wire());
			}
			array.add(node);
		}
		return array;
	}

	public static ArrayNode writeTrimRegions(List<TrimRegion> regions) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (TrimRegion region : regions) {
			ObjectNode node = Json.nodeFactory().objectNode();
			node.put("id", region.id());
			node.put("startMs", region.startMs());
			node.put("endMs", region.endMs());
			array.add(node);
		}
		return array;
	}

	public static ArrayNode writeClipRegions(List<ClipRegion> regions) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (ClipRegion region : regions) {
			ObjectNode node = Json.nodeFactory().objectNode();
			node.put("id", region.id());
			node.put("startMs", region.startMs());
			node.put("endMs", region.endMs());
			if (region.sourceStartMs() != null) {
				node.put("sourceStartMs", region.sourceStartMs());
			}
			if (region.sourceMinMs() != null) {
				node.put("sourceMinMs", region.sourceMinMs());
			}
			if (region.sourceMaxMs() != null) {
				node.put("sourceMaxMs", region.sourceMaxMs());
			}
			node.put("speed", region.speed());
			node.put("muted", Boolean.TRUE.equals(region.muted()));
			node.put("showSourceAudio", Boolean.TRUE.equals(region.showSourceAudio()));
			if (region.cameraLayout() != null) {
				node.put("cameraLayout", region.cameraLayout().wire());
			}
			if (region.transitionOut() != null) {
				ObjectNode transition = Json.nodeFactory().objectNode();
				transition.put("kind", region.transitionOut().kind().wire());
				transition.put("durationMs", region.transitionOut().durationMs());
				node.set("transitionOut", transition);
			}
			array.add(node);
		}
		return array;
	}

	public static ArrayNode writeSpeedRegions(List<SpeedRegion> regions) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (SpeedRegion region : regions) {
			ObjectNode node = Json.nodeFactory().objectNode();
			node.put("id", region.id());
			node.put("startMs", region.startMs());
			node.put("endMs", region.endMs());
			node.put("speed", region.speed());
			array.add(node);
		}
		return array;
	}

	public static ArrayNode writeAnnotationRegions(List<AnnotationRegion> regions) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (AnnotationRegion region : regions) {
			ObjectNode node = Json.nodeFactory().objectNode();
			node.put("id", region.id());
			node.put("startMs", region.startMs());
			node.put("endMs", region.endMs());
			node.put("type", region.type().wire());
			node.put("content", region.content());
			if (region.textContent() != null) {
				node.put("textContent", region.textContent());
			}
			if (region.imageContent() != null) {
				node.put("imageContent", region.imageContent());
			}
			ObjectNode position = Json.nodeFactory().objectNode();
			position.put("x", region.position().x());
			position.put("y", region.position().y());
			node.set("position", position);
			ObjectNode size = Json.nodeFactory().objectNode();
			size.put("width", region.size().width());
			size.put("height", region.size().height());
			node.set("size", size);
			ObjectNode style = Json.nodeFactory().objectNode();
			style.put("color", region.style().color());
			style.put("backgroundColor", region.style().backgroundColor());
			style.put("fontSize", region.style().fontSize());
			style.put("fontFamily", region.style().fontFamily());
			style.put("fontWeight", region.style().fontWeight().wire());
			style.put("fontStyle", region.style().fontStyle().wire());
			style.put("textDecoration", region.style().textDecoration().wire());
			style.put("textAlign", region.style().textAlign().wire());
			style.put("borderRadius", region.style().borderRadius());
			node.set("style", style);
			node.put("zIndex", region.zIndex());
			node.put("trackIndex", region.trackIndex() == null ? 0 : region.trackIndex());
			if (region.figureData() != null) {
				ObjectNode figure = Json.nodeFactory().objectNode();
				figure.put("arrowDirection", region.figureData().arrowDirection().wire());
				figure.put("color", region.figureData().color());
				figure.put("strokeWidth", region.figureData().strokeWidth());
				node.set("figureData", figure);
			}
			node.put("blurIntensity", region.blurIntensity() == null ? 20 : region.blurIntensity());
			if (region.blurColor() != null) {
				node.put("blurColor", region.blurColor());
			}
			array.add(node);
		}
		return array;
	}

	public static ArrayNode writeAudioRegions(List<AudioRegion> regions) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (AudioRegion region : regions) {
			ObjectNode node = Json.nodeFactory().objectNode();
			node.put("id", region.id());
			node.put("startMs", region.startMs());
			node.put("endMs", region.endMs());
			node.put("audioPath", region.audioPath());
			node.put("volume", region.volume());
			node.put("normalize", Boolean.TRUE.equals(region.normalize()));
			node.put("trackIndex", region.trackIndex() == null ? 0 : region.trackIndex());
			array.add(node);
		}
		return array;
	}

	public static ArrayNode writeAutoCaptions(List<CaptionCue> cues) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (CaptionCue cue : cues) {
			ObjectNode node = Json.nodeFactory().objectNode();
			node.put("id", cue.id());
			node.put("startMs", cue.startMs());
			node.put("endMs", cue.endMs());
			node.put("text", cue.text());
			if (cue.words() != null) {
				ArrayNode words = Json.nodeFactory().arrayNode();
				for (CaptionCueWord word : cue.words()) {
					ObjectNode wordNode = Json.nodeFactory().objectNode();
					wordNode.put("text", word.text());
					wordNode.put("startMs", word.startMs());
					wordNode.put("endMs", word.endMs());
					if (Boolean.TRUE.equals(word.leadingSpace())) {
						wordNode.put("leadingSpace", true);
					}
					words.add(wordNode);
				}
				node.set("words", words);
			}
			array.add(node);
		}
		return array;
	}

	public static ObjectNode writeAutoCaptionSettings(AutoCaptionSettings settings) {
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

	private static ObjectNode writeKeystrokeOverlay(KeystrokeOverlaySettings settings) {		ObjectNode node = Json.nodeFactory().objectNode();
		node.put("enabled", settings.enabled());
		node.put("position", settings.position().wire());
		node.put("fontSize", settings.fontSize());
		node.put("lingerMs", settings.lingerMs());
		node.put("fadeMs", settings.fadeMs());
		node.put("groupingMs", settings.groupingMs());
		node.put("bottomOffset", settings.bottomOffset());
		return node;
	}

	/** Serialises the editable key-press elements (the timeline Keystrokes track). */
	public static ArrayNode writeKeystrokeElements(List<KeystrokeElement> elements) {
		ArrayNode array = Json.nodeFactory().arrayNode();
		for (KeystrokeElement element : elements == null ? List.<KeystrokeElement>of() : elements) {
			ObjectNode node = Json.nodeFactory().objectNode();
			node.put("id", element.id());
			node.put("startMs", element.startMs());
			node.put("endMs", element.endMs());
			node.put("key", element.key());
			ArrayNode modifiers = node.putArray("modifiers");
			for (String modifier : element.orderedModifiers()) {
				modifiers.add(modifier);
			}
			array.add(node);
		}
		return array;
	}

	/** Serialises the webcam overlay settings (the import controller writes a composed webcam). */
	public static ObjectNode writeWebcam(WebcamOverlaySettings webcam) {
		ObjectNode node = Json.nodeFactory().objectNode();
		if (webcam.visibleRanges() != null) {
			ArrayNode ranges = Json.nodeFactory().arrayNode();
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
		node.set("cropRegion", writeCropRegion(webcam.cropRegion()));
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
}
