package dev.gravaai.ui.editor.timeline.core;

/**
 * Delete-target resolution (port of {@code resolveDeleteSelectionTarget} in
 * {@code hooks/utils/timelineSelectionUtils.ts}).
 */
public final class TimelineSelectionMath {

	/** Port of the TS {@code DeleteSelectionTarget} union. */
	public enum DeleteTarget {
		KEYFRAME,
		ZOOM,
		CLIP,
		ANNOTATION,
		AUDIO,
		CAPTION,
		NONE
	}

	/** Port of the params object; ids are null when that kind is not selected. */
	public record SelectionIds(boolean selectAllBlocksActive, String selectedKeyframeId,
			String selectedZoomId, String selectedClipId, String selectedAnnotationId,
			String selectedAudioId, String selectedCaptionId) {
	}

	private TimelineSelectionMath() {
	}

	/** First match wins in the same order as the React helper. */
	public static DeleteTarget resolveDeleteTarget(SelectionIds ids) {
		if (ids.selectAllBlocksActive()) {
			return DeleteTarget.ZOOM;
		}
		if (notBlank(ids.selectedKeyframeId())) {
			return DeleteTarget.KEYFRAME;
		}
		if (notBlank(ids.selectedZoomId())) {
			return DeleteTarget.ZOOM;
		}
		if (notBlank(ids.selectedClipId())) {
			return DeleteTarget.CLIP;
		}
		if (notBlank(ids.selectedAnnotationId())) {
			return DeleteTarget.ANNOTATION;
		}
		if (notBlank(ids.selectedAudioId())) {
			return DeleteTarget.AUDIO;
		}
		if (notBlank(ids.selectedCaptionId())) {
			return DeleteTarget.CAPTION;
		}
		return DeleteTarget.NONE;
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
