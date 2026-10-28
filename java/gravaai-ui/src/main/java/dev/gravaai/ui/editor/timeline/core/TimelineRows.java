package dev.gravaai.ui.editor.timeline.core;

/** Annotation/audio row id helpers (port of {@code timeline/core/rows.ts}). */
public final class TimelineRows {

	private TimelineRows() {
	}

	/** Port of {@code getAnnotationTrackRowId}. */
	public static String getAnnotationTrackRowId(double trackIndex) {
		return TimelineConstants.ANNOTATION_ROW_ID + "-" + Math.max(0, (long) Math.floor(trackIndex));
	}

	/** Port of {@code isAnnotationTrackRowId}. */
	public static boolean isAnnotationTrackRowId(String rowId) {
		return TimelineConstants.ANNOTATION_ROW_ID.equals(rowId)
				|| (rowId != null && rowId.startsWith(TimelineConstants.ANNOTATION_ROW_PREFIX));
	}

	/** Port of {@code getAnnotationTrackIndex}. */
	public static int getAnnotationTrackIndex(String rowId) {
		if (TimelineConstants.ANNOTATION_ROW_ID.equals(rowId)) {
			return 0;
		}
		if (rowId == null) {
			return 0;
		}
		try {
			int parsed = Integer.parseInt(rowId.substring(TimelineConstants.ANNOTATION_ROW_PREFIX.length()));
			return Math.max(0, parsed);
		} catch (NumberFormatException | StringIndexOutOfBoundsException error) {
			return 0;
		}
	}

	/** Row id for one source-audio track ({@code row-source-audio-<trackId>}). */
	public static String getSourceAudioTrackRowId(String trackId) {
		return TimelineConstants.SOURCE_AUDIO_ROW_ID + "-" + (trackId == null ? "" : trackId);
	}

	/** True for any source-audio track row. */
	public static boolean isSourceAudioTrackRowId(String rowId) {
		return rowId != null && rowId.startsWith(TimelineConstants.SOURCE_AUDIO_ROW_ID + "-");
	}

	/** Track id encoded in a source-audio row id. */
	public static String getSourceAudioTrackId(String rowId) {
		if (!isSourceAudioTrackRowId(rowId)) {
			return "";
		}
		return rowId.substring((TimelineConstants.SOURCE_AUDIO_ROW_ID + "-").length());
	}

	/** Port of {@code getAudioTrackRowId}. */
	public static String getAudioTrackRowId(double trackIndex) {
		return TimelineConstants.AUDIO_ROW_PREFIX + Math.max(0, (long) Math.floor(trackIndex));
	}

	/** Port of {@code isAudioTrackRowId}. */
	public static boolean isAudioTrackRowId(String rowId) {
		return TimelineConstants.AUDIO_ROW_ID.equals(rowId)
				|| (rowId != null && rowId.startsWith(TimelineConstants.AUDIO_ROW_PREFIX));
	}

	/** Port of {@code getAudioTrackIndex}. */
	public static int getAudioTrackIndex(String rowId) {
		if (TimelineConstants.AUDIO_ROW_ID.equals(rowId)) {
			return 0;
		}
		if (rowId == null) {
			return 0;
		}
		try {
			int parsed = Integer.parseInt(rowId.substring(TimelineConstants.AUDIO_ROW_PREFIX.length()));
			return Math.max(0, parsed);
		} catch (NumberFormatException | StringIndexOutOfBoundsException error) {
			return 0;
		}
	}
}
