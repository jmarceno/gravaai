package dev.gravaai.ui.editor.timeline.core;

/** Row ids and limits of the timeline (port of {@code timeline/core/constants.ts}). */
public final class TimelineConstants {

	public static final String ZOOM_ROW_ID = "row-zoom";
	public static final String CLIP_ROW_ID = "row-clip";
	public static final String ANNOTATION_ROW_ID = "row-annotation";
	public static final String AUDIO_ROW_ID = "row-audio";
	public static final String SOURCE_AUDIO_ROW_ID = "row-source-audio";
	public static final String CAPTION_ROW_ID = "row-captions";
	public static final String ANNOTATION_ROW_PREFIX = ANNOTATION_ROW_ID + "-";
	public static final String AUDIO_ROW_PREFIX = AUDIO_ROW_ID + "-";

	public static final double FALLBACK_RANGE_MS = 1000;
	public static final int TARGET_MARKER_COUNT = 12;
	public static final int WAVEFORM_DEFAULT_PEAK_COUNT = 2048;

	private TimelineConstants() {
	}
}
