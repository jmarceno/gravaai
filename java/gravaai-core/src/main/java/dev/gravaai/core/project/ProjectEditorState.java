package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.util.List;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.node.ObjectNode;

/**
 * The editor state persisted inside a project file (port of {@code ProjectEditorState} in
 * {@code projectPersistence.ts}, plan §5.11). Field order and names match the TS interface; optional
 * fields are nullable components and are filled by {@code ProjectPersistence.normalizeProjectEditor}.
 *
 * <p>{@code sourceAudioTrackSettingsByClip} and {@code defaultSourceAudioTrackSettings} are kept as
 * raw JSON objects because the TS normaliser passes them through untouched (they are per-source
 * maps whose inner shape belongs to the audio layer).
 */
public record ProjectEditorState(
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
		CursorClickEffectStyle cursorClickEffect,
		String cursorClickEffectColor,
		double cursorClickEffectScale,
		double cursorClickEffectOpacity,
		double cursorClickEffectDurationMs,
		double cursorSize,
		double cursorSmoothing,
		double cursorSpringStiffnessMultiplier,
		double cursorSpringDampingMultiplier,
		double cursorSpringMassMultiplier,
		double cameraSpringStiffnessMultiplier,
		double cameraSpringDampingMultiplier,
		double cameraSpringMassMultiplier,
		double zoomSmoothness,
		boolean zoomClassicMode,
		double cursorMotionBlur,
		double cursorClickBounce,
		double cursorClickBounceDuration,
		double cursorSway,
		double borderRadius,
		Padding padding,
		CropRegion cropRegion,
		List<ZoomRegion> zoomRegions,
		List<TrimRegion> trimRegions,
		List<ClipRegion> clipRegions,
		@Nullable String autoFullTrackClipId,
		@Nullable Long autoFullTrackClipEndMs,
		List<SpeedRegion> speedRegions,
		List<AnnotationRegion> annotationRegions,
		List<AudioRegion> audioRegions,
		List<CaptionCue> autoCaptions,
		AutoCaptionSettings autoCaptionSettings,
		KeystrokeOverlaySettings keystrokeOverlay,
		WebcamOverlaySettings webcam,
		String aspectRatio,
		ObjectNode sourceAudioTrackSettingsByClip,
		ObjectNode defaultSourceAudioTrackSettings,
		List<KeystrokeElement> keystrokeElements) {

	/**
	 * Copy with a new caption list, without re-normalising the rest of the state. The editor needs
	 * this for freshly added cues whose text is still empty: the project normaliser drops empty
	 * cues when loading, but the editor must keep them while the user types (same as the TS state).
	 */
	public ProjectEditorState withAutoCaptions(List<CaptionCue> nextAutoCaptions) {
		return new ProjectEditorState(wallpaper, shadowIntensity, backgroundBlur, zoomMotionBlur,
				zoomMotionBlurTuning, connectZooms, zoomInDurationMs, zoomInOverlapMs,
				zoomOutDurationMs, connectedZoomGapMs, connectedZoomDurationMs, zoomInEasing,
				zoomOutEasing, connectedZoomEasing, showCursor, loopCursor, cursorStyle,
				cursorClickEffect, cursorClickEffectColor, cursorClickEffectScale,
				cursorClickEffectOpacity, cursorClickEffectDurationMs, cursorSize, cursorSmoothing,
				cursorSpringStiffnessMultiplier, cursorSpringDampingMultiplier,
				cursorSpringMassMultiplier, cameraSpringStiffnessMultiplier,
				cameraSpringDampingMultiplier, cameraSpringMassMultiplier, zoomSmoothness,
				zoomClassicMode, cursorMotionBlur, cursorClickBounce, cursorClickBounceDuration,
				cursorSway, borderRadius, padding, cropRegion, zoomRegions, trimRegions,
				clipRegions, autoFullTrackClipId, autoFullTrackClipEndMs, speedRegions,
				annotationRegions, audioRegions, nextAutoCaptions, autoCaptionSettings,
				keystrokeOverlay, webcam, aspectRatio, sourceAudioTrackSettingsByClip,
				defaultSourceAudioTrackSettings, keystrokeElements);
	}

	/**
	 * Copy with a new raw source-audio settings map (unknown keys preserved). Absence is modelled as
	 * an empty object, matching {@code ProjectPersistence.objectOrEmpty}.
	 */
	public ProjectEditorState withSourceAudioTrackSettingsByClip(
			@Nullable ObjectNode nextSourceAudioTrackSettings) {
		ObjectNode normalized = nextSourceAudioTrackSettings != null
				? nextSourceAudioTrackSettings : Json.nodeFactory().objectNode();
		return new ProjectEditorState(wallpaper, shadowIntensity, backgroundBlur, zoomMotionBlur,
				zoomMotionBlurTuning, connectZooms, zoomInDurationMs, zoomInOverlapMs,
				zoomOutDurationMs, connectedZoomGapMs, connectedZoomDurationMs, zoomInEasing,
				zoomOutEasing, connectedZoomEasing, showCursor, loopCursor, cursorStyle,
				cursorClickEffect, cursorClickEffectColor, cursorClickEffectScale,
				cursorClickEffectOpacity, cursorClickEffectDurationMs, cursorSize, cursorSmoothing,
				cursorSpringStiffnessMultiplier, cursorSpringDampingMultiplier,
				cursorSpringMassMultiplier, cameraSpringStiffnessMultiplier,
				cameraSpringDampingMultiplier, cameraSpringMassMultiplier, zoomSmoothness,
				zoomClassicMode, cursorMotionBlur, cursorClickBounce, cursorClickBounceDuration,
				cursorSway, borderRadius, padding, cropRegion, zoomRegions, trimRegions,
				clipRegions, autoFullTrackClipId, autoFullTrackClipEndMs, speedRegions,
				annotationRegions, audioRegions, autoCaptions, autoCaptionSettings,
				keystrokeOverlay, webcam, aspectRatio, normalized,
				defaultSourceAudioTrackSettings, keystrokeElements);
	}

	/** Typed view of {@link #sourceAudioTrackSettingsByClip()} (raw JSON stays canonical). */
	public java.util.Map<String, SourceAudioTrackSettings> sourceAudioTrackSettings() {
		return SourceAudioSettings.parseAll(sourceAudioTrackSettingsByClip);
	}

	/** Typed view of {@link #defaultSourceAudioTrackSettings()} (raw JSON stays canonical). */
	public SourceAudioTrackSettings defaultSourceAudioTrackSettingsTyped() {
		return SourceAudioSettings.parse(defaultSourceAudioTrackSettings);
	}

	/** A copy of this state to change some fields; {@link Builder#build()} makes the new record. */
	public Builder toBuilder() {
		return new Builder(this);
	}

	/** Typed field-by-field edits of a {@link ProjectEditorState}. */
	public static final class Builder {

		private @Nullable String wallpaper;
		private double shadowIntensity;
		private double backgroundBlur;
		private double zoomMotionBlur;
		private ZoomMotionBlurTuning zoomMotionBlurTuning;
		private boolean connectZooms;
		private double zoomInDurationMs;
		private double zoomInOverlapMs;
		private double zoomOutDurationMs;
		private double connectedZoomGapMs;
		private double connectedZoomDurationMs;
		private ZoomTransitionEasing zoomInEasing;
		private ZoomTransitionEasing zoomOutEasing;
		private ZoomTransitionEasing connectedZoomEasing;
		private boolean showCursor;
		private boolean loopCursor;
		private String cursorStyle;
		private CursorClickEffectStyle cursorClickEffect;
		private String cursorClickEffectColor;
		private double cursorClickEffectScale;
		private double cursorClickEffectOpacity;
		private double cursorClickEffectDurationMs;
		private double cursorSize;
		private double cursorSmoothing;
		private double cursorSpringStiffnessMultiplier;
		private double cursorSpringDampingMultiplier;
		private double cursorSpringMassMultiplier;
		private double cameraSpringStiffnessMultiplier;
		private double cameraSpringDampingMultiplier;
		private double cameraSpringMassMultiplier;
		private double zoomSmoothness;
		private boolean zoomClassicMode;
		private double cursorMotionBlur;
		private double cursorClickBounce;
		private double cursorClickBounceDuration;
		private double cursorSway;
		private double borderRadius;
		private Padding padding;
		private CropRegion cropRegion;
		private List<ZoomRegion> zoomRegions;
		private List<TrimRegion> trimRegions;
		private List<ClipRegion> clipRegions;
		private @Nullable String autoFullTrackClipId;
		private @Nullable Long autoFullTrackClipEndMs;
		private List<SpeedRegion> speedRegions;
		private List<AnnotationRegion> annotationRegions;
		private List<AudioRegion> audioRegions;
		private List<CaptionCue> autoCaptions;
		private AutoCaptionSettings autoCaptionSettings;
		private KeystrokeOverlaySettings keystrokeOverlay;
		private WebcamOverlaySettings webcam;
		private String aspectRatio;
		private ObjectNode sourceAudioTrackSettingsByClip;
		private ObjectNode defaultSourceAudioTrackSettings;
		private List<KeystrokeElement> keystrokeElements;

		private Builder(ProjectEditorState state) {
			wallpaper = state.wallpaper;
			shadowIntensity = state.shadowIntensity;
			backgroundBlur = state.backgroundBlur;
			zoomMotionBlur = state.zoomMotionBlur;
			zoomMotionBlurTuning = state.zoomMotionBlurTuning;
			connectZooms = state.connectZooms;
			zoomInDurationMs = state.zoomInDurationMs;
			zoomInOverlapMs = state.zoomInOverlapMs;
			zoomOutDurationMs = state.zoomOutDurationMs;
			connectedZoomGapMs = state.connectedZoomGapMs;
			connectedZoomDurationMs = state.connectedZoomDurationMs;
			zoomInEasing = state.zoomInEasing;
			zoomOutEasing = state.zoomOutEasing;
			connectedZoomEasing = state.connectedZoomEasing;
			showCursor = state.showCursor;
			loopCursor = state.loopCursor;
			cursorStyle = state.cursorStyle;
			cursorClickEffect = state.cursorClickEffect;
			cursorClickEffectColor = state.cursorClickEffectColor;
			cursorClickEffectScale = state.cursorClickEffectScale;
			cursorClickEffectOpacity = state.cursorClickEffectOpacity;
			cursorClickEffectDurationMs = state.cursorClickEffectDurationMs;
			cursorSize = state.cursorSize;
			cursorSmoothing = state.cursorSmoothing;
			cursorSpringStiffnessMultiplier = state.cursorSpringStiffnessMultiplier;
			cursorSpringDampingMultiplier = state.cursorSpringDampingMultiplier;
			cursorSpringMassMultiplier = state.cursorSpringMassMultiplier;
			cameraSpringStiffnessMultiplier = state.cameraSpringStiffnessMultiplier;
			cameraSpringDampingMultiplier = state.cameraSpringDampingMultiplier;
			cameraSpringMassMultiplier = state.cameraSpringMassMultiplier;
			zoomSmoothness = state.zoomSmoothness;
			zoomClassicMode = state.zoomClassicMode;
			cursorMotionBlur = state.cursorMotionBlur;
			cursorClickBounce = state.cursorClickBounce;
			cursorClickBounceDuration = state.cursorClickBounceDuration;
			cursorSway = state.cursorSway;
			borderRadius = state.borderRadius;
			padding = state.padding;
			cropRegion = state.cropRegion;
			zoomRegions = state.zoomRegions;
			trimRegions = state.trimRegions;
			clipRegions = state.clipRegions;
			autoFullTrackClipId = state.autoFullTrackClipId;
			autoFullTrackClipEndMs = state.autoFullTrackClipEndMs;
			speedRegions = state.speedRegions;
			annotationRegions = state.annotationRegions;
			audioRegions = state.audioRegions;
			autoCaptions = state.autoCaptions;
			autoCaptionSettings = state.autoCaptionSettings;
			keystrokeOverlay = state.keystrokeOverlay;
			webcam = state.webcam;
			aspectRatio = state.aspectRatio;
			sourceAudioTrackSettingsByClip = state.sourceAudioTrackSettingsByClip;
			defaultSourceAudioTrackSettings = state.defaultSourceAudioTrackSettings;
			keystrokeElements = state.keystrokeElements;
		}

		public Builder wallpaper(String value) {
			wallpaper = value;
			return this;
		}

		public Builder shadowIntensity(double value) {
			shadowIntensity = value;
			return this;
		}

		public Builder backgroundBlur(double value) {
			backgroundBlur = value;
			return this;
		}

		public Builder zoomMotionBlur(double value) {
			zoomMotionBlur = value;
			return this;
		}

		public Builder zoomMotionBlurTuning(ZoomMotionBlurTuning value) {
			zoomMotionBlurTuning = value;
			return this;
		}

		public Builder connectZooms(boolean value) {
			connectZooms = value;
			return this;
		}

		public Builder zoomInDurationMs(double value) {
			zoomInDurationMs = value;
			return this;
		}

		public Builder zoomInOverlapMs(double value) {
			zoomInOverlapMs = value;
			return this;
		}

		public Builder zoomOutDurationMs(double value) {
			zoomOutDurationMs = value;
			return this;
		}

		public Builder connectedZoomGapMs(double value) {
			connectedZoomGapMs = value;
			return this;
		}

		public Builder connectedZoomDurationMs(double value) {
			connectedZoomDurationMs = value;
			return this;
		}

		public Builder zoomInEasing(ZoomTransitionEasing value) {
			zoomInEasing = value;
			return this;
		}

		public Builder zoomOutEasing(ZoomTransitionEasing value) {
			zoomOutEasing = value;
			return this;
		}

		public Builder connectedZoomEasing(ZoomTransitionEasing value) {
			connectedZoomEasing = value;
			return this;
		}

		public Builder showCursor(boolean value) {
			showCursor = value;
			return this;
		}

		public Builder loopCursor(boolean value) {
			loopCursor = value;
			return this;
		}

		public Builder cursorStyle(String value) {
			cursorStyle = value;
			return this;
		}

		public Builder cursorClickEffect(CursorClickEffectStyle value) {
			cursorClickEffect = value;
			return this;
		}

		public Builder cursorClickEffectColor(String value) {
			cursorClickEffectColor = value;
			return this;
		}

		public Builder cursorClickEffectScale(double value) {
			cursorClickEffectScale = value;
			return this;
		}

		public Builder cursorClickEffectOpacity(double value) {
			cursorClickEffectOpacity = value;
			return this;
		}

		public Builder cursorClickEffectDurationMs(double value) {
			cursorClickEffectDurationMs = value;
			return this;
		}

		public Builder cursorSize(double value) {
			cursorSize = value;
			return this;
		}

		public Builder cursorSmoothing(double value) {
			cursorSmoothing = value;
			return this;
		}

		public Builder cursorSpringStiffnessMultiplier(double value) {
			cursorSpringStiffnessMultiplier = value;
			return this;
		}

		public Builder cursorSpringDampingMultiplier(double value) {
			cursorSpringDampingMultiplier = value;
			return this;
		}

		public Builder cursorSpringMassMultiplier(double value) {
			cursorSpringMassMultiplier = value;
			return this;
		}

		public Builder cameraSpringStiffnessMultiplier(double value) {
			cameraSpringStiffnessMultiplier = value;
			return this;
		}

		public Builder cameraSpringDampingMultiplier(double value) {
			cameraSpringDampingMultiplier = value;
			return this;
		}

		public Builder cameraSpringMassMultiplier(double value) {
			cameraSpringMassMultiplier = value;
			return this;
		}

		public Builder zoomSmoothness(double value) {
			zoomSmoothness = value;
			return this;
		}

		public Builder zoomClassicMode(boolean value) {
			zoomClassicMode = value;
			return this;
		}

		public Builder cursorMotionBlur(double value) {
			cursorMotionBlur = value;
			return this;
		}

		public Builder cursorClickBounce(double value) {
			cursorClickBounce = value;
			return this;
		}

		public Builder cursorClickBounceDuration(double value) {
			cursorClickBounceDuration = value;
			return this;
		}

		public Builder cursorSway(double value) {
			cursorSway = value;
			return this;
		}

		public Builder borderRadius(double value) {
			borderRadius = value;
			return this;
		}

		public Builder padding(Padding value) {
			padding = value;
			return this;
		}

		public Builder cropRegion(CropRegion value) {
			cropRegion = value;
			return this;
		}

		public Builder zoomRegions(List<ZoomRegion> value) {
			zoomRegions = value;
			return this;
		}

		public Builder trimRegions(List<TrimRegion> value) {
			trimRegions = value;
			return this;
		}

		public Builder clipRegions(List<ClipRegion> value) {
			clipRegions = value;
			return this;
		}

		public Builder autoFullTrackClipId(String value) {
			autoFullTrackClipId = value;
			return this;
		}

		public Builder autoFullTrackClipEndMs(Long value) {
			autoFullTrackClipEndMs = value;
			return this;
		}

		public Builder speedRegions(List<SpeedRegion> value) {
			speedRegions = value;
			return this;
		}

		public Builder annotationRegions(List<AnnotationRegion> value) {
			annotationRegions = value;
			return this;
		}

		public Builder audioRegions(List<AudioRegion> value) {
			audioRegions = value;
			return this;
		}

		public Builder autoCaptions(List<CaptionCue> value) {
			autoCaptions = value;
			return this;
		}

		public Builder autoCaptionSettings(AutoCaptionSettings value) {
			autoCaptionSettings = value;
			return this;
		}

		public Builder keystrokeOverlay(KeystrokeOverlaySettings value) {
			keystrokeOverlay = value;
			return this;
		}

		public Builder webcam(WebcamOverlaySettings value) {
			webcam = value;
			return this;
		}

		public Builder aspectRatio(String value) {
			aspectRatio = value;
			return this;
		}

		public Builder sourceAudioTrackSettingsByClip(ObjectNode value) {
			sourceAudioTrackSettingsByClip = value;
			return this;
		}

		public Builder defaultSourceAudioTrackSettings(ObjectNode value) {
			defaultSourceAudioTrackSettings = value;
			return this;
		}

		public Builder keystrokeElements(List<KeystrokeElement> value) {
			keystrokeElements = value;
			return this;
		}

		/**
		 * Copies one component, named as in the project JSON, from another state. Agent settings
		 * paths name components at run time; the switch keeps every copy type-checked.
		 */
		public Builder copy(String component, ProjectEditorState from) {
			switch (component) {
				case "wallpaper" -> wallpaper = from.wallpaper;
				case "shadowIntensity" -> shadowIntensity = from.shadowIntensity;
				case "backgroundBlur" -> backgroundBlur = from.backgroundBlur;
				case "zoomMotionBlur" -> zoomMotionBlur = from.zoomMotionBlur;
				case "zoomMotionBlurTuning" -> zoomMotionBlurTuning = from.zoomMotionBlurTuning;
				case "connectZooms" -> connectZooms = from.connectZooms;
				case "zoomInDurationMs" -> zoomInDurationMs = from.zoomInDurationMs;
				case "zoomInOverlapMs" -> zoomInOverlapMs = from.zoomInOverlapMs;
				case "zoomOutDurationMs" -> zoomOutDurationMs = from.zoomOutDurationMs;
				case "connectedZoomGapMs" -> connectedZoomGapMs = from.connectedZoomGapMs;
				case "connectedZoomDurationMs" -> connectedZoomDurationMs = from.connectedZoomDurationMs;
				case "zoomInEasing" -> zoomInEasing = from.zoomInEasing;
				case "zoomOutEasing" -> zoomOutEasing = from.zoomOutEasing;
				case "connectedZoomEasing" -> connectedZoomEasing = from.connectedZoomEasing;
				case "showCursor" -> showCursor = from.showCursor;
				case "loopCursor" -> loopCursor = from.loopCursor;
				case "cursorStyle" -> cursorStyle = from.cursorStyle;
				case "cursorClickEffect" -> cursorClickEffect = from.cursorClickEffect;
				case "cursorClickEffectColor" -> cursorClickEffectColor = from.cursorClickEffectColor;
				case "cursorClickEffectScale" -> cursorClickEffectScale = from.cursorClickEffectScale;
				case "cursorClickEffectOpacity" -> cursorClickEffectOpacity = from.cursorClickEffectOpacity;
				case "cursorClickEffectDurationMs" -> cursorClickEffectDurationMs = from.cursorClickEffectDurationMs;
				case "cursorSize" -> cursorSize = from.cursorSize;
				case "cursorSmoothing" -> cursorSmoothing = from.cursorSmoothing;
				case "cursorSpringStiffnessMultiplier" -> cursorSpringStiffnessMultiplier = from.cursorSpringStiffnessMultiplier;
				case "cursorSpringDampingMultiplier" -> cursorSpringDampingMultiplier = from.cursorSpringDampingMultiplier;
				case "cursorSpringMassMultiplier" -> cursorSpringMassMultiplier = from.cursorSpringMassMultiplier;
				case "cameraSpringStiffnessMultiplier" -> cameraSpringStiffnessMultiplier = from.cameraSpringStiffnessMultiplier;
				case "cameraSpringDampingMultiplier" -> cameraSpringDampingMultiplier = from.cameraSpringDampingMultiplier;
				case "cameraSpringMassMultiplier" -> cameraSpringMassMultiplier = from.cameraSpringMassMultiplier;
				case "zoomSmoothness" -> zoomSmoothness = from.zoomSmoothness;
				case "zoomClassicMode" -> zoomClassicMode = from.zoomClassicMode;
				case "cursorMotionBlur" -> cursorMotionBlur = from.cursorMotionBlur;
				case "cursorClickBounce" -> cursorClickBounce = from.cursorClickBounce;
				case "cursorClickBounceDuration" -> cursorClickBounceDuration = from.cursorClickBounceDuration;
				case "cursorSway" -> cursorSway = from.cursorSway;
				case "borderRadius" -> borderRadius = from.borderRadius;
				case "padding" -> padding = from.padding;
				case "cropRegion" -> cropRegion = from.cropRegion;
				case "zoomRegions" -> zoomRegions = from.zoomRegions;
				case "trimRegions" -> trimRegions = from.trimRegions;
				case "clipRegions" -> clipRegions = from.clipRegions;
				case "autoFullTrackClipId" -> autoFullTrackClipId = from.autoFullTrackClipId;
				case "autoFullTrackClipEndMs" -> autoFullTrackClipEndMs = from.autoFullTrackClipEndMs;
				case "speedRegions" -> speedRegions = from.speedRegions;
				case "annotationRegions" -> annotationRegions = from.annotationRegions;
				case "audioRegions" -> audioRegions = from.audioRegions;
				case "autoCaptions" -> autoCaptions = from.autoCaptions;
				case "autoCaptionSettings" -> autoCaptionSettings = from.autoCaptionSettings;
				case "keystrokeOverlay" -> keystrokeOverlay = from.keystrokeOverlay;
				case "webcam" -> webcam = from.webcam;
				case "aspectRatio" -> aspectRatio = from.aspectRatio;
				case "sourceAudioTrackSettingsByClip" -> sourceAudioTrackSettingsByClip = from.sourceAudioTrackSettingsByClip;
				case "defaultSourceAudioTrackSettings" -> defaultSourceAudioTrackSettings = from.defaultSourceAudioTrackSettings;
				case "keystrokeElements" -> keystrokeElements = from.keystrokeElements;
				default -> throw new IllegalArgumentException("unknown editor component " + component);
			}
			return this;
		}

		public ProjectEditorState build() {
			return new ProjectEditorState(
					wallpaper,
					shadowIntensity,
					backgroundBlur,
					zoomMotionBlur,
					zoomMotionBlurTuning,
					connectZooms,
					zoomInDurationMs,
					zoomInOverlapMs,
					zoomOutDurationMs,
					connectedZoomGapMs,
					connectedZoomDurationMs,
					zoomInEasing,
					zoomOutEasing,
					connectedZoomEasing,
					showCursor,
					loopCursor,
					cursorStyle,
					cursorClickEffect,
					cursorClickEffectColor,
					cursorClickEffectScale,
					cursorClickEffectOpacity,
					cursorClickEffectDurationMs,
					cursorSize,
					cursorSmoothing,
					cursorSpringStiffnessMultiplier,
					cursorSpringDampingMultiplier,
					cursorSpringMassMultiplier,
					cameraSpringStiffnessMultiplier,
					cameraSpringDampingMultiplier,
					cameraSpringMassMultiplier,
					zoomSmoothness,
					zoomClassicMode,
					cursorMotionBlur,
					cursorClickBounce,
					cursorClickBounceDuration,
					cursorSway,
					borderRadius,
					padding,
					cropRegion,
					zoomRegions,
					trimRegions,
					clipRegions,
					autoFullTrackClipId,
					autoFullTrackClipEndMs,
					speedRegions,
					annotationRegions,
					audioRegions,
					autoCaptions,
					autoCaptionSettings,
					keystrokeOverlay,
					webcam,
					aspectRatio,
					sourceAudioTrackSettingsByClip,
					defaultSourceAudioTrackSettings,
					keystrokeElements);
		}
	}
}
