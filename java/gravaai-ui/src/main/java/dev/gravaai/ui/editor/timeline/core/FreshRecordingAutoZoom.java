package dev.gravaai.ui.editor.timeline.core;

/**
 * Pure decision logic of {@code useFreshRecordingAutoZoom.ts}: decide whether a freshly recorded
 * screencast should fire the suggest-zooms pass, and whether auto-added zooms must be removed when
 * the source aspect ratio turns out ineligible.
 */
public final class FreshRecordingAutoZoom {

	/** Port of the hook's inputs relevant to the decisions. */
	public record Input(String appPlatform, String videoPath, boolean loading, boolean previewReady,
			double durationMs, long cursorTelemetryCount, int normalizedTelemetrySize,
			int zoomRegionCount, String pendingAutoZoomPath, String autoSuggestedVideoPath,
			long lastAttemptTelemetryCount) {
	}

	public enum Decision {
		/** Capture is portrait/near-square and not win32: auto zooms are not applied. */
		SUPPRESSED,
		/** No platform yet: the hook does nothing. */
		NO_PLATFORM,
		/** Not ready (loading, no preview, no duration, existing zooms, too few samples). */
		WAIT,
		/** The pending marker belongs to another video. */
		WAIT_FOR_PENDING,
		/** This video already got an auto-suggestion pass. */
		ALREADY_SUGGESTED,
		/** No new telemetry since the last attempt. */
		UNCHANGED,
		/** Debounce finished and the pass should fire. */
		SUGGEST
	}

	private FreshRecordingAutoZoom() {
	}

	public static Decision decide(Input input) {
		if (input.appPlatform() == null || input.appPlatform().isBlank()) {
			return Decision.NO_PLATFORM;
		}
		if (input.videoPath() != null && input.videoPath().equals(input.pendingAutoZoomPath())
				&& input.previewReady() && !ZoomSuggestionUtils
						.shouldAutoApplyFreshRecordingZoomsForSource(null, null, input.appPlatform())) {
			return Decision.SUPPRESSED;
		}
		if (input.videoPath() == null || input.loading() || !input.previewReady()
				|| input.durationMs() <= 0 || input.zoomRegionCount() > 0
				|| input.normalizedTelemetrySize() < 2) {
			return Decision.WAIT;
		}
		if (!input.videoPath().equals(input.pendingAutoZoomPath())) {
			return Decision.WAIT_FOR_PENDING;
		}
		if (input.videoPath().equals(input.autoSuggestedVideoPath())) {
			return Decision.ALREADY_SUGGESTED;
		}
		if (input.lastAttemptTelemetryCount() == input.cursorTelemetryCount()) {
			return Decision.UNCHANGED;
		}
		return Decision.SUGGEST;
	}

	/**
	 * Port of the second effect: auto-added zooms are removed when the video was auto-suggested but
	 * the real source dimensions make the capture ineligible.
	 */
	public static boolean shouldRemoveAutoRegions(String videoPath, boolean previewReady,
			int zoomRegionCount, String autoSuggestedVideoPath, Integer sourceWidth,
			Integer sourceHeight, String platform) {
		return videoPath != null && previewReady && zoomRegionCount > 0
				&& videoPath.equals(autoSuggestedVideoPath)
				&& !ZoomSuggestionUtils.shouldAutoApplyFreshRecordingZoomsForSource(sourceWidth,
						sourceHeight, platform);
	}
}
