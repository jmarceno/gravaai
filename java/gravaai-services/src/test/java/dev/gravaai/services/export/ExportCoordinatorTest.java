package dev.gravaai.services.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.gravaai.core.editor.ExportPreferences.ExportEncodingMode;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.media.export.ExportAudioPlan;
import dev.gravaai.media.export.ExportException;
import dev.gravaai.media.export.ExportFrameProvider;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.media.export.ExportRunner;
import dev.gravaai.media.export.SyntheticFrameProvider;
import dev.gravaai.services.automations.AutomationEvent;
import dev.gravaai.services.automations.AutomationTrigger;
import dev.gravaai.services.automations.AutomationTriggers;

/** {@link ExportCoordinator} lifecycle: one job at a time, cancel, automations trigger. */
class ExportCoordinatorTest {

	@TempDir
	Path tempDir;

	private static ExportCoordinator.Request request(Path destination) {
		return new ExportCoordinator.Request(ExportFormat.MP4, 160, 90, 24, 1_000_000,
				ExportEncodingMode.FAST, 0.3, false, destination, false, false, null,
				ExportAudioPlan.NONE);
	}

	@Test
	@DisplayName("runs one export, publishes the file and fires export-finished")
	void runsAndFiresTrigger() throws Exception {
		List<AutomationEvent> events = new CopyOnWriteArrayList<>();
		AutomationTriggers triggers = new AutomationTriggers(events::add);
		Path destination = tempDir.resolve("out").resolve("clip.mp4");
		AtomicReference<ExportRunner.Outcome> completed = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try (ExportCoordinator coordinator = new ExportCoordinator(new ExportPublisher(null),
				triggers)) {
			boolean started = coordinator.start(request(destination),
					new SyntheticFrameProvider(160, 90, 24), new ExportCoordinator.Listener() {
						@Override
						public void onCompleted(ExportRunner.Outcome outcome) {
							completed.set(outcome);
							done.countDown();
						}

						@Override
						public void onFailed(Throwable error) {
							failure.set(error);
							done.countDown();
						}
					});
			assertTrue(started);
			assertTrue(done.await(30, TimeUnit.SECONDS), "export did not finish");
			assertEquals(null, failure.get());
			assertTrue(Files.isRegularFile(destination));
			assertEquals(destination.toAbsolutePath(), completed.get().path());
			assertEquals(1, events.size());
			assertEquals(AutomationTrigger.EXPORT_FINISHED, events.get(0).trigger());
			assertEquals(destination.toAbsolutePath().toString(), events.get(0).path());
		}
	}

	@Test
	@DisplayName("rejects a second export while one is running")
	void oneAtATime() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExportFrameProvider blockingProvider = new ExportFrameProvider() {
			@Override
			public int width() {
				return 160;
			}

			@Override
			public int height() {
				return 90;
			}

			@Override
			public int frameRate() {
				return 24;
			}

			@Override
			public void renderFrame(long frameIndex, double timeSeconds, java.nio.ByteBuffer rgba)
					throws Exception {
				started.countDown();
				if (frameIndex == 0) {
					release.await(30, TimeUnit.SECONDS);
				}
				new SyntheticFrameProvider(160, 90, 24).renderFrame(frameIndex, timeSeconds, rgba);
			}
		};
		try (ExportCoordinator coordinator = new ExportCoordinator(new ExportPublisher(null), null)) {
			assertTrue(coordinator.start(request(tempDir.resolve("a.mp4")), blockingProvider, null));
			assertTrue(started.await(10, TimeUnit.SECONDS));
			assertTrue(coordinator.isBusy());
			assertFalse(coordinator.start(request(tempDir.resolve("b.mp4")),
					new SyntheticFrameProvider(160, 90, 24), null));
			coordinator.cancel();
			release.countDown();
			waitUntilIdle(coordinator);
			assertFalse(coordinator.isBusy());
		}
	}

	@Test
	@DisplayName("cancelling reports onCancelled and leaves no destination file")
	void cancelReports() throws Exception {
		CountDownLatch cancelled = new CountDownLatch(1);
		Path destination = tempDir.resolve("out").resolve("cancelled.mp4");
		ExportFrameProvider slowProvider = new ExportFrameProvider() {
			@Override
			public int width() {
				return 160;
			}

			@Override
			public int height() {
				return 90;
			}

			@Override
			public int frameRate() {
				return 24;
			}

			@Override
			public void renderFrame(long frameIndex, double timeSeconds, java.nio.ByteBuffer rgba)
					throws Exception {
				Thread.sleep(25);
				new SyntheticFrameProvider(160, 90, 24).renderFrame(frameIndex, timeSeconds, rgba);
			}
		};
		try (ExportCoordinator coordinator = new ExportCoordinator(new ExportPublisher(null), null)) {
			coordinator.start(request(destination), slowProvider, new ExportCoordinator.Listener() {
				@Override
				public void onCancelled() {
					cancelled.countDown();
				}
			});
			Thread.sleep(120);
			coordinator.cancel();
			assertTrue(cancelled.await(30, TimeUnit.SECONDS), "cancel was not reported");
			assertFalse(Files.exists(destination));
			waitUntilIdle(coordinator);
			assertFalse(coordinator.isBusy());
		}
	}

	@Test
	@DisplayName("reports failures instead of throwing from the worker thread")
	void reportsFailure() throws Exception {
		CountDownLatch failed = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		ExportFrameProvider failingProvider = new ExportFrameProvider() {
			@Override
			public int width() {
				return 160;
			}

			@Override
			public int height() {
				return 90;
			}

			@Override
			public int frameRate() {
				return 24;
			}

			@Override
			public void renderFrame(long frameIndex, double timeSeconds, java.nio.ByteBuffer rgba) {
				throw new ExportException("synthetic frame failure");
			}
		};
		try (ExportCoordinator coordinator = new ExportCoordinator(new ExportPublisher(null), null)) {
			coordinator.start(request(tempDir.resolve("fail.mp4")), failingProvider,
					new ExportCoordinator.Listener() {
						@Override
						public void onFailed(Throwable throwable) {
							error.set(throwable);
							failed.countDown();
						}
					});
			assertTrue(failed.await(30, TimeUnit.SECONDS));
			assertTrue(error.get() instanceof ExportException);
			waitUntilIdle(coordinator);
			assertFalse(coordinator.isBusy());
		}
	}

	private static void waitUntilIdle(ExportCoordinator coordinator) throws InterruptedException {
		for (int attempt = 0; attempt < 300 && coordinator.isBusy(); attempt++) {
			Thread.sleep(100);
		}
	}
}
