package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Webcam track sync for the preview, ported from
 * {@code src/components/video-editor/videoPlayback/webcamSync.ts} (plan §5.9).
 */
public final class WebcamSync {

	/** {@code HAVE_CURRENT_DATA_READY_STATE} of an HTMLMediaElement. */
	public static final int HAVE_CURRENT_DATA_READY_STATE = 2;

	/** A visible webcam span on the recording clock ({@code visibleRanges}). */
	public record WebcamRange(long startMs, long endMs) {
	}

	private WebcamSync() {
	}

	/**
	 * Maps the editor timeline time to the corresponding webcam media timestamp, accounting for any
	 * recorded webcam start offset and media duration clamps.
	 */
	public static double getWebcamMediaTargetTimeSeconds(
			double currentTime, @Nullable Double webcamDuration, Double timeOffsetMs) {
		double safeOffsetMs = timeOffsetMs != null && Double.isFinite(timeOffsetMs) ? timeOffsetMs : 0;
		double shiftedTime = currentTime - safeOffsetMs / 1000;
		return MediaTiming.clampMediaTimeToDuration(shiftedTime, webcamDuration);
	}

	/** The TS alias of {@code getWebcamMediaTargetTimeSeconds}. */
	public static double getWebcamPreviewTargetTimeSeconds(
			double currentTime, Double webcamDuration, Double timeOffsetMs) {
		return getWebcamMediaTargetTimeSeconds(currentTime, webcamDuration, timeOffsetMs);
	}

	/** Whether the webcam media element is ready, not seeking, and anchored to the timeline. */
	public static boolean isWebcamMediaSynchronized(
			double currentTime, double targetTime, int readyState, boolean isSeeking) {
		return isWebcamMediaSynchronized(currentTime, targetTime, readyState, isSeeking, 0.12);
	}

	public static boolean isWebcamMediaSynchronized(
			double currentTime,
			double targetTime,
			int readyState,
			boolean isSeeking,
			double toleranceSeconds) {
		return readyState >= HAVE_CURRENT_DATA_READY_STATE
				&& !isSeeking
				&& Math.abs(currentTime - targetTime) <= toleranceSeconds;
	}

	/** Whether the webcam media element needs a corrective seek for the current frame. */
	public static boolean shouldSeekWebcamMedia(
			double desiredTime,
			boolean isPlaying,
			boolean isSeeking,
			Double previousTimelineTime,
			double timelineTime,
			double webcamCurrentTime) {
		if (isSeeking) {
			return false;
		}
		boolean timelineJumped = previousTimelineTime == null
				|| Math.abs(timelineTime - previousTimelineTime) > 0.25;
		double driftThreshold = isPlaying ? 0.35 : 0.01;
		return timelineJumped || Math.abs(webcamCurrentTime - desiredTime) > driftThreshold;
	}

	/**
	 * Missing ranges means an original recording; an empty list means no webcam frames.
	 */
	public static boolean isWebcamVisibleAtSourceTime(
			@Nullable List<WebcamRange> visibleRanges, double sourceTimeSeconds) {
		if (visibleRanges == null) {
			return true;
		}
		double sourceTimeMs = sourceTimeSeconds * 1000;
		for (WebcamRange range : visibleRanges) {
			if (sourceTimeMs >= range.startMs() && sourceTimeMs < range.endMs()) {
				return true;
			}
		}
		return false;
	}
}
