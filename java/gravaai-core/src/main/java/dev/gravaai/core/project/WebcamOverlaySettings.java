package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** Webcam overlay position, crop and timing. */
public record WebcamOverlaySettings(
		@Nullable List<TimeRange> visibleRanges,
		boolean enabled,
		@Nullable String sourcePath,
		long timeOffsetMs,
		boolean mirror,
		CropRegion cropRegion,
		WebcamCorner corner,
		WebcamPositionPreset positionPreset,
		double positionX,
		double positionY,
		double size,
		double width,
		double height,
		boolean reactToZoom,
		double roundness,
		@Nullable Double cornerRadius,
		double shadow,
		double margin,
		WebcamBackgroundEffect backgroundEffect,
		boolean highQualityMaskWhilePlaying,
		double maskPrecision,
		double maskOffset) {

	public record TimeRange(long startMs, long endMs) {}

	public static final double DEFAULT_WEBCAM_SIZE = 40;
	public static final boolean DEFAULT_WEBCAM_REACT_TO_ZOOM = true;
	public static final double DEFAULT_WEBCAM_ROUNDNESS = 100;
	public static final double DEFAULT_WEBCAM_SHADOW = 0.3;
	public static final double DEFAULT_WEBCAM_MARGIN = 24;
	public static final WebcamPositionPreset DEFAULT_WEBCAM_POSITION_PRESET = WebcamPositionPreset.BOTTOM_RIGHT;
	public static final double DEFAULT_WEBCAM_POSITION_X = 1;
	public static final double DEFAULT_WEBCAM_POSITION_Y = 1;
	public static final long DEFAULT_WEBCAM_TIME_OFFSET_MS = 0;
	public static final WebcamBackgroundEffect DEFAULT_WEBCAM_BACKGROUND_EFFECT =
			WebcamBackgroundEffect.NONE;
	/**
	 * Default for the Advanced slow-path switch: off, so playback uses the fast model and only a
	 * paused frame or an export pays for the precise one.
	 */
	public static final boolean DEFAULT_WEBCAM_HIGH_QUALITY_MASK_WHILE_PLAYING = false;
	/**
	 * Default edge precision: 0.5 is the mask post-process the blur shipped with (dilate 2 plus a
	 * σ2 Gaussian and a slope-12 sigmoid), so existing projects render exactly as before.
	 */
	public static final double DEFAULT_WEBCAM_MASK_PRECISION = 0.5;
	/** Default edge offset in percent of the card's short side: 0 means no grow/shrink. */
	public static final double DEFAULT_WEBCAM_MASK_OFFSET = 0;

	/** {@code DEFAULT_WEBCAM_OVERLAY}. */
	public static WebcamOverlaySettings defaults() {
		return new WebcamOverlaySettings(null, false, null, DEFAULT_WEBCAM_TIME_OFFSET_MS, true,
				CropRegion.DEFAULT, WebcamCorner.BOTTOM_RIGHT, DEFAULT_WEBCAM_POSITION_PRESET,
				DEFAULT_WEBCAM_POSITION_X, DEFAULT_WEBCAM_POSITION_Y, DEFAULT_WEBCAM_SIZE,
				DEFAULT_WEBCAM_SIZE, DEFAULT_WEBCAM_SIZE, DEFAULT_WEBCAM_REACT_TO_ZOOM,
				DEFAULT_WEBCAM_ROUNDNESS, null, DEFAULT_WEBCAM_SHADOW, DEFAULT_WEBCAM_MARGIN,
				DEFAULT_WEBCAM_BACKGROUND_EFFECT,
				DEFAULT_WEBCAM_HIGH_QUALITY_MASK_WHILE_PLAYING, DEFAULT_WEBCAM_MASK_PRECISION,
				DEFAULT_WEBCAM_MASK_OFFSET);
	}

	/** Copy with a different background effect. */
	public WebcamOverlaySettings withBackgroundEffect(WebcamBackgroundEffect effect) {
		return new WebcamOverlaySettings(visibleRanges, enabled, sourcePath, timeOffsetMs, mirror,
				cropRegion, corner, positionPreset, positionX, positionY, size, width, height,
				reactToZoom, roundness, cornerRadius, shadow, margin, effect,
				highQualityMaskWhilePlaying, maskPrecision, maskOffset);
	}

	/** Copy with a different mask edge shape (Webcam → Advanced sliders). */
	public WebcamOverlaySettings withMaskTuning(double precision, double offsetPercent) {
		return new WebcamOverlaySettings(visibleRanges, enabled, sourcePath, timeOffsetMs, mirror,
				cropRegion, corner, positionPreset, positionX, positionY, size, width, height,
				reactToZoom, roundness, cornerRadius, shadow, margin, backgroundEffect,
				highQualityMaskWhilePlaying, precision, offsetPercent);
	}
}
