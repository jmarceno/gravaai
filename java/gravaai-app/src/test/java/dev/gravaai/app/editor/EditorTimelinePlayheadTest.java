package dev.gravaai.app.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javafx.application.Platform;

/**
 * Regression: the preview's playback clock must drive the timeline indicator. Nothing subscribed
 * engine time to {@code TimelineView.setCurrentTimeMs}, so the playhead never moved while the video
 * played (user report 2026-10-28).
 *
 * <p>Uses the raw toolkit (not TestFX) because the assertion polls FX state from the test thread
 * while the FX thread keeps processing the engine's time callbacks.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class EditorTimelinePlayheadTest {

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			Platform.runLater(() -> { });
		}
		dev.gravaai.ui.AppFonts.load();
	}

	@Test
	@DisplayName("an engine seek drives the timeline playhead")
	void seekDrivesTimelinePlayhead() throws Exception {
		Path fixture = fixture();
		Path project = Files.createTempDirectory("gravaai-playhead")
				.resolve("Playhead project.gravaai");
		Files.writeString(project, """
				{"version":2,"projectId":"11111111-2222-3333-4444-555555555555",
				 "videoPath": "%s","createdAt":1700000000000,"updatedAt":1700000000000,
				 "projectName":"Playhead project",
				 "editor":{"wallpaper":"/wallpapers/tahoe-light.jpg","shadowIntensity":0.67,
				 "backgroundBlur":0,"aspectRatio":"16:9","borderRadius":0,
				 "padding":{"top":20,"bottom":20,"left":20,"right":20,"linked":true},
				 "clipRegions":[{"id":"clip-1","startMs":0,"endMs":2000,"speed":1}]}}
				""".formatted(fixture));
		AtomicReference<EditorWindow> holder = new AtomicReference<>();
		try {
			runOnFx(() -> {
				try {
					EditorWindow window = EditorWindow.create(project, EditorWindow.Services.bare());
					holder.set(window);
					window.show();
				} catch (Exception error) {
					throw new IllegalStateException(error);
				}
			});
			// Media attach is asynchronous: wait for the engine to report the clip duration.
			await(() -> holder.get().session().playbackPort().state().durationSeconds() > 0.5,
					15_000);
			// A seek publishes the engine time; before the fix nothing forwarded it to the timeline,
			// so the indicator stayed at 0 while the video played.
			runOnFx(() -> holder.get().session().playbackPort().seek(1.5));
			await(() -> holder.get().timeline().currentTime() >= 1_400, 10_000);
			double position = onFx(() -> holder.get().timeline().currentTime());
			assertTrue(position >= 1_400,
					"timeline playhead follows the engine time: " + position);
		} finally {
			EditorWindow window = holder.get();
			if (window != null) {
				runOnFx(window::dispose);
			}
		}
	}

	@Test
	@DisplayName("pressing empty timeline space seeks at once and dragging scrubs the preview live")
	void pressAndDragScrubsPreview() throws Exception {
		Path project = Files.createTempDirectory("gravaai-scrub").resolve("Scrub project.gravaai");
		Files.writeString(project, """
				{"version":2,"projectId":"11111111-2222-3333-4444-888888888888",
				 "videoPath": "%s","createdAt":1700000000000,"updatedAt":1700000000000,
				 "projectName":"Scrub project","editor":{"aspectRatio":"16:9",
				 "clipRegions":[{"id":"clip-1","startMs":0,"endMs":2000,"speed":1}]}}
				""".formatted(fixture()));
		AtomicReference<EditorWindow> holder = new AtomicReference<>();
		java.util.List<Double> frameTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
		try {
			runOnFx(() -> {
				try {
					EditorWindow window = EditorWindow.create(project, EditorWindow.Services.bare());
					holder.set(window);
					window.show();
				} catch (Exception error) {
					throw new IllegalStateException(error);
				}
			});
			await(() -> holder.get().session().playbackPort().state().durationSeconds() > 0.5,
					15_000);
			holder.get().session().playbackPort().controller()
					.onFrame(frame -> frameTimes.add(frame.timelineSeconds()));
			double width = onFx(() -> holder.get().timeline().getWidth());
			// The axis strip is empty timeline space: press seeks immediately (before release).
			runOnFx(() -> holder.get().timeline().pressAt(width * 0.25, 8));
			await(() -> holder.get().timeline().currentTime() > 300, 5_000);
			await(() -> !frameTimes.isEmpty() && frameTimes.get(frameTimes.size() - 1) > 0.3,
					5_000);
			// Dragging keeps the preview following the pointer while the button is still down.
			for (int step = 1; step <= 10; step++) {
				double x = width * (0.25 + step * 0.05);
				runOnFx(() -> holder.get().timeline().dragTo(x, 8));
				Thread.sleep(30);
			}
			await(() -> frameTimes.get(frameTimes.size() - 1) > 1.3, 5_000);
			double before = onFx(() -> holder.get().timeline().currentTime());
			assertTrue(before > 1_300, "playhead follows the drag: " + before);
			runOnFx(() -> holder.get().timeline().releaseAt(width * 0.75, 8));
		} finally {
			EditorWindow window = holder.get();
			if (window != null) {
				runOnFx(window::dispose);
			}
		}
	}

	private static void runOnFx(Runnable work) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Platform.runLater(() -> {
			try {
				work.run();
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(30, TimeUnit.SECONDS), "FX work timed out");
		if (failure.get() != null) {
			throw new AssertionError(failure.get());
		}
	}

	private static <T> T onFx(Supplier<T> work) throws Exception {
		AtomicReference<T> value = new AtomicReference<>();
		runOnFx(() -> value.set(work.get()));
		return value.get();
	}

	/** Polls a condition on the FX thread from the test thread. */
	private static void await(Supplier<Boolean> condition, long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (Boolean.TRUE.equals(onFx(condition))) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("condition not met within " + timeoutMs + " ms");
	}

	private static Path fixture() {
		for (Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath(); start != null;
				start = start.getParent()) {
			for (String relative : List.of(
					"parity/fixtures/media/h264-1080p60.mp4",
					"../parity/fixtures/media/h264-1080p60.mp4",
					"../../parity/fixtures/media/h264-1080p60.mp4")) {
				Path candidate = start.resolve(relative).normalize();
				if (Files.isRegularFile(candidate)) {
					return candidate;
				}
			}
		}
		throw new IllegalStateException("h264 fixture not found");
	}
}
