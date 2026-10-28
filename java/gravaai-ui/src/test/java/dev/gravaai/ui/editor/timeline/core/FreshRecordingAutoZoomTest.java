package dev.gravaai.ui.editor.timeline.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of the pure decisions in {@code useFreshRecordingAutoZoom.ts}. */
class FreshRecordingAutoZoomTest {

	private static FreshRecordingAutoZoom.Input input(String platform, String videoPath,
			boolean loading, boolean previewReady, double durationMs, long telemetryCount,
			int normalizedSize, int zoomCount, String pendingPath, String autoSuggestedPath,
			long lastAttemptCount) {
		return new FreshRecordingAutoZoom.Input(platform, videoPath, loading, previewReady,
				durationMs, telemetryCount, normalizedSize, zoomCount, pendingPath,
				autoSuggestedPath, lastAttemptCount);
	}

	private static FreshRecordingAutoZoom.Input ready() {
		return input("linux", "/media/a.mp4", false, true, 30_000, 12, 12, 0, "/media/a.mp4", null,
				0);
	}

	@Test
	@DisplayName("suggests zooms for a ready fresh recording with new telemetry")
	void suggestsForReadyRecording() {
		assertEquals(FreshRecordingAutoZoom.Decision.SUGGEST,
				FreshRecordingAutoZoom.decide(ready()));
	}

	@Test
	@DisplayName("does nothing without an app platform")
	void doesNothingWithoutPlatform() {
		assertEquals(FreshRecordingAutoZoom.Decision.NO_PLATFORM,
				FreshRecordingAutoZoom.decide(input("", "/media/a.mp4", false, true, 30_000, 12, 12,
						0, "/media/a.mp4", null, 0)));
	}

	@Test
	@DisplayName("waits while loading, before preview or without enough telemetry")
	void waitsWhenNotReady() {
		assertEquals(FreshRecordingAutoZoom.Decision.WAIT, FreshRecordingAutoZoom.decide(
				input("linux", "/media/a.mp4", true, true, 30_000, 12, 12, 0, "/media/a.mp4",
						null, 0)));
		assertEquals(FreshRecordingAutoZoom.Decision.WAIT, FreshRecordingAutoZoom.decide(
				input("linux", "/media/a.mp4", false, false, 30_000, 12, 12, 0, "/media/a.mp4",
						null, 0)));
		assertEquals(FreshRecordingAutoZoom.Decision.WAIT, FreshRecordingAutoZoom.decide(
				input("linux", "/media/a.mp4", false, true, 30_000, 1, 1, 0, "/media/a.mp4",
						null, 0)));
	}

	@Test
	@DisplayName("skips recordings that already have zooms or were already suggested")
	void skipsAlreadyHandled() {
		assertEquals(FreshRecordingAutoZoom.Decision.WAIT, FreshRecordingAutoZoom.decide(
				input("linux", "/media/a.mp4", false, true, 30_000, 12, 12, 2, "/media/a.mp4",
						null, 0)));
		assertEquals(FreshRecordingAutoZoom.Decision.ALREADY_SUGGESTED,
				FreshRecordingAutoZoom.decide(input("linux", "/media/a.mp4", false, true, 30_000,
						12, 12, 0, "/media/a.mp4", "/media/a.mp4", 0)));
	}

	@Test
	@DisplayName("waits for a new telemetry count before firing again")
	void waitsForNewTelemetry() {
		assertEquals(FreshRecordingAutoZoom.Decision.UNCHANGED, FreshRecordingAutoZoom.decide(
				input("linux", "/media/a.mp4", false, true, 30_000, 12, 12, 0, "/media/a.mp4",
						null, 12)));
	}

	@Test
	@DisplayName("waits while the pending marker belongs to another video")
	void waitsForOtherPendingVideo() {
		assertEquals(FreshRecordingAutoZoom.Decision.WAIT_FOR_PENDING,
				FreshRecordingAutoZoom.decide(input("linux", "/media/a.mp4", false, true, 30_000,
						12, 12, 0, "/media/b.mp4", null, 0)));
	}

	@Test
	@DisplayName("removes auto zooms when the real source turns out ineligible")
	void removesAutoZoomsForIneligibleSource() {
		assertTrue(FreshRecordingAutoZoom.shouldRemoveAutoRegions("/media/a.mp4", true, 1,
				"/media/a.mp4", 960, 1020, "linux"));
		assertFalse(FreshRecordingAutoZoom.shouldRemoveAutoRegions("/media/a.mp4", true, 1,
				"/media/a.mp4", 1920, 1080, "linux"));
	}
}
