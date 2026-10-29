package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Project persistence helpers (first slice of the port of
 * {@code src/components/video-editor/projectPersistence.ts}; plan §5.11).
 *
 * <p>The normalisers and the full v2 load/save arrive with the rest of the persistence port; the
 * helpers here are the ones the dirty-state comparison and the save pipeline depend on:
 * project data creation, user-preference stripping and dropping the developer-only motion-blur
 * tuning.
 */
// Loose-JSON port: values are looked up, null-checked and read again, which NullAway cannot
// follow. Checked by its tests instead; typed code it returns is null-checked.
@org.jspecify.annotations.NullUnmarked
public final class ProjectPersistence {

	public static final long PROJECT_VERSION = 2;
	public static final double MACOS_DEFAULT_BORDER_RADIUS_PERCENT = 8;
	private static final double LEGACY_BORDER_RADIUS_REFERENCE_PX = 1080;
	private static final double[] PLAYBACK_SPEEDS = {0.25, 0.5, 0.75, 1.25, 1.5, 1.75, 2};

	/** Keys that older project files may carry but are app preferences, never project edits. */
	private static final List<String> PROJECT_USER_PREFERENCE_KEYS = List.of(
			"exportEncodingMode",
			"exportBackendPreference",
			"exportPipelineModel",
			"exportQuality",
			"mp4FrameRate",
			"exportFormat",
			"gifFrameRate",
			"gifLoop",
			"gifSizePreset",
			"publishDestination",
			"includeCaptionSidecar",
			"whisperExecutablePath",
			"whisperModelPath");

	private ProjectPersistence() {}

	/** {@code createProjectData}: version 2, an optional non-blank project id, stripped editor. */
	public static ObjectNode createProjectData(String videoPath, ObjectNode editor, String projectId) {
		ObjectNode data = Json.nodeFactory().objectNode();
		data.put("version", PROJECT_VERSION);
		if (projectId != null && !projectId.isBlank()) {
			data.put("projectId", projectId);
		}
		data.put("videoPath", videoPath);
		data.set("editor", stripProjectUserPreferences(editor));
		return data;
	}

	/** {@code stripProjectUserPreferences}: copy of the editor without the app-preference keys. */
	public static ObjectNode stripProjectUserPreferences(ObjectNode editor) {
		ObjectNode result = editor.deepCopy();
		for (String key : PROJECT_USER_PREFERENCE_KEYS) {
			result.remove(key);
		}
		return result;
	}

	/** {@code stripPersistedDevMotionBlurSettings}: drops the developer-only tuning object. */
	public static ObjectNode stripPersistedDevMotionBlurSettings(ObjectNode editor) {
		ObjectNode result = editor.deepCopy();
		result.remove("zoomMotionBlurTuning");
		return result;
	}

	/** {@code validateProjectData}. */
	public static boolean validateProjectData(JsonNode candidate) {
		if (candidate == null || !candidate.isObject()) {
			return false;
		}
		JsonNode version = candidate.get("version");
		if (version == null || !version.isNumber()) {
			return false;
		}
		JsonNode projectId = candidate.get("projectId");
		if (projectId != null && !projectId.isNull() && !projectId.isString()) {
			return false;
		}
		JsonNode videoPath = candidate.get("videoPath");
		if (videoPath == null || !videoPath.isString() || videoPath.asString().isEmpty()) {
			return false;
		}
		JsonNode editor = candidate.get("editor");
		return editor != null && editor.isObject();
	}

	public static double getDefaultBorderRadiusPercent() {
		return getDefaultBorderRadiusPercent(System.getProperty("os.name", ""));
	}

	/** {@code getDefaultBorderRadiusPercent}: 8% on macOS, square corners elsewhere. */
	public static double getDefaultBorderRadiusPercent(String platform) {
		return platform != null && platform.toLowerCase(Locale.ROOT).contains("mac")
				? MACOS_DEFAULT_BORDER_RADIUS_PERCENT
				: 0;
	}

	/** {@code legacyBorderRadiusPixelsToPercent}: 1080p-relative pixels to percent of the short side. */
	public static double legacyBorderRadiusPixelsToPercent(double value) {
		return (value / LEGACY_BORDER_RADIUS_REFERENCE_PX) * 100;
	}

	// ---------------------------------------------------------------------
	// file:// URLs (the local-media-server conversion is N/A in the Java app, plan §5.5)
	// ---------------------------------------------------------------------

	private static boolean isFileUrl(String value) {
		return value.matches("(?i)^file://.*");
	}

	/** {@code toFileUrl}. */
	public static String toFileUrl(String filePath) {
		String normalized = filePath.replace('\\', '/');

		if (normalized.matches("^[a-zA-Z]:/.*")) {
			return "file://" + encodePathSegments("/" + normalized, true);
		}

		if (normalized.startsWith("//")) {
			String[] hostAndPath = normalized.replaceFirst("^/+", "").split("/", 2);
			String host = hostAndPath[0];
			String encodedPath = hostAndPath.length < 2
					? ""
					: Arrays.stream(hostAndPath[1].split("/", -1))
							.map(ProjectPersistence::encodeUriComponent)
							.collect(Collectors.joining("/"));
			return encodedPath.isEmpty() ? "file://" + host + "/" : "file://" + host + "/" + encodedPath;
		}

		String absolutePath = normalized.startsWith("/") ? normalized : "/" + normalized;
		return "file://" + encodePathSegments(absolutePath, false);
	}

	/** {@code fromFileUrl}. */
	public static String fromFileUrl(String fileUrl) {
		String value = fileUrl.trim();
		// The TS version first asks the media HTTP server; the Java app decodes files directly.
		if (!isFileUrl(value)) {
			return value;
		}

		try {
			URI uri = URI.create(value);
			String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
			String pathname = decodePercent(rawPath);
			String host = uri.getHost();
			if (host != null && !host.isEmpty() && !host.equals("localhost")) {
				String uncPath = "//" + host + (pathname.startsWith("/") ? pathname : "/" + pathname);
				return uncPath.replace("/", "\\");
			}
			if (pathname.matches("^/[A-Za-z]:.*")) {
				return pathname.substring(1);
			}
			return pathname;
		} catch (RuntimeException error) {
			String rawFallbackPath = value.replaceFirst("(?i)^file://", "");
			String fallbackPath;
			try {
				fallbackPath = decodePercent(rawFallbackPath);
			} catch (RuntimeException decodeError) {
				fallbackPath = rawFallbackPath;
			}
			return fallbackPath.replaceFirst("^/([a-zA-Z]:)", "$1");
		}
	}

	private static String encodePathSegments(String pathname, boolean keepWindowsDrive) {
		String[] segments = pathname.split("/", -1);
		List<String> encoded = new ArrayList<>(segments.length);
		for (int index = 0; index < segments.length; index++) {
			String segment = segments[index];
			if (segment.isEmpty()) {
				encoded.add("");
			} else if (keepWindowsDrive && index == 1 && segment.matches("^[a-zA-Z]:$")) {
				encoded.add(segment);
			} else {
				encoded.add(encodeUriComponent(segment));
			}
		}
		return String.join("/", encoded);
	}

	/** {@code encodeURIComponent} for one path segment. */
	private static String encodeUriComponent(String segment) {
		StringBuilder result = new StringBuilder();
		for (byte raw : segment.getBytes(StandardCharsets.UTF_8)) {
			int value = raw & 0xff;
			char character = (char) value;
			if ((character >= 'A' && character <= 'Z')
					|| (character >= 'a' && character <= 'z')
					|| (character >= '0' && character <= '9')
					|| "-_.!~*'()".indexOf(character) >= 0) {
				result.append(character);
			} else {
				result.append('%').append(String.format(Locale.ROOT, "%02X", value));
			}
		}
		return result.toString();
	}

	/** {@code decodeURIComponent}. */
	private static String decodePercent(String value) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
		int index = 0;
		while (index < value.length()) {
			char character = value.charAt(index);
			if (character == '%' && index + 2 < value.length()
					&& isHex(value.charAt(index + 1)) && isHex(value.charAt(index + 2))) {
				bytes.write(Integer.parseInt(value.substring(index + 1, index + 3), 16));
				index += 3;
			} else {
				byte[] chunk = String.valueOf(character).getBytes(StandardCharsets.UTF_8);
				bytes.write(chunk, 0, chunk.length);
				index++;
			}
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}

	private static boolean isHex(char character) {
		return (character >= '0' && character <= '9')
				|| (character >= 'a' && character <= 'f')
				|| (character >= 'A' && character <= 'F');
	}

	// ---------------------------------------------------------------------
	// normalizeProjectEditor
	// ---------------------------------------------------------------------

	public static ProjectEditorState normalizeProjectEditor(JsonNode rawEditor) {
		JsonNode editor = rawEditor != null && rawEditor.isObject() ? rawEditor : Json.nodeFactory().objectNode();

		boolean legacyMotionBlurEnabled = Boolean.TRUE.equals(bool(field(editor, "motionBlurEnabled")));
		boolean legacyShowBlur = Boolean.TRUE.equals(bool(field(editor, "showBlur")));

		double normalizedZoomMotionBlur = isFiniteNumber(field(editor, "zoomMotionBlur"))
				? clamp(field(editor, "zoomMotionBlur").doubleValue(), 0, 2)
				: legacyMotionBlurEnabled ? 0.35 : EditorDefaults.DEFAULT_ZOOM_MOTION_BLUR;
		ZoomMotionBlurTuning normalizedZoomMotionBlurTuning =
				normalizeZoomMotionBlurTuning(field(editor, "zoomMotionBlurTuning"));
		double normalizedBackgroundBlur = isFiniteNumber(field(editor, "backgroundBlur"))
				? clamp(field(editor, "backgroundBlur").doubleValue(), 0, 8)
				: legacyShowBlur ? 2 : 0;

		double normalizedZoomInDurationMs = isFiniteNumber(field(editor, "zoomInDurationMs"))
				? clamp(field(editor, "zoomInDurationMs").doubleValue(), 60, 4000)
				: 200;
		double normalizedZoomInOverlapMs = isFiniteNumber(field(editor, "zoomInOverlapMs"))
				? clamp(field(editor, "zoomInOverlapMs").doubleValue(), 0, normalizedZoomInDurationMs)
				: EditorDefaults.DEFAULT_ZOOM_IN_OVERLAP_MS;
		double normalizedZoomOutDurationMs = isFiniteNumber(field(editor, "zoomOutDurationMs"))
				? clamp(field(editor, "zoomOutDurationMs").doubleValue(), 60, 4000)
				: 200;
		double normalizedConnectedZoomGapMs = isFiniteNumber(field(editor, "connectedZoomGapMs"))
				? clamp(field(editor, "connectedZoomGapMs").doubleValue(), 0, 5000)
				: EditorDefaults.DEFAULT_CONNECTED_ZOOM_GAP_MS;
		double normalizedConnectedZoomDurationMs = isFiniteNumber(field(editor, "connectedZoomDurationMs"))
				? clamp(field(editor, "connectedZoomDurationMs").doubleValue(), 60, 4000)
				: EditorDefaults.DEFAULT_CONNECTED_ZOOM_DURATION_MS;

		List<ZoomRegion> normalizedZoomRegions = normalizeZoomRegions(field(editor, "zoomRegions"));
		List<TrimRegion> normalizedTrimRegions = normalizeTrimRegions(field(editor, "trimRegions"));
		List<ClipRegion> normalizedClipRegions = normalizeClipRegions(field(editor, "clipRegions"));

		// Migrate before applying editor state, so loaded history and the saved baseline both begin
		// with the same canonical sequence rather than recording a repair edit.
		List<ClipRegion> sequenceClips = ClipSequence.closeClipGaps(normalizedClipRegions);
		boolean sequenceChanged = false;
		for (int index = 0; index < sequenceClips.size(); index++) {
			if (sequenceClips.get(index) != normalizedClipRegions.get(index)) {
				sequenceChanged = true;
				break;
			}
		}

		String normalizedAutoFullTrackClipId = text(field(editor, "autoFullTrackClipId"));
		Long normalizedAutoFullTrackClipEndMs = isFiniteNumber(field(editor, "autoFullTrackClipEndMs"))
				? Math.round(field(editor, "autoFullTrackClipEndMs").doubleValue())
				: null;

		List<SpeedRegion> normalizedSpeedRegions = normalizeSpeedRegions(field(editor, "speedRegions"));
		List<AnnotationRegion> normalizedAnnotationRegions =
				normalizeAnnotationRegions(field(editor, "annotationRegions"));
		List<AudioRegion> normalizedAudioRegions = normalizeAudioRegions(field(editor, "audioRegions"));
		List<CaptionCue> normalizedAutoCaptions = normalizeAutoCaptions(field(editor, "autoCaptions"));
		AutoCaptionSettings normalizedAutoCaptionSettings =
				normalizeAutoCaptionSettings(field(editor, "autoCaptionSettings"));

		JsonNode cropNode = field(editor, "cropRegion");
		double rawCropX = isFiniteNumber(field(cropNode, "x")) ? field(cropNode, "x").doubleValue() : 0;
		double rawCropY = isFiniteNumber(field(cropNode, "y")) ? field(cropNode, "y").doubleValue() : 0;
		double rawCropWidth = isFiniteNumber(field(cropNode, "width"))
				? field(cropNode, "width").doubleValue() : 1;
		double rawCropHeight = isFiniteNumber(field(cropNode, "height"))
				? field(cropNode, "height").doubleValue() : 1;
		double cropX = clamp(rawCropX, 0, 1);
		double cropY = clamp(rawCropY, 0, 1);
		double cropWidth = clamp(rawCropWidth, 0.01, 1 - cropX);
		double cropHeight = clamp(rawCropHeight, 0.01, 1 - cropY);

		JsonNode webcamNode = field(editor, "webcam") != null && field(editor, "webcam").isObject()
				? field(editor, "webcam") : Json.nodeFactory().objectNode();
		String webcamSourcePath = text(field(webcamNode, "sourcePath"));
		Double legacyZoomScaleEffect = isFiniteNumber(field(webcamNode, "zoomScaleEffect"))
				? field(webcamNode, "zoomScaleEffect").doubleValue() : null;

		String normalizedCursorStyle = text(field(editor, "cursorStyle"));
		normalizedCursorStyle = normalizedCursorStyle != null && !normalizedCursorStyle.trim().isEmpty()
				? ("mono".equals(normalizedCursorStyle) ? "tahoe-inverted" : normalizedCursorStyle)
				: EditorDefaults.DEFAULT_CURSOR_STYLE;

		double motionCursorSize = isFiniteNumber(field(editor, "cursorSize"))
				? clamp(field(editor, "cursorSize").doubleValue(), 0.5, 10) : 2.5;
		double motionCursorSmoothing = isFiniteNumber(field(editor, "cursorSmoothing"))
				? clamp(field(editor, "cursorSmoothing").doubleValue(), 0, 2) : 0.67;
		double motionStiffness = isFiniteNumber(field(editor, "cursorSpringStiffnessMultiplier"))
				? clamp(field(editor, "cursorSpringStiffnessMultiplier").doubleValue(), 0.25, 3) : 1.35;
		double motionDamping = isFiniteNumber(field(editor, "cursorSpringDampingMultiplier"))
				? clamp(field(editor, "cursorSpringDampingMultiplier").doubleValue(), 0.25, 3) : 0.79;
		double motionMass = isFiniteNumber(field(editor, "cursorSpringMassMultiplier"))
				? clamp(field(editor, "cursorSpringMassMultiplier").doubleValue(), 0.25, 3) : 1.29;
		double motionClickBounce = isFiniteNumber(field(editor, "cursorClickBounce"))
				? clamp(field(editor, "cursorClickBounce").doubleValue(), 0, 5)
				: EditorDefaults.DEFAULT_CURSOR_CLICK_BOUNCE;
		double motionClickBounceDuration = isFiniteNumber(field(editor, "cursorClickBounceDuration"))
				? clamp(field(editor, "cursorClickBounceDuration").doubleValue(), 60, 500)
				: EditorDefaults.DEFAULT_CURSOR_CLICK_BOUNCE_DURATION;

		CursorMotionPresets.SelectionInput motionSelection = new CursorMotionPresets.SelectionInput(
				normalizedZoomInDurationMs, normalizedZoomOutDurationMs, motionCursorSize,
				motionCursorSmoothing, motionStiffness, motionDamping, motionMass, motionClickBounce,
				motionClickBounceDuration);
		CursorMotionPresets.CursorMotionPreset normalizedMotionPreset =
				CursorMotionPresets.preset(CursorMotionPresets.resolveCursorMotionPresetId(motionSelection));

		double normalizedCursorClickEffectScale = isFiniteNumber(field(editor, "cursorClickEffectScale"))
				? clamp(field(editor, "cursorClickEffectScale").doubleValue(), 0.5, 2)
				: EditorDefaults.DEFAULT_CURSOR_CLICK_EFFECT_SCALE;
		double normalizedCursorClickEffectOpacity = isFiniteNumber(field(editor, "cursorClickEffectOpacity"))
				? clamp(field(editor, "cursorClickEffectOpacity").doubleValue(), 0, 1)
				: EditorDefaults.DEFAULT_CURSOR_CLICK_EFFECT_OPACITY;
		double normalizedCursorClickEffectDurationMs =
				isFiniteNumber(field(editor, "cursorClickEffectDurationMs"))
						? clamp(field(editor, "cursorClickEffectDurationMs").doubleValue(), 120, 1200)
						: EditorDefaults.DEFAULT_CURSOR_CLICK_EFFECT_DURATION_MS;
		String normalizedCursorClickEffectColor = EditorDefaults.normalizeCursorClickEffectColor(
				text(field(editor, "cursorClickEffectColor")),
				EditorDefaults.DEFAULT_CURSOR_CLICK_EFFECT_COLOR);

		return new ProjectEditorState(
				text(field(editor, "wallpaper")) != null
						? text(field(editor, "wallpaper")) : EditorDefaults.DEFAULT_WALLPAPER_PATH,
				numberOr(field(editor, "shadowIntensity"), 0.67),
				normalizedBackgroundBlur,
				normalizedZoomMotionBlur,
				normalizedZoomMotionBlurTuning,
				booleanOr(field(editor, "connectZooms"), true),
				normalizedMotionPreset.zoomInDurationMs(),
				normalizedZoomInOverlapMs,
				normalizedMotionPreset.zoomOutDurationMs(),
				normalizedConnectedZoomGapMs,
				normalizedConnectedZoomDurationMs,
				ZoomTransitionEasing.normalize(text(field(editor, "zoomInEasing")),
						EditorDefaults.DEFAULT_ZOOM_IN_EASING),
				ZoomTransitionEasing.normalize(text(field(editor, "zoomOutEasing")),
						EditorDefaults.DEFAULT_ZOOM_OUT_EASING),
				ZoomTransitionEasing.normalize(text(field(editor, "connectedZoomEasing")),
						EditorDefaults.DEFAULT_CONNECTED_ZOOM_EASING),
				booleanOr(field(editor, "showCursor"), true),
				booleanOr(field(editor, "loopCursor"), false),
				normalizedCursorStyle,
				CursorClickEffectStyle.normalizeStyle(text(field(editor, "cursorClickEffect")),
						EditorDefaults.DEFAULT_CURSOR_CLICK_EFFECT),
				normalizedCursorClickEffectColor,
				normalizedCursorClickEffectScale,
				normalizedCursorClickEffectOpacity,
				normalizedCursorClickEffectDurationMs,
				normalizedMotionPreset.cursorSize(),
				normalizedMotionPreset.cursorSmoothing(),
				normalizedMotionPreset.cursorSpringStiffnessMultiplier(),
				normalizedMotionPreset.cursorSpringDampingMultiplier(),
				normalizedMotionPreset.cursorSpringMassMultiplier(),
				isFiniteNumber(field(editor, "cameraSpringStiffnessMultiplier"))
						? clamp(field(editor, "cameraSpringStiffnessMultiplier").doubleValue(), 0.25, 3) : 1,
				isFiniteNumber(field(editor, "cameraSpringDampingMultiplier"))
						? clamp(field(editor, "cameraSpringDampingMultiplier").doubleValue(), 0.25, 3) : 1.13,
				isFiniteNumber(field(editor, "cameraSpringMassMultiplier"))
						? clamp(field(editor, "cameraSpringMassMultiplier").doubleValue(), 0.25, 3) : 1.12,
				EditorDefaults.DEFAULT_ZOOM_SMOOTHNESS,
				booleanOr(field(editor, "zoomClassicMode"), false),
				EditorDefaults.DEFAULT_CURSOR_MOTION_BLUR,
				normalizedMotionPreset.cursorClickBounce(),
				normalizedMotionPreset.cursorClickBounceDuration(),
				isFiniteNumber(field(editor, "cursorSway"))
						? clamp(field(editor, "cursorSway").doubleValue(), 0, 2)
						: EditorDefaults.DEFAULT_CURSOR_SWAY,
				isFiniteNumber(field(editor, "borderRadius"))
						? clamp(field(editor, "borderRadius").doubleValue(), 0, 50)
						: getDefaultBorderRadiusPercent(),
				normalizePadding(field(editor, "padding")),
				new CropRegion(cropX, cropY, cropWidth, cropHeight),
				sequenceChanged
						? ClipSequence.rippleRegions(normalizedZoomRegions, normalizedClipRegions,
								sequenceClips, ClipSequence.ZOOM_SPAN)
						: normalizedZoomRegions,
				normalizedTrimRegions,
				sequenceClips,
				normalizedAutoFullTrackClipId,
				normalizedAutoFullTrackClipEndMs,
				normalizedSpeedRegions,
				sequenceChanged
						? ClipSequence.rippleRegions(normalizedAnnotationRegions, normalizedClipRegions,
								sequenceClips, ClipSequence.ANNOTATION_SPAN)
						: normalizedAnnotationRegions,
				sequenceChanged
						? ClipSequence.rippleRegionAnchors(normalizedAudioRegions, normalizedClipRegions,
								sequenceClips, ClipSequence.AUDIO_SPAN)
						: normalizedAudioRegions,
				normalizedAutoCaptions,
				normalizedAutoCaptionSettings,
				normalizeKeystrokeOverlaySettings(field(editor, "keystrokeOverlay")),
				normalizeWebcam(webcamNode, webcamSourcePath, legacyZoomScaleEffect),
				AspectRatios.isValidAspectRatio(text(field(editor, "aspectRatio")))
						? text(field(editor, "aspectRatio")) : "16:9",
				objectOrEmpty(field(editor, "sourceAudioTrackSettingsByClip")),
				objectOrEmpty(field(editor, "defaultSourceAudioTrackSettings")));
	}


	// ---------------------------------------------------------------------
	// Normalisers for the individual editor sections
	// ---------------------------------------------------------------------

	private static ZoomMotionBlurTuning normalizeZoomMotionBlurTuning(JsonNode rawTuning) {
		JsonNode tuning = rawTuning != null && rawTuning.isObject()
				? rawTuning : Json.nodeFactory().objectNode();
		ZoomMotionBlurTuning defaults = ZoomMotionBlurTuning.DEFAULT;
		return new ZoomMotionBlurTuning(
				isFiniteNumber(field(tuning, "panVelocityThreshold"))
						? clamp(field(tuning, "panVelocityThreshold").doubleValue(), 0, 240)
						: defaults.panVelocityThreshold(),
				isFiniteNumber(field(tuning, "zoomVelocityThreshold"))
						? clamp(field(tuning, "zoomVelocityThreshold").doubleValue(), 0, 0.4)
						: defaults.zoomVelocityThreshold(),
				isFiniteNumber(field(tuning, "maxDirectionalBlurPx"))
						? clamp(field(tuning, "maxDirectionalBlurPx").doubleValue(), 0, 96)
						: defaults.maxDirectionalBlurPx(),
				isFiniteNumber(field(tuning, "maxRadialBlurStrength"))
						? clamp(field(tuning, "maxRadialBlurStrength").doubleValue(), 0, 1.5)
						: defaults.maxRadialBlurStrength(),
				isFiniteNumber(field(tuning, "panResponsePerSecond"))
						? clamp(field(tuning, "panResponsePerSecond").doubleValue(), 1, 30)
						: defaults.panResponsePerSecond(),
				isFiniteNumber(field(tuning, "zoomResponsePerSecond"))
						? clamp(field(tuning, "zoomResponsePerSecond").doubleValue(), 1, 30)
						: defaults.zoomResponsePerSecond(),
				isFiniteNumber(field(tuning, "zoomSafeZoneRadiusPx"))
						? clamp(field(tuning, "zoomSafeZoneRadiusPx").doubleValue(), 0, 80)
						: defaults.zoomSafeZoneRadiusPx());
	}

	private static List<ZoomRegion> normalizeZoomRegions(JsonNode rawRegions) {
		if (rawRegions == null || !rawRegions.isArray()) {
			return List.of();
		}
		List<ZoomRegion> regions = new ArrayList<>();
		for (JsonNode region : rawRegions) {
			if (region == null || !region.isObject()) {
				continue;
			}
			String id = text(field(region, "id"));
			if (id == null) {
				continue;
			}
			Span span = normalizeSpan(region);
			JsonNode rawDepth = field(region, "depth");
			ZoomDepth depth = ZoomDepth.DEFAULT;
			if (rawDepth != null && rawDepth.isNumber()) {
				int value = rawDepth.intValue();
				if (value >= 1 && value <= 6) {
					depth = ZoomDepth.fromWire(value);
				}
			}
			JsonNode focus = field(region, "focus");
			double cx = clamp(isFiniteNumber(field(focus, "cx"))
					? field(focus, "cx").doubleValue() : 0.5, 0, 1);
			double cy = clamp(isFiniteNumber(field(focus, "cy"))
					? field(focus, "cy").doubleValue() : 0.5, 0, 1);
			String rawMode = text(field(region, "mode"));
			ZoomMode mode = "auto".equals(rawMode) ? ZoomMode.AUTO
					: "manual".equals(rawMode) ? ZoomMode.MANUAL : null;
			regions.add(new ZoomRegion(id, span.startMs(), span.endMs(), depth,
					new ZoomFocus(cx, cy), mode));
		}
		return List.copyOf(regions);
	}

	private static List<TrimRegion> normalizeTrimRegions(JsonNode rawRegions) {
		if (rawRegions == null || !rawRegions.isArray()) {
			return List.of();
		}
		List<TrimRegion> regions = new ArrayList<>();
		for (JsonNode region : rawRegions) {
			if (region == null || !region.isObject()) {
				continue;
			}
			String id = text(field(region, "id"));
			if (id == null) {
				continue;
			}
			Span span = normalizeSpan(region);
			regions.add(new TrimRegion(id, span.startMs(), span.endMs()));
		}
		return List.copyOf(regions);
	}

	private static @Nullable List<ClipRegion> normalizeClipRegions(JsonNode rawRegions) {
		if (rawRegions == null || !rawRegions.isArray()) {
			return List.of();
		}
		List<ClipRegion> regions = new ArrayList<>();
		for (JsonNode region : rawRegions) {
			if (region == null || !region.isObject()) {
				continue;
			}
			String id = text(field(region, "id"));
			if (id == null) {
				continue;
			}
			Span span = normalizeSpan(region);

			Long sourceStartMs = isFiniteNumber(field(region, "sourceStartMs"))
					? Math.max(0, Math.round(field(region, "sourceStartMs").doubleValue())) : null;
			Long sourceMinMs = isFiniteNumber(field(region, "sourceMinMs"))
					? Math.max(0, Math.round(field(region, "sourceMinMs").doubleValue())) : null;
			Long sourceMaxMs = isFiniteNumber(field(region, "sourceMaxMs"))
					? Math.max(0, Math.round(field(region, "sourceMaxMs").doubleValue())) : null;
			if (sourceMaxMs != null
					&& sourceMaxMs < Math.max(sourceMinMs == null ? 0 : sourceMinMs,
							sourceStartMs == null ? span.startMs() : sourceStartMs)) {
				sourceMinMs = null;
				sourceMaxMs = null;
			}

			double speed = isFiniteNumber(field(region, "speed"))
					&& field(region, "speed").doubleValue() > 0
							? field(region, "speed").doubleValue() : 1;
			boolean muted = Boolean.TRUE.equals(bool(field(region, "muted")));
			boolean showSourceAudio = Boolean.TRUE.equals(bool(field(region, "showSourceAudio")));

			ClipCameraLayout cameraLayout =
					ClipCameraLayout.fromWireOrNull(text(field(region, "cameraLayout")));
			if (cameraLayout == ClipCameraLayout.DEFAULT) {
				cameraLayout = null;
			}

			regions.add(new ClipRegion(id, span.startMs(), span.endMs(), sourceStartMs, sourceMinMs,
					sourceMaxMs, speed, muted, showSourceAudio, cameraLayout,
					normalizeClipTransition(field(region, "transitionOut"))));
		}
		return List.copyOf(regions);
	}

	private static @Nullable ClipTransition normalizeClipTransition(JsonNode value) {
		if (value == null || !value.isObject()) {
			return null;
		}
		String kind = text(field(value, "kind"));
		if (!"fade-black".equals(kind) || !isFiniteNumber(field(value, "durationMs"))) {
			return null;
		}
		long durationMs = Math.round(clamp(field(value, "durationMs").doubleValue(),
				ClipTransition.MIN_CLIP_TRANSITION_MS, ClipTransition.MAX_CLIP_TRANSITION_MS));
		return new ClipTransition(ClipTransitionKind.FADE_BLACK, durationMs);
	}

	private static List<SpeedRegion> normalizeSpeedRegions(JsonNode rawRegions) {
		if (rawRegions == null || !rawRegions.isArray()) {
			return List.of();
		}
		List<SpeedRegion> regions = new ArrayList<>();
		for (JsonNode region : rawRegions) {
			if (region == null || !region.isObject()) {
				continue;
			}
			String id = text(field(region, "id"));
			if (id == null) {
				continue;
			}
			Span span = normalizeSpan(region);
			double speed = SpeedRegion.DEFAULT_PLAYBACK_SPEED;
			if (isFiniteNumber(field(region, "speed"))) {
				double rawSpeed = field(region, "speed").doubleValue();
				for (double allowed : PLAYBACK_SPEEDS) {
					if (rawSpeed == allowed) {
						speed = rawSpeed;
						break;
					}
				}
			}
			regions.add(new SpeedRegion(id, span.startMs(), span.endMs(), speed));
		}
		return List.copyOf(regions);
	}

	private static List<AnnotationRegion> normalizeAnnotationRegions(JsonNode rawRegions) {
		if (rawRegions == null || !rawRegions.isArray()) {
			return List.of();
		}
		List<AnnotationRegion> regions = new ArrayList<>();
		int index = 0;
		for (JsonNode region : rawRegions) {
			if (region == null || !region.isObject()) {
				continue;
			}
			String id = text(field(region, "id"));
			if (id == null) {
				continue;
			}
			Span span = normalizeSpan(region);
			String rawType = text(field(region, "type"));
			AnnotationType type = "image".equals(rawType) ? AnnotationType.IMAGE
					: "figure".equals(rawType) ? AnnotationType.FIGURE
					: "blur".equals(rawType) ? AnnotationType.BLUR : AnnotationType.TEXT;

			JsonNode positionNode = field(region, "position");
			double positionX = isFiniteNumber(field(positionNode, "x"))
					? field(positionNode, "x").doubleValue() : AnnotationPosition.DEFAULT.x();
			double positionY = isFiniteNumber(field(positionNode, "y"))
					? field(positionNode, "y").doubleValue() : AnnotationPosition.DEFAULT.y();
			JsonNode sizeNode = field(region, "size");
			double width = clamp(isFiniteNumber(field(sizeNode, "width"))
					? field(sizeNode, "width").doubleValue() : AnnotationSize.DEFAULT.width(), 1, 10000);
			double height = clamp(isFiniteNumber(field(sizeNode, "height"))
					? field(sizeNode, "height").doubleValue() : AnnotationSize.DEFAULT.height(), 1, 10000);

			regions.add(new AnnotationRegion(
					id, span.startMs(), span.endMs(), type,
					text(field(region, "content")) != null ? text(field(region, "content")) : "",
					text(field(region, "textContent")),
					text(field(region, "imageContent")),
					new AnnotationPosition(positionX, positionY),
					new AnnotationSize(width, height),
					normalizeAnnotationStyle(field(region, "style")),
					isFiniteNumber(field(region, "zIndex"))
							? (int) field(region, "zIndex").doubleValue() : index + 1,
					isFiniteNumber(field(region, "trackIndex"))
							? Math.max(0, (int) Math.floor(field(region, "trackIndex").doubleValue())) : 0,
					normalizeFigureData(field(region, "figureData")),
					isFiniteNumber(field(region, "blurIntensity"))
							? clamp(field(region, "blurIntensity").doubleValue(), 1, 100) : 20,
					text(field(region, "blurColor"))));
			index++;
		}
		return List.copyOf(regions);
	}

	private static AnnotationTextStyle normalizeAnnotationStyle(JsonNode rawStyle) {
		AnnotationTextStyle defaults = AnnotationTextStyle.defaultStyle();
		if (rawStyle == null || !rawStyle.isObject()) {
			return defaults;
		}
		return new AnnotationTextStyle(
				text(field(rawStyle, "color")) != null
						? text(field(rawStyle, "color")) : defaults.color(),
				text(field(rawStyle, "backgroundColor")) != null
						? text(field(rawStyle, "backgroundColor")) : defaults.backgroundColor(),
				isFiniteNumber(field(rawStyle, "fontSize"))
						? field(rawStyle, "fontSize").doubleValue() : defaults.fontSize(),
				text(field(rawStyle, "fontFamily")) != null
						? text(field(rawStyle, "fontFamily")) : defaults.fontFamily(),
				AnnotationTextStyle.FontWeight.fromWire(text(field(rawStyle, "fontWeight"))),
				AnnotationTextStyle.FontStyle.fromWire(text(field(rawStyle, "fontStyle"))),
				AnnotationTextStyle.TextDecoration.fromWire(text(field(rawStyle, "textDecoration"))),
				AnnotationTextStyle.TextAlign.fromWire(text(field(rawStyle, "textAlign"))),
				isFiniteNumber(field(rawStyle, "borderRadius"))
						? field(rawStyle, "borderRadius").doubleValue() : defaults.borderRadius());
	}

	private static @Nullable FigureData normalizeFigureData(JsonNode rawFigureData) {
		if (rawFigureData == null || !rawFigureData.isObject()) {
			return null;
		}
		FigureData defaults = FigureData.DEFAULT;
		String rawDirection = text(field(rawFigureData, "arrowDirection"));
		ArrowDirection direction = defaults.arrowDirection();
		if (rawDirection != null) {
			try {
				direction = ArrowDirection.fromWire(rawDirection);
			} catch (IllegalArgumentException ignored) {
				direction = defaults.arrowDirection();
			}
		}
		return new FigureData(
				direction,
				text(field(rawFigureData, "color")) != null
						? text(field(rawFigureData, "color")) : defaults.color(),
				isFiniteNumber(field(rawFigureData, "strokeWidth"))
						? field(rawFigureData, "strokeWidth").doubleValue() : defaults.strokeWidth());
	}

	private static List<AudioRegion> normalizeAudioRegions(JsonNode rawRegions) {
		if (rawRegions == null || !rawRegions.isArray()) {
			return List.of();
		}
		List<AudioRegion> regions = new ArrayList<>();
		for (JsonNode region : rawRegions) {
			if (region == null || !region.isObject()) {
				continue;
			}
			String id = text(field(region, "id"));
			if (id == null) {
				continue;
			}
			Span span = normalizeSpan(region);
			regions.add(new AudioRegion(
					id, span.startMs(), span.endMs(),
					text(field(region, "audioPath")) != null
							? text(field(region, "audioPath")) : "",
					isFiniteNumber(field(region, "volume"))
							? clamp(field(region, "volume").doubleValue(), 0, 1) : 1,
					truthy(field(region, "normalize")),
					isFiniteNumber(field(region, "trackIndex"))
							? Math.max(0, (int) Math.floor(field(region, "trackIndex").doubleValue())) : 0));
		}
		return List.copyOf(regions);
	}

	private static List<CaptionCue> normalizeAutoCaptions(JsonNode rawCues) {
		if (rawCues == null || !rawCues.isArray()) {
			return List.of();
		}
		List<CaptionCue> cues = new ArrayList<>();
		for (JsonNode cue : rawCues) {
			if (cue == null || !cue.isObject()) {
				continue;
			}
			String id = text(field(cue, "id"));
			if (id == null) {
				continue;
			}
			Span span = normalizeSpan(cue);

			List<CaptionCueWord> words = null;
			JsonNode rawWords = field(cue, "words");
			if (rawWords != null && rawWords.isArray()) {
				List<CaptionCueWord> normalizedWords = new ArrayList<>();
				for (JsonNode word : rawWords) {
					if (word == null || !word.isObject()) {
						continue;
					}
					String wordText = text(field(word, "text"));
					if (wordText == null) {
						continue;
					}
					long rawWordStart = isFiniteNumber(field(word, "startMs"))
							? Math.round(field(word, "startMs").doubleValue()) : span.startMs();
					long rawWordEnd = isFiniteNumber(field(word, "endMs"))
							? Math.round(field(word, "endMs").doubleValue()) : rawWordStart + 1;
					long wordStart = (long) clamp(rawWordStart, span.startMs(), span.endMs() - 1);
					long wordEnd = (long) clamp(rawWordEnd, wordStart + 1, span.endMs());
					String text = wordText.trim();
					if (text.isEmpty()) {
						continue;
					}
					normalizedWords.add(new CaptionCueWord(text, wordStart, wordEnd,
							truthy(field(word, "leadingSpace")) ? Boolean.TRUE : null));
				}
				if (!normalizedWords.isEmpty()) {
					words = List.copyOf(normalizedWords);
				}
			}

			String text = text(field(cue, "text")) != null ? text(field(cue, "text")).trim() : "";
			if (text.isEmpty()) {
				continue;
			}
			cues.add(new CaptionCue(id, span.startMs(), span.endMs(), text, words));
		}
		return List.copyOf(cues);
	}

	private static AutoCaptionSettings normalizeAutoCaptionSettings(JsonNode rawSettings) {
		JsonNode settings = rawSettings != null && rawSettings.isObject()
				? rawSettings : Json.nodeFactory().objectNode();
		AutoCaptionSettings defaults = AutoCaptionSettings.defaults();
		String language = text(field(settings, "language"));
		String textColor = text(field(settings, "textColor"));
		String inactiveTextColor = text(field(settings, "inactiveTextColor"));
		return new AutoCaptionSettings(
				booleanOr(field(settings, "enabled"), defaults.enabled()),
				booleanOr(field(settings, "timelineQuickAdd"), defaults.timelineQuickAdd()),
				language != null && !language.trim().isEmpty() ? language.trim() : defaults.language(),
				AutoCaptionSettings.defaultCaptionFontFamily(),
				isFiniteNumber(field(settings, "fontSize"))
						? clamp(field(settings, "fontSize").doubleValue(), 16, 72) : defaults.fontSize(),
				isFiniteNumber(field(settings, "bottomOffset"))
						? clamp(field(settings, "bottomOffset").doubleValue(), 0, 30)
						: defaults.bottomOffset(),
				isFiniteNumber(field(settings, "maxWidth"))
						? clamp(field(settings, "maxWidth").doubleValue(), 40, 95) : defaults.maxWidth(),
				isFiniteNumber(field(settings, "maxRows"))
						? (int) clamp(Math.round(field(settings, "maxRows").doubleValue()), 1, 4)
						: defaults.maxRows(),
				AutoCaptionAnimation.fromWire(text(field(settings, "animationStyle"))),
				isFiniteNumber(field(settings, "boxRadius"))
						? clamp(field(settings, "boxRadius").doubleValue(), 0, 40) : defaults.boxRadius(),
				textColor != null && !textColor.trim().isEmpty() ? textColor : defaults.textColor(),
				inactiveTextColor != null && !inactiveTextColor.trim().isEmpty()
						? inactiveTextColor : defaults.inactiveTextColor(),
				isFiniteNumber(field(settings, "backgroundOpacity"))
						? clamp(field(settings, "backgroundOpacity").doubleValue(), 0, 1)
						: defaults.backgroundOpacity());
	}

	private static Padding normalizePadding(JsonNode rawPadding) {
		if (rawPadding != null && rawPadding.isObject()) {
			boolean linked = booleanOr(field(rawPadding, "linked"), true);
			double verticalMax = linked ? 100 : Padding.ADVANCED_VERTICAL_PADDING_MAX;
			double top = isFiniteNumber(field(rawPadding, "top"))
					? clamp(field(rawPadding, "top").doubleValue(), 0, verticalMax)
					: Padding.DEFAULT.top();
			if (linked) {
				return new Padding(top, top, top, top, true);
			}
			return new Padding(
					top,
					isFiniteNumber(field(rawPadding, "bottom"))
							? clamp(field(rawPadding, "bottom").doubleValue(), 0, verticalMax)
							: Padding.DEFAULT.bottom(),
					isFiniteNumber(field(rawPadding, "left"))
							? clamp(field(rawPadding, "left").doubleValue(), 0, 100)
							: Padding.DEFAULT.left(),
					isFiniteNumber(field(rawPadding, "right"))
							? clamp(field(rawPadding, "right").doubleValue(), 0, 100)
							: Padding.DEFAULT.right(),
					false);
		}
		if (rawPadding != null && rawPadding.isNumber() && isFiniteNumber(rawPadding)) {
			double value = clamp(rawPadding.doubleValue(), 0, 100);
			return new Padding(value, value, value, value, true);
		}
		return Padding.DEFAULT;
	}

	private static KeystrokeOverlaySettings normalizeKeystrokeOverlaySettings(JsonNode rawSettings) {
		KeystrokeOverlaySettings defaults = KeystrokeOverlaySettings.DEFAULT;
		JsonNode settings = rawSettings != null && rawSettings.isObject()
				? rawSettings : Json.nodeFactory().objectNode();
		KeystrokeOverlayPosition position =
				KeystrokeOverlayPosition.fromWireOrNull(text(field(settings, "position")));
		return new KeystrokeOverlaySettings(
				booleanOr(field(settings, "enabled"), defaults.enabled()),
				position != null ? position : defaults.position(),
				clampSetting(field(settings, "fontSize"), 12, 96, defaults.fontSize()),
				Math.round(clampSetting(field(settings, "lingerMs"), 200, 5000, defaults.lingerMs())),
				Math.round(clampSetting(field(settings, "fadeMs"), 0, 1000, defaults.fadeMs())),
				Math.round(clampSetting(field(settings, "groupingMs"), 0, 3000, defaults.groupingMs())),
				clampSetting(field(settings, "bottomOffset"), 0, 90, defaults.bottomOffset()));
	}

	private static WebcamOverlaySettings normalizeWebcam(
			JsonNode webcamNode, @Nullable String sourcePath, @Nullable Double legacyZoomScaleEffect) {
		WebcamOverlaySettings defaults = WebcamOverlaySettings.defaults();

		List<WebcamOverlaySettings.TimeRange> visibleRanges = null;
		JsonNode rawVisibleRanges = field(webcamNode, "visibleRanges");
		if (rawVisibleRanges != null && rawVisibleRanges.isArray()) {
			List<WebcamOverlaySettings.TimeRange> ranges = new ArrayList<>();
			for (JsonNode range : rawVisibleRanges) {
				if (range == null || !range.isObject()) {
					continue;
				}
				Double startMs = finite(field(range, "startMs"));
				Double endMs = finite(field(range, "endMs"));
				if (startMs != null && endMs != null && startMs >= 0 && endMs > startMs) {
					ranges.add(new WebcamOverlaySettings.TimeRange(
							Math.round(startMs), Math.round(endMs)));
				}
			}
			visibleRanges = List.copyOf(ranges);
		}

		WebcamPositionPreset positionPreset =
				WebcamPositionPreset.fromWireOrNull(text(field(webcamNode, "positionPreset")));
		if (positionPreset == null) {
			WebcamPositionPreset cornerPreset =
					WebcamPositionPreset.fromWireOrNull(text(field(webcamNode, "corner")));
			positionPreset = cornerPreset != null
					? cornerPreset : WebcamOverlaySettings.DEFAULT_WEBCAM_POSITION_PRESET;
		}
		WebcamCorner corner = WebcamCorner.fromWireOrNull(text(field(webcamNode, "corner")));
		if (corner == null) {
			corner = defaults.corner();
		}

		Double rawSize = finite(field(webcamNode, "size"));
		Double rawWidth = finite(field(webcamNode, "width"));
		Double rawHeight = finite(field(webcamNode, "height"));
		double widthPercent = rawWidth != null ? clamp(rawWidth, 10, 100)
				: rawSize != null ? clamp(rawSize, 10, 100)
						: WebcamOverlaySettings.DEFAULT_WEBCAM_SIZE;
		double heightPercent = rawHeight != null ? clamp(rawHeight, 10, 100)
				: rawSize != null ? clamp(rawSize, 10, 100)
						: WebcamOverlaySettings.DEFAULT_WEBCAM_SIZE;

		Double rawCornerRadius = finite(field(webcamNode, "cornerRadius"));
		double roundness = isFiniteNumber(field(webcamNode, "roundness"))
				? clamp(field(webcamNode, "roundness").doubleValue(), 0, 100)
				: rawCornerRadius != null
						? WebcamOverlay.convertLegacyWebcamRadiusToRoundness(
								rawCornerRadius, widthPercent, heightPercent)
						: WebcamOverlaySettings.DEFAULT_WEBCAM_ROUNDNESS;

		Boolean reactToZoom = bool(field(webcamNode, "reactToZoom"));
		boolean normalizedReactToZoom = reactToZoom != null
				? reactToZoom
				: legacyZoomScaleEffect != null
						? legacyZoomScaleEffect > 0
						: WebcamOverlaySettings.DEFAULT_WEBCAM_REACT_TO_ZOOM;

		return new WebcamOverlaySettings(
				visibleRanges,
				booleanOr(field(webcamNode, "enabled"), defaults.enabled()),
				sourcePath,
				isFiniteNumber(field(webcamNode, "timeOffsetMs"))
						? Math.round(field(webcamNode, "timeOffsetMs").doubleValue())
						: WebcamOverlaySettings.DEFAULT_WEBCAM_TIME_OFFSET_MS,
				booleanOr(field(webcamNode, "mirror"), defaults.mirror()),
				WebcamOverlay.normalizeWebcamCropRegion(field(webcamNode, "cropRegion")),
				corner,
				positionPreset,
				isFiniteNumber(field(webcamNode, "positionX"))
						? clamp(field(webcamNode, "positionX").doubleValue(), 0, 1)
						: WebcamOverlaySettings.DEFAULT_WEBCAM_POSITION_X,
				isFiniteNumber(field(webcamNode, "positionY"))
						? clamp(field(webcamNode, "positionY").doubleValue(), 0, 1)
						: WebcamOverlaySettings.DEFAULT_WEBCAM_POSITION_Y,
				rawSize != null ? clamp(rawSize, 10, 100) : WebcamOverlaySettings.DEFAULT_WEBCAM_SIZE,
				widthPercent,
				heightPercent,
				normalizedReactToZoom,
				roundness,
				null,
				isFiniteNumber(field(webcamNode, "shadow"))
						? clamp(field(webcamNode, "shadow").doubleValue(), 0, 1)
						: WebcamOverlaySettings.DEFAULT_WEBCAM_SHADOW,
				isFiniteNumber(field(webcamNode, "margin"))
						? clamp(field(webcamNode, "margin").doubleValue(), 0, 96)
						: WebcamOverlaySettings.DEFAULT_WEBCAM_MARGIN);
	}

	// ---------------------------------------------------------------------
	// Node helpers
	// ---------------------------------------------------------------------

	private record Span(long startMs, long endMs) {}

	private static Span normalizeSpan(JsonNode region) {
		long rawStart = isFiniteNumber(field(region, "startMs"))
				? Math.round(field(region, "startMs").doubleValue()) : 0;
		long rawEnd = isFiniteNumber(field(region, "endMs"))
				? Math.round(field(region, "endMs").doubleValue()) : rawStart + 1000;
		long startMs = Math.max(0, Math.min(rawStart, rawEnd));
		long endMs = Math.max(startMs + 1, rawEnd);
		return new Span(startMs, endMs);
	}

	private static @Nullable JsonNode field(JsonNode node, String name) {
		return node != null && node.isObject() ? node.get(name) : null;
	}

	private static boolean isFiniteNumber(JsonNode value) {
		return value != null && value.isNumber() && Double.isFinite(value.doubleValue());
	}

	private static @Nullable Double finite(JsonNode value) {
		return isFiniteNumber(value) ? value.doubleValue() : null;
	}

	private static @Nullable String text(JsonNode value) {
		return value != null && value.isString() ? value.asString() : null;
	}

	private static @Nullable Boolean bool(JsonNode value) {
		return value != null && value.isBoolean() ? value.booleanValue() : null;
	}

	private static boolean booleanOr(JsonNode value, boolean fallback) {
		Boolean parsed = bool(value);
		return parsed != null ? parsed : fallback;
	}

	private static double numberOr(JsonNode value, double fallback) {
		return isFiniteNumber(value) ? value.doubleValue() : fallback;
	}

	private static ObjectNode objectOrEmpty(JsonNode value) {
		return value != null && value.isObject()
				? (ObjectNode) value : Json.nodeFactory().objectNode();
	}

	private static boolean truthy(JsonNode value) {
		if (value == null || value.isNull() || value.isMissingNode()) {
			return false;
		}
		if (value.isBoolean()) {
			return value.booleanValue();
		}
		if (value.isNumber()) {
			return value.doubleValue() != 0;
		}
		if (value.isString()) {
			return !value.asString().isEmpty();
		}
		return value.isArray() || value.isObject() ? value.size() > 0 : true;
	}

	private static double clampSetting(JsonNode value, double min, double max, double fallback) {
		if (!isFiniteNumber(value)) {
			return fallback;
		}
		return Math.min(max, Math.max(min, value.doubleValue()));
	}

	private static double clamp(double value, double min, double max) {
		return Math.min(max, Math.max(min, value));
	}
}
