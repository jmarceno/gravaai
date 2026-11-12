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
	double maskOffset,
	boolean smartFraming,
	boolean eyeContact,
	double eyeContactAngle) {

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
	/** Smart framing is opt-in: the camera only dodges the cursor when the project asks for it. */
	public static final boolean DEFAULT_WEBCAM_SMART_FRAMING = false;
	/** Eye-contact correction is opt-in: the gaze is only redirected when the project asks. */
	public static final boolean DEFAULT_WEBCAM_EYE_CONTACT = false;
	/**
	 * Default eye-contact correction in degrees of eye rotation towards the camera. 15&deg; matches
	 * the usual webcam-above-the-screen offset and is inside the range the warp model was trained
	 * on; see {@link #MAX_EYE_CONTACT_ANGLE}.
	 */
	public static final double DEFAULT_WEBCAM_EYE_CONTACT_ANGLE = 15;
	/** Largest correction the slider offers: past ~25&deg; the warp starts to look forced. */
	public static final double MAX_EYE_CONTACT_ANGLE = 25;

	/** {@code DEFAULT_WEBCAM_OVERLAY}. */
	public static WebcamOverlaySettings defaults() {
		return new WebcamOverlaySettings(null, false, null, DEFAULT_WEBCAM_TIME_OFFSET_MS, true,
				CropRegion.DEFAULT, WebcamCorner.BOTTOM_RIGHT, DEFAULT_WEBCAM_POSITION_PRESET,
				DEFAULT_WEBCAM_POSITION_X, DEFAULT_WEBCAM_POSITION_Y, DEFAULT_WEBCAM_SIZE,
				DEFAULT_WEBCAM_SIZE, DEFAULT_WEBCAM_SIZE, DEFAULT_WEBCAM_REACT_TO_ZOOM,
				DEFAULT_WEBCAM_ROUNDNESS, null, DEFAULT_WEBCAM_SHADOW, DEFAULT_WEBCAM_MARGIN,
				DEFAULT_WEBCAM_BACKGROUND_EFFECT,
				DEFAULT_WEBCAM_HIGH_QUALITY_MASK_WHILE_PLAYING, DEFAULT_WEBCAM_MASK_PRECISION,
				DEFAULT_WEBCAM_MASK_OFFSET, DEFAULT_WEBCAM_SMART_FRAMING, DEFAULT_WEBCAM_EYE_CONTACT,
				DEFAULT_WEBCAM_EYE_CONTACT_ANGLE);
	}

	/** Copy with a different background effect. */
	public WebcamOverlaySettings withBackgroundEffect(WebcamBackgroundEffect effect) {
		return new WebcamOverlaySettings(visibleRanges, enabled, sourcePath, timeOffsetMs, mirror,
				cropRegion, corner, positionPreset, positionX, positionY, size, width, height,
				reactToZoom, roundness, cornerRadius, shadow, margin, effect,
				highQualityMaskWhilePlaying, maskPrecision, maskOffset, smartFraming, eyeContact,
				eyeContactAngle);
	}

	/** Copy with eye-contact correction switched and aimed ({@code angle} in degrees). */
	public WebcamOverlaySettings withEyeContact(boolean on, double angle) {
		return new WebcamOverlaySettings(visibleRanges, enabled, sourcePath, timeOffsetMs, mirror,
				cropRegion, corner, positionPreset, positionX, positionY, size, width, height,
				reactToZoom, roundness, cornerRadius, shadow, margin, backgroundEffect,
				highQualityMaskWhilePlaying, maskPrecision, maskOffset, smartFraming, on,
				clampEyeContactAngle(angle));
	}

	/** Clamps a correction angle to the slider range; non-finite values fall back to the default. */
	public static double clampEyeContactAngle(double angle) {
		if (!Double.isFinite(angle)) {
			return DEFAULT_WEBCAM_EYE_CONTACT_ANGLE;
		}
		return Math.max(0, Math.min(MAX_EYE_CONTACT_ANGLE, angle));
	}

	/** Copy with a different mask edge shape (Webcam → Advanced sliders). */
	public WebcamOverlaySettings withMaskTuning(double precision, double offsetPercent) {
		return new WebcamOverlaySettings(visibleRanges, enabled, sourcePath, timeOffsetMs, mirror,
				cropRegion, corner, positionPreset, positionX, positionY, size, width, height,
				reactToZoom, roundness, cornerRadius, shadow, margin, backgroundEffect,
				highQualityMaskWhilePlaying, precision, offsetPercent, smartFraming, eyeContact,
				eyeContactAngle);
	}
}
