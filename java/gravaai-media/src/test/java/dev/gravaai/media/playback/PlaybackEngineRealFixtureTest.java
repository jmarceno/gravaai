package dev.gravaai.media.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.media.ffmpeg.MediaProbe;

/**
 * Regression for the FFM thread-ownership bug ui-kit hit in the real preview: the engine opens
 * ffmpeg-backed sources from setMedia (FX thread) while the decode worker calls seek/next. Opens
 * the real fixture project and decodes frames through the production provider, using a manual
 * clock/scheduler so the test does not depend on wall-clock decode speed under a loaded build.
 */
@Timeout(120)
class PlaybackEngineRealFixtureTest {

	private static Path fixture(String group, String name) {
		Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		Path candidate = working.resolve(Path.of("..", "parity", "fixtures", group, name));
		if (Files.isReadable(candidate)) {
			return candidate.normalize();
		}
		return working.resolve(Path.of("parity", "fixtures", group, name)).normalize();
	}

	private static final class ManualClock implements PlaybackClock {

		private double position;
		private boolean running;

		@Override
		public double positionSeconds() {
			return position;
		}

		@Override
		public void resetTo(double seconds) {
			position = seconds;
		}

		@Override
		public void setRunning(boolean running) {
			this.running = running;
		}

		void advance(double seconds) {
			if (running) {
				position += seconds;
			}
		}
	}

	private static final class ManualScheduler implements RenderScheduler {

		private Runnable task;

		@Override
		public void start(Runnable tick) {
			task = tick;
		}

		@Override
		public void stop() {
			task = null;
		}

		void tick() {
			Runnable current = task;
			if (current != null) {
				current.run();
			}
		}
	}

	@Test
	@DisplayName("opens the real fixture project through the ffmpeg-backed provider and decodes frames")
	void decodesRealFixtureFrames() throws Exception {
		Path projectFile = fixture("projects", "03-zoom-motion-blur.gravaai");
		assertTrue(Files.isReadable(projectFile), "fixture project missing: " + projectFile);
		ProjectDocument document = ProjectDocument.load(projectFile);
		Path video = Path.of(document.videoPath());
		assertTrue(Files.isReadable(video), "fixture media missing: " + video);

		List<ClipRegion> clips = document.editor().clipRegions();
		double sourceDurationSeconds = MediaProbe.probe(video).durationSeconds();
		if (clips == null || clips.isEmpty()) {
			clips = List.of(new ClipRegion(
					"full", 0, Math.round(sourceDurationSeconds * 1000), 0L,
					null, null, 1, null, null, null, null));
		}
		PlaybackMedia media = PlaybackMediaLoader.load(video, null, 0, clips, null);

		ManualClock clock = new ManualClock();
		ManualScheduler scheduler = new ManualScheduler();
		List<PlaybackFrame> frames = new ArrayList<>();
		AtomicReference<PlaybackFrame> last = new AtomicReference<>();
		PlaybackEngine engine = new PlaybackEngine(new PlaybackEngine.Dependencies(
				DecoderFrameSourceProvider.ffmpegBacked(), clock, scheduler, false));
		engine.onFrame(frame -> {
			frames.add(frame);
			last.set(frame);
		});
		try {
			engine.setMedia(media);
			assertFalse(frames.isEmpty(), "setMedia must render the first frame");
			PlaybackFrame first = frames.get(0);
			assertNotNull(first.sourceFrame(), "first frame must decode through the worker thread");
			assertTrue(first.sourceFrame().width() > 0);
			assertTrue(first.sourceFrame().height() > 0);

			// Sequential playback decodes ahead from the ring instead of a full-GOP seek.
			engine.play();
			for (int step = 0; step < 5; step++) {
				clock.advance(0.05);
				scheduler.tick();
			}
			engine.pause();
			assertTrue(frames.size() >= 5, "expected decoded frames while playing, got " + frames.size());
			assertNotNull(last.get().sourceFrame());
			assertTrue(last.get().timelineSeconds() > 0, "playhead must advance");

			// A short seek stays inside the first GOP (cheap) and must still return a decoded frame.
			engine.seek(0.1);
			assertEquals(0.1, last.get().timelineSeconds(), 1e-9);
			assertNotNull(last.get().sourceFrame(), "seek frame must decode");
			assertTrue(last.get().sourceSeconds() > 0.05 && last.get().sourceSeconds() < 0.2);
		} finally {
			engine.close();
		}
	}
}
