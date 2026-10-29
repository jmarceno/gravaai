package dev.gravaai.core.editor;

import org.jspecify.annotations.Nullable;

import java.util.List;

import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.CursorClickEffectStyle;
import dev.gravaai.core.project.KeystrokeOverlaySettings;
import dev.gravaai.core.project.Padding;
import dev.gravaai.core.project.ZoomMotionBlurTuning;
import dev.gravaai.core.project.ZoomTransitionEasing;

/** The editor controls captured by an editor preset (port of {@code EditorPresetSnapshot}). */
public record EditorPresetSnapshot(
		@Nullable String wallpaper,
		@Nullable double shadowIntensity,
		@Nullable double backgroundBlur,
		@Nullable double zoomMotionBlur,
		@Nullable ZoomMotionBlurTuning zoomMotionBlurTuning,
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
		@Nullable double cursorSway,
		@Nullable double borderRadius,
		@Nullable Padding padding,
		@Nullable String aspectRatio,
		String borderRadiusUnit,
		WebcamPresetSettings webcam,
		@Nullable CropRegion cropRegion,
		@Nullable AutoCaptionSettings autoCaptionSettings,
		KeystrokeOverlaySettings keystrokeOverlay) {}
