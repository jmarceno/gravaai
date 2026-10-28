package dev.gravaai.core.project;

import java.util.List;

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
		String wallpaper,
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
		String autoFullTrackClipId,
		Long autoFullTrackClipEndMs,
		List<SpeedRegion> speedRegions,
		List<AnnotationRegion> annotationRegions,
		List<AudioRegion> audioRegions,
		List<CaptionCue> autoCaptions,
		AutoCaptionSettings autoCaptionSettings,
		KeystrokeOverlaySettings keystrokeOverlay,
		WebcamOverlaySettings webcam,
		String aspectRatio,
		ObjectNode sourceAudioTrackSettingsByClip,
		ObjectNode defaultSourceAudioTrackSettings) {

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
				defaultSourceAudioTrackSettings);
	}

	/** Copy with a new raw source-audio settings map (unknown keys preserved). */
	public ProjectEditorState withSourceAudioTrackSettingsByClip(tools.jackson.databind.node.ObjectNode nextSourceAudioTrackSettings) {		return new ProjectEditorState(wallpaper, shadowIntensity, backgroundBlur, zoomMotionBlur,
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
				keystrokeOverlay, webcam, aspectRatio, nextSourceAudioTrackSettings,
				defaultSourceAudioTrackSettings);
	}

	/** Typed view of {@link #sourceAudioTrackSettingsByClip()} (raw JSON stays canonical). */
	public java.util.Map<String, SourceAudioTrackSettings> sourceAudioTrackSettings() {
		return SourceAudioSettings.parseAll(sourceAudioTrackSettingsByClip);
	}

	/** Typed view of {@link #defaultSourceAudioTrackSettings()} (raw JSON stays canonical). */
	public SourceAudioTrackSettings defaultSourceAudioTrackSettingsTyped() {
		return SourceAudioSettings.parse(defaultSourceAudioTrackSettings);
	}
}
