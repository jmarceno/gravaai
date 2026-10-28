package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.ClipRegion;

/** Tests the PlaybackMedia hand-off helper used by the editor preview (P2.5). */
class PlaybackMediaLoaderTest {

	private static final ClipRegion CLIP =
			new ClipRegion("a", 0, 1_000, 0L, null, null, 1, null, null, null, null);

	@Test
	@DisplayName("probes the source duration and maps the project media")
	void probesDurationAndMapsMedia() {
		PlaybackMedia media = PlaybackMediaLoader.load(
				Path.of("/media/screen.mp4"),
				Path.of("/media/cam.webm"),
				250,
				List.of(CLIP),
				List.of(new WebcamSync.WebcamRange(1_000, 2_000)),
				file -> 12.5);

		assertEquals("/media/screen.mp4", media.videoPath());
		assertEquals("/media/cam.webm", media.webcamPath());
		assertEquals(250, media.webcamTimeOffsetMs());
		assertEquals(12_500, media.sourceDurationMs());
		assertEquals(List.of(CLIP), media.clips());
		assertEquals(1, media.webcamVisibleRanges().size());
	}

	@Test
	@DisplayName("unknown durations become zero and a missing webcam stays null")
	void unknownDurationBecomesZero() {
		PlaybackMedia media = PlaybackMediaLoader.load(
				Path.of("/media/screen.mp4"), null, 0, List.of(), null, file -> Double.NaN);
		assertEquals(0, media.sourceDurationMs());
		assertNull(media.webcamPath());
		assertNull(media.webcamVisibleRanges());
	}

	@Test
	@DisplayName("missing input and probe failures are rejected")
	void rejectsMissingInputAndProbeFailures() {
		assertThrows(IllegalArgumentException.class, () -> PlaybackMediaLoader.load(
				null, null, 0, List.of(), null, file -> 1));
		AtomicInteger calls = new AtomicInteger();
		assertThrows(IllegalArgumentException.class, () -> PlaybackMediaLoader.load(
				Path.of("/media/missing.mp4"),
				null,
				0,
				List.of(),
				null,
				file -> {
					calls.incrementAndGet();
					throw new IllegalArgumentException("no file");
				}));
		assertEquals(1, calls.get());
	}
}
