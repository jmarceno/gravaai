package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from {@code src/lib/mediaTiming.test.ts}. */
class MediaTimingTest {

	@Test
	@DisplayName("clamps playback time to known media duration")
	void clampsPlaybackTimeToKnownMediaDuration() {
		assertEquals(4.5, MediaTiming.clampMediaTimeToDuration(12, 4.5));
		assertEquals(0.0, MediaTiming.clampMediaTimeToDuration(-1, 4.5));
	}

	@Test
	@DisplayName("leaves playback time unchanged when duration is unknown")
	void leavesPlaybackTimeUnchangedWhenDurationUnknown() {
		assertEquals(12.0, MediaTiming.clampMediaTimeToDuration(12, null));
		assertEquals(12.0, MediaTiming.clampMediaTimeToDuration(12, Double.NaN));
	}

	@Test
	@DisplayName("keeps reported durations that approximately match the timeline")
	void keepsApproximatelyMatchingDurations() {
		assertEquals(120.0, MediaTiming.resolvePreviewMediaDuration(120.0, 120.0));
		assertEquals(119.25, MediaTiming.resolvePreviewMediaDuration(119.25, 120.0));
	}

	@Test
	@DisplayName("treats non-finite or missing reported durations as unknown")
	void treatsNonFiniteReportedDurationsAsUnknown() {
		assertNull(MediaTiming.resolvePreviewMediaDuration(Double.POSITIVE_INFINITY, 120.0));
		assertNull(MediaTiming.resolvePreviewMediaDuration(Double.NaN, 120.0));
		assertNull(MediaTiming.resolvePreviewMediaDuration(null, 120.0));
	}

	@Test
	@DisplayName("treats implausibly short reported durations as unknown")
	void treatsImplausiblyShortDurationsAsUnknown() {
		assertNull(MediaTiming.resolvePreviewMediaDuration(20.0, 120.0));
		assertNull(MediaTiming.resolvePreviewMediaDuration(0.0, 120.0));
	}

	@Test
	@DisplayName("falls back to the reported duration when the timeline duration is unknown")
	void fallsBackToReportedDurationWhenTimelineUnknown() {
		assertEquals(20.0, MediaTiming.resolvePreviewMediaDuration(20.0, Double.NaN));
		assertEquals(20.0, MediaTiming.resolvePreviewMediaDuration(20.0, null));
		assertNull(MediaTiming.resolvePreviewMediaDuration(Double.NaN, Double.NaN));
	}

	@Test
	@DisplayName("keeps small inferred offsets when the companion audio is only slightly shorter")
	void keepsSmallInferredOffsets() {
		assertEquals(0.4, MediaTiming.estimateCompanionAudioStartDelaySeconds(10.0, 9.6, null), 1e-9);
		assertEquals(0.03, MediaTiming.estimateCompanionAudioStartDelaySeconds(10.0, 9.97, null), 1e-9);
	}

	@Test
	@DisplayName("prefers an explicitly recorded start delay")
	void prefersExplicitStartDelay() {
		assertEquals(3.5, MediaTiming.estimateCompanionAudioStartDelaySeconds(10.0, 2.0, 3_500.0), 1e-9);
		assertEquals(0.0, MediaTiming.estimateCompanionAudioStartDelaySeconds(10.0, 2.0, 0.0));
	}

	@Test
	@DisplayName("ignores tiny, negative, or suspiciously large inferred differences")
	void ignoresInvalidInferredDifferences() {
		assertEquals(0.0, MediaTiming.estimateCompanionAudioStartDelaySeconds(10.0, 9.99, null));
		assertEquals(0.0, MediaTiming.estimateCompanionAudioStartDelaySeconds(10.0, 10.5, null));
		assertEquals(0.0, MediaTiming.estimateCompanionAudioStartDelaySeconds(600.0, 565.0, null));
	}

	@Test
	@DisplayName("subtracts accumulated paused time")
	void subtractsAccumulatedPausedTime() {
		assertEquals(
				7_500.0,
				MediaTiming.getEffectiveRecordingDurationMs(1_000, 11_000, 2_500, null));
	}

	@Test
	@DisplayName("subtracts an active pause interval")
	void subtractsAnActivePauseInterval() {
		assertEquals(
				6_000.0,
				MediaTiming.getEffectiveRecordingDurationMs(1_000, 11_000, 2_000, 9_000.0));
	}

	@Test
	@DisplayName("returns the base rate when drift is within tolerance")
	void returnsBaseRateWithinTolerance() {
		assertEquals(1.0, MediaTiming.getMediaSyncPlaybackRate(1, 10, 10.01));
	}

	@Test
	@DisplayName("nudges playback rate toward the target time")
	void nudgesPlaybackRateTowardTarget() {
		assertEquals(1.05, MediaTiming.getMediaSyncPlaybackRate(1, 10, 10.1), 1e-9);
		assertEquals(0.95, MediaTiming.getMediaSyncPlaybackRate(1, 10.1, 10), 1e-9);
	}

	@Test
	@DisplayName("clamps oversized corrections")
	void clampsOversizedCorrections() {
		assertEquals(1.08, MediaTiming.getMediaSyncPlaybackRate(1, 10, 10.5), 1e-9);
	}

	@Test
	@DisplayName("prefers the video stream duration when present")
	void prefersVideoStreamDuration() {
		assertEquals(11.2, MediaTiming.getEffectiveVideoStreamDurationSeconds(12, 11.2));
	}

	@Test
	@DisplayName("uses the container duration when the video stream is much shorter")
	void usesContainerDurationForLargeMismatch() {
		assertEquals(60.0, MediaTiming.getEffectiveVideoStreamDurationSeconds(60, 40));
	}

	@Test
	@DisplayName("falls back to the container duration when stream duration is missing")
	void fallsBackToContainerDuration() {
		assertEquals(12.0, MediaTiming.getEffectiveVideoStreamDurationSeconds(12, Double.NaN));
	}

	@Test
	@DisplayName("returns zero when neither duration is usable")
	void returnsZeroWhenNoDurationUsable() {
		assertEquals(0.0, MediaTiming.getEffectiveVideoStreamDurationSeconds(Double.NaN, 0));
	}
}
