package dev.gravaai.media.playback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.core.project.ProjectDocument;

/**
 * Manual probe (-Dprobe.project=/path/to/project.gravaai): drives the real engine and ffmpeg
 * decoders through the editor's seek/scrub/play/end patterns and reports every frame that comes
 * back without video inside a clip.
 */
@Timeout(600)
class PlaybackSeekStressProbeTest {

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
	void stress() throws Exception {
		String projectPath = System.getProperty("probe.project");
		Assumptions.assumeTrue(projectPath != null, "probe only");
		ProjectDocument document = ProjectDocument.load(Path.of(projectPath));
		Path video = Path.of(document.videoPath());
		String webcamProperty = System.getProperty("probe.webcam");
		Path webcam = webcamProperty == null ? null : Path.of(webcamProperty);
		long offset = Long.getLong("probe.offset", 0L);
		PlaybackMedia media = PlaybackMediaLoader.load(video, webcam, offset,
				document.editor().clipRegions(), null);
		ManualClock clock = new ManualClock();
		ManualScheduler scheduler = new ManualScheduler();
		AtomicReference<PlaybackFrame> last = new AtomicReference<>();
		AtomicInteger delivered = new AtomicInteger();
		PlaybackEngine engine = new PlaybackEngine(new PlaybackEngine.Dependencies(
				DecoderFrameSourceProvider.ffmpegBacked(), clock, scheduler, false));
		engine.onFrame(frame -> {
			last.set(frame);
			delivered.incrementAndGet();
		});
		List<String> problems = new ArrayList<>();
		Random random = new Random(42);
		try {
			engine.setMedia(media);
			double duration = engine.status().durationSeconds();
			System.out.println("PROBE duration=" + duration + " webcam=" + webcam + " offset="
					+ offset);
			for (int round = 0; round < 60; round++) {
				int op = random.nextInt(5);
				String label;
				long started = System.nanoTime();
				switch (op) {
					case 0 -> {
						double t = random.nextDouble() * duration;
						label = "seek " + t;
						engine.seek(t);
					}
					case 1 -> {
						label = "scrub-drag";
						double from = random.nextDouble() * duration;
						double to = random.nextDouble() * duration;
						int before = delivered.get();
						for (int i = 0; i <= 30; i++) {
							engine.scrub(from + (to - from) * i / 30.0);
							Thread.sleep(8);
						}
						engine.seek(to);
						waitFor(delivered, before + 1);
					}
					case 2 -> {
						label = "play 1.5s";
						engine.play();
						for (int i = 0; i < 90; i++) {
							clock.advance(1 / 60.0);
							scheduler.tick();
						}
						engine.pause();
					}
					case 3 -> {
						label = "play to end";
						engine.seek(Math.max(0, duration - 1.0));
						engine.play();
						for (int i = 0; i < 120; i++) {
							clock.advance(1 / 60.0);
							scheduler.tick();
						}
					}
					default -> {
						label = "seek end";
						engine.seek(duration);
					}
				}
				long ms = (System.nanoTime() - started) / 1_000_000;
				PlaybackFrame frame = last.get();
				boolean inClip = frame != null && frame.clipId() != null;
				boolean missing = inClip && frame.sourceFrame() == null;
				boolean webcamMissing = webcam != null && inClip && frame.webcamVisible()
						&& frame.webcamFrame() == null;
				String line = "PROBE #" + round + " " + label + " -> t="
						+ (frame == null ? "none" : frame.timelineSeconds()) + " clip=" + inClip
						+ " video=" + (frame != null && frame.sourceFrame() != null)
						+ " webcam=" + (frame != null && frame.webcamFrame() != null) + " " + ms
						+ "ms state=" + engine.status().state();
				System.out.println(line);
				if (missing || webcamMissing || ms > 3000) {
					problems.add(line);
				}
			}
		} finally {
			engine.close();
		}
		System.out.println("PROBE problems=" + problems.size());
		problems.forEach(problem -> System.out.println("PROBE FAIL " + problem));
	}

	private static void waitFor(AtomicInteger counter, int target) throws InterruptedException {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (counter.get() < target && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
	}
}
