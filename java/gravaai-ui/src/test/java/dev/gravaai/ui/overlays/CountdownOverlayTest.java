package dev.gravaai.ui.overlays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.gravaai.services.recording.CountdownService;
import javafx.application.Platform;

/**
 * Regression for the recording start path: {@code CountdownService.start()} is called from the HUD
 * start worker (a virtual thread), and the countdown window is a JavaFX stage.
 *
 * <p>The overlay used to require the FX thread and threw
 * {@code "Overlay windows must be created on the JavaFX application thread"}; the countdown
 * service turned that into a failed result and the recording start returned silently. The record
 * button therefore never counted down and never recorded. {@link CountdownOverlay#create()} now
 * marshals the stage creation (and the timer-thread close) to the FX thread.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class CountdownOverlayTest {

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			Platform.runLater(() -> { });
		}
	}

	@Test
	@DisplayName("countdown starts from the recording worker thread and closes on the timer thread")
	void countdownFromRecordingWorkerThread() throws Exception {
		CountdownOverlay overlay = new CountdownOverlay();
		AtomicReference<CountdownService.CountdownResult> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				CountdownService service = new CountdownService(
						overlay, remaining -> { }, CountdownService.Scheduler.createDefault());
				result.set(service.start(1).join());
			} catch (Throwable error) {
				failure.set(error);
			}
		}, "test-hud-start-worker");
		worker.start();
		worker.join(30_000);

		assertNull(failure.get(), () -> "the countdown start worker failed: " + failure.get());
		assertNotNull(result.get(), "the countdown never settled");
		assertTrue(result.get().success(),
				() -> "countdown failed: " + result.get().error());
		assertTrue(waitUntil(() -> !overlay.isShowing(), 5_000),
				"the countdown window stayed on screen after the countdown finished");
	}

	private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.sleep(25);
		}
		return condition.getAsBoolean();
	}
}
