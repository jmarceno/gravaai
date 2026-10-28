package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from {@code src/components/video-editor/videoPlayback/webcamSync.test.ts}. */
class WebcamSyncTest {

	@Test
	@DisplayName("subtracts positive webcam offsets when the webcam started after the main capture")
	void subtractsPositiveOffsets() {
		assertEquals(9.75, WebcamSync.getWebcamPreviewTargetTimeSeconds(10, 20.0, 250.0));
	}

	@Test
	@DisplayName("adds negative webcam offsets when the webcam started before the main capture")
	void addsNegativeOffsets() {
		assertEquals(0.35, WebcamSync.getWebcamPreviewTargetTimeSeconds(0.1, 20.0, -250.0), 1e-9);
	}

	@Test
	@DisplayName("falls back to the unshifted time when the offset is invalid")
	void fallsBackOnInvalidOffset() {
		assertEquals(3.5, WebcamSync.getWebcamPreviewTargetTimeSeconds(3.5, 20.0, Double.NaN));
	}

	@Test
	@DisplayName("clamps to the webcam duration")
	void clampsToWebcamDuration() {
		assertEquals(9.0, WebcamSync.getWebcamPreviewTargetTimeSeconds(8.9, 9.0, -500.0));
	}

	@Test
	@DisplayName("clamps positive offsets at zero when the main timeline is earlier than the webcam start")
	void clampsPositiveOffsetAtZero() {
		assertEquals(0.0, WebcamSync.getWebcamMediaTargetTimeSeconds(0.1, 20.0, 250.0));
	}

	@Test
	@DisplayName("does not issue another corrective seek while the webcam element is already seeking")
	void doesNotSeekWhileAlreadySeeking() {
		assertFalse(WebcamSync.shouldSeekWebcamMedia(10.5, true, true, 10.0, 10.5, 9.8));
	}

	@Test
	@DisplayName("seeks when playback drift grows beyond the active threshold")
	void seeksOnPlaybackDrift() {
		assertTrue(WebcamSync.shouldSeekWebcamMedia(10.5, true, false, 10.0, 10.5, 9.8));
	}

	@Test
	@DisplayName("does not seek when the clamped media target is already correct")
	void doesNotSeekWhenTargetCorrect() {
		assertFalse(WebcamSync.shouldSeekWebcamMedia(1.0 / 60, false, false, 0.0, 0.0, 1.0 / 60));
	}

	@Test
	@DisplayName("waits for current frame data and a completed seek")
	void waitsForDataAndSeek() {
		assertFalse(WebcamSync.isWebcamMediaSynchronized(10, 10, 1, false));
		assertFalse(WebcamSync.isWebcamMediaSynchronized(10, 10, 2, true));
	}

	@Test
	@DisplayName("accepts a loaded webcam only when it is anchored to the timeline")
	void acceptsLoadedWebcamAnchoredToTimeline() {
		assertTrue(WebcamSync.isWebcamMediaSynchronized(9.95, 10, 2, false));
		assertFalse(WebcamSync.isWebcamMediaSynchronized(9.5, 10, 2, false));
	}

	@Test
	@DisplayName("hides screen-only spans and uses source-time boundaries")
	void hidesScreenOnlySpans() {
		List<WebcamSync.WebcamRange> ranges = List.of(
				new WebcamSync.WebcamRange(1200, 2000),
				new WebcamSync.WebcamRange(4000, 5000));
		assertFalse(WebcamSync.isWebcamVisibleAtSourceTime(ranges, 1));
		assertTrue(WebcamSync.isWebcamVisibleAtSourceTime(ranges, 1.2));
		assertFalse(WebcamSync.isWebcamVisibleAtSourceTime(ranges, 2));
		assertTrue(WebcamSync.isWebcamVisibleAtSourceTime(ranges, 4.5));
		assertFalse(WebcamSync.isWebcamVisibleAtSourceTime(List.of(), 0));
		assertTrue(WebcamSync.isWebcamVisibleAtSourceTime(null, 0));
	}
}
