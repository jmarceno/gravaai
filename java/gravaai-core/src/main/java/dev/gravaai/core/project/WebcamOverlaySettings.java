package dev.gravaai.core.project;

import java.util.List;

/** Webcam overlay position, crop and timing. */
public record WebcamOverlaySettings(
		List<TimeRange> visibleRanges,
		boolean enabled,
		String sourcePath,
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
		Double cornerRadius,
		double shadow,
		double margin) {

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

	/** {@code DEFAULT_WEBCAM_OVERLAY}. */
	public static WebcamOverlaySettings defaults() {
		return new WebcamOverlaySettings(null, false, null, DEFAULT_WEBCAM_TIME_OFFSET_MS, true,
				CropRegion.DEFAULT, WebcamCorner.BOTTOM_RIGHT, DEFAULT_WEBCAM_POSITION_PRESET,
				DEFAULT_WEBCAM_POSITION_X, DEFAULT_WEBCAM_POSITION_Y, DEFAULT_WEBCAM_SIZE,
				DEFAULT_WEBCAM_SIZE, DEFAULT_WEBCAM_SIZE, DEFAULT_WEBCAM_REACT_TO_ZOOM,
				DEFAULT_WEBCAM_ROUNDNESS, null, DEFAULT_WEBCAM_SHADOW, DEFAULT_WEBCAM_MARGIN);
	}
}
