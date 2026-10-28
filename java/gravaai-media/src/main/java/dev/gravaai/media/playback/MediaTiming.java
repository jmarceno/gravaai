package dev.gravaai.media.playback;

/**
 * Media timing helpers for the preview playback engine, ported from {@code src/lib/mediaTiming.ts}
 * (plan §5.9): playhead clamping, duration reconciliation, companion-audio delay inference, the
 * sync playback rate used while correcting A/V drift and the effective video/recording durations.
 *
 * <p>{@code enablePitchPreservingPlayback} is N/A: Java has no HTMLMediaElement; pitch is preserved
 * by the decoder/audio path instead.
 */
public final class MediaTiming {

	private MediaTiming() {
	}

	/** {@code clampMediaTimeToDuration}: a negative time becomes 0, a known duration caps it. */
	public static double clampMediaTimeToDuration(double targetTime, Double duration) {
		double safeTargetTime = Math.max(0, targetTime);
		if (duration == null || !Double.isFinite(duration)) {
			return safeTargetTime;
		}
		return Math.max(0, Math.min(safeTargetTime, Math.max(0, duration)));
	}

	private static final double MIN_PREVIEW_MEDIA_DURATION_SHORTFALL_TOLERANCE_SECONDS = 0.5;
	private static final double MAX_PREVIEW_MEDIA_DURATION_SHORTFALL_TOLERANCE_SECONDS = 1;
	private static final double PREVIEW_MEDIA_DURATION_SHORTFALL_TOLERANCE_RATIO = 0.01;

	/** {@code resolvePreviewMediaDuration}: discard implausibly short reported durations. */
	public static Double resolvePreviewMediaDuration(Double reportedDuration, Double timelineDuration) {
		if (reportedDuration == null || !Double.isFinite(reportedDuration)) {
			return null;
		}
		double safeReportedDuration = Math.max(0, reportedDuration);
		if (timelineDuration == null || !Double.isFinite(timelineDuration)) {
			return safeReportedDuration;
		}
		double safeTimelineDuration = Math.max(0, timelineDuration);
		if (safeTimelineDuration == 0) {
			return safeReportedDuration;
		}
		double shortfallToleranceSeconds = Math.min(
				MAX_PREVIEW_MEDIA_DURATION_SHORTFALL_TOLERANCE_SECONDS,
				Math.max(
						MIN_PREVIEW_MEDIA_DURATION_SHORTFALL_TOLERANCE_SECONDS,
						safeTimelineDuration * PREVIEW_MEDIA_DURATION_SHORTFALL_TOLERANCE_RATIO));
		if (safeTimelineDuration - safeReportedDuration > shortfallToleranceSeconds) {
			return null;
		}
		return safeReportedDuration;
	}

	private static final double MIN_COMPANION_AUDIO_DELAY_SECONDS = 0.025;
	private static final double MAX_INFERRED_COMPANION_AUDIO_DELAY_SECONDS = 0.5;

	/** {@code estimateCompanionAudioStartDelaySeconds}. */
	public static double estimateCompanionAudioStartDelaySeconds(
			Double timelineDuration, Double audioDuration, Double recordedStartDelayMs) {
		if (recordedStartDelayMs != null && Double.isFinite(recordedStartDelayMs)
				&& recordedStartDelayMs >= 0) {
			return Math.max(0, recordedStartDelayMs) / 1000;
		}
		if (timelineDuration == null || !Double.isFinite(timelineDuration)
				|| audioDuration == null || !Double.isFinite(audioDuration)) {
			return 0;
		}
		double safeTimelineDuration = Math.max(0, timelineDuration);
		double safeAudioDuration = Math.max(0, audioDuration);
		double estimatedDelaySeconds = safeTimelineDuration - safeAudioDuration;
		if (estimatedDelaySeconds <= MIN_COMPANION_AUDIO_DELAY_SECONDS
				|| estimatedDelaySeconds > MAX_INFERRED_COMPANION_AUDIO_DELAY_SECONDS) {
			return 0;
		}
		return estimatedDelaySeconds;
	}

	/** {@code getMediaSyncPlaybackRate} with its TypeScript defaults. */
	public static double getMediaSyncPlaybackRate(
			double basePlaybackRate, double currentTime, double targetTime) {
		return getMediaSyncPlaybackRate(basePlaybackRate, currentTime, targetTime, 0.015, 2, 0.08);
	}

	/** {@code getMediaSyncPlaybackRate}: nudge the rate toward {@code targetTime}. */
	public static double getMediaSyncPlaybackRate(
			double basePlaybackRate,
			double currentTime,
			double targetTime,
			double toleranceSeconds,
			double correctionWindowSeconds,
			double maxAdjustment) {
		double safeBasePlaybackRate =
				Double.isFinite(basePlaybackRate) && basePlaybackRate > 0 ? basePlaybackRate : 1;
		if (!Double.isFinite(currentTime) || !Double.isFinite(targetTime)) {
			return safeBasePlaybackRate;
		}
		double driftSeconds = targetTime - currentTime;
		if (Math.abs(driftSeconds) <= toleranceSeconds) {
			return safeBasePlaybackRate;
		}
		double safeCorrectionWindow = correctionWindowSeconds > 0 ? correctionWindowSeconds : 2;
		double safeMaxAdjustment = Math.max(0, maxAdjustment);
		double adjustment = Math.max(
				-safeMaxAdjustment,
				Math.min(safeMaxAdjustment, driftSeconds / safeCorrectionWindow));
		return Math.max(0.1, safeBasePlaybackRate + adjustment);
	}

	/** {@code getEffectiveVideoStreamDurationSeconds}. */
	public static double getEffectiveVideoStreamDurationSeconds(double duration, double streamDuration) {
		double safeDuration = Double.isFinite(duration) && duration > 0 ? Math.max(0, duration) : 0;
		double safeStreamDuration =
				Double.isFinite(streamDuration) && streamDuration > 0 ? Math.max(0, streamDuration) : 0;
		if (safeDuration > 0 && safeStreamDuration > 0) {
			double gapSeconds = safeDuration - safeStreamDuration;
			double largeMismatchThresholdSeconds = Math.max(2, safeDuration * 0.1);
			if (gapSeconds > largeMismatchThresholdSeconds) {
				return safeDuration;
			}
			return safeStreamDuration;
		}
		if (safeStreamDuration > 0) {
			return safeStreamDuration;
		}
		if (safeDuration > 0) {
			return safeDuration;
		}
		return 0;
	}

	/** {@code getEffectiveRecordingDurationMs}. */
	public static double getEffectiveRecordingDurationMs(
			double startTimeMs,
			double endTimeMs,
			double accumulatedPausedDurationMs,
			Double pauseStartedAtMs) {
		if (!Double.isFinite(startTimeMs) || !Double.isFinite(endTimeMs)) {
			return 0;
		}
		double safeStartTime = Math.max(0, startTimeMs);
		double safeEndTime = Math.max(safeStartTime, endTimeMs);
		double activePauseDuration = pauseStartedAtMs != null && Double.isFinite(pauseStartedAtMs)
				? Math.max(0, safeEndTime - pauseStartedAtMs)
				: 0;
		return Math.max(
				0,
				safeEndTime - safeStartTime - Math.max(0, accumulatedPausedDurationMs) - activePauseDuration);
	}
}
