package dev.gravaai.media.compose;

/**
 * Port of {@code src/components/video-editor/videoPlayback/constants.ts} — the zoom animation
 * timing constants. {@link #DEFAULT_FOCUS} is the TS {@code DEFAULT_FOCUS}.
 */
public final class ZoomConstants {

	/** {@code DEFAULT_FOCUS}. */
	public static final ZoomFocus DEFAULT_FOCUS = new ZoomFocus(0.5, 0.5);

	/** {@code TRANSITION_WINDOW_MS}. */
	public static final double TRANSITION_WINDOW_MS = 1015.05;

	/** {@code ZOOM_IN_TRANSITION_WINDOW_MS}. */
	public static final double ZOOM_IN_TRANSITION_WINDOW_MS = TRANSITION_WINDOW_MS * 1.5;

	/** {@code ZOOM_OUT_EARLY_START_MS}. */
	public static final double ZOOM_OUT_EARLY_START_MS = 500;

	/** {@code MIN_DELTA}. */
	public static final double MIN_DELTA = 0.0001;

	/** {@code VIEWPORT_SCALE}. */
	public static final double VIEWPORT_SCALE = 0.8;

	/** {@code ZOOM_TRANSLATION_DEADZONE_PX}. */
	public static final double ZOOM_TRANSLATION_DEADZONE_PX = 1.25;

	/** {@code ZOOM_SCALE_DEADZONE}. */
	public static final double ZOOM_SCALE_DEADZONE = 0.002;

	private ZoomConstants() {}
}
