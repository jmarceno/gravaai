package dev.gravaai.ui.launch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.gravaai.ui.launch.HudRecordingPort.Phase;
import javafx.application.Platform;

/**
 * The packaged app logged bursts of "Could not resolve '-g-fg'" (and -g-muted, -g-accent, ...) at
 * every recording start and stop: the HUD is hidden for capture, and re-fitting the stage called
 * applyCss on the hidden scene, where the .root tokens do not resolve.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class HudHiddenCssTest {

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			// another test started the toolkit
		}
		Platform.setImplicitExit(false);
		dev.gravaai.ui.AppFonts.load();
	}

	@Test
	@DisplayName("state changes while hidden for capture resolve every kit colour")
	void hiddenStateChangesResolveTokens() throws Exception {
		List<String> warnings = new CopyOnWriteArrayList<>();
		Handler capture = new Handler() {
			@Override
			public void publish(LogRecord record) {
				String message = record.getMessage();
				if (message != null && message.contains("Could not resolve")) {
					warnings.add(message.replaceAll(" in stylesheet.*", ""));
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		Logger cssLogger = Logger.getLogger("javafx.css");
		cssLogger.addHandler(capture);
		HudShowcase.HudHarnessPort port = new HudShowcase.HudHarnessPort();
		AtomicReference<HudOverlayWindow> hud = new AtomicReference<>();
		try {
			onFx(() -> {
				hud.set(new HudOverlayWindow(port));
				hud.get().show();
			});
			waitFor(() -> onFx(() -> hud.get().bar() != null), "the HUD bar was never built");
			Thread.sleep(300);
			double idleWidth = onFx(() -> hud.get().stage().getWidth());

			// Recording start: the HUD hides for the capture pick, then the bar switches rows.
			onFx(() -> hud.get().hideForCapture());
			onFx(port::startWebcamRecording);
			Thread.sleep(300);
			onFx(() -> hud.get().restoreAfterCapture());
			Thread.sleep(400);
			double recordingWidth = onFx(() -> hud.get().stage().getWidth());
			double barWidth = onFx(() -> hud.get().bar().prefWidth(-1));
			assertTrue(recordingWidth >= barWidth,
					"the stage was re-fitted to the recording bar after restore: stage " + recordingWidth
							+ " bar " + barWidth + " (idle " + idleWidth + ")");

			// Stop from the tray while hidden: the finalizing row is built off screen.
			onFx(() -> hud.get().hideForCapture());
			onFx(() -> port.setPhase(Phase.FINALIZING));
			Thread.sleep(300);
			onFx(() -> hud.get().restoreAfterCapture());
			Thread.sleep(400);

			assertTrue(warnings.isEmpty(), () -> "unresolved CSS lookups: " + warnings.stream()
					.distinct().toList());
		} finally {
			cssLogger.removeHandler(capture);
			if (hud.get() != null) {
				onFx(() -> hud.get().dispose());
			}
		}
	}

	private static void onFx(Runnable action) throws Exception {
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Platform.runLater(() -> {
			try {
				action.run();
			} catch (Throwable error) {
				failure.set(error);
			} finally {
				done.countDown();
			}
		});
		assertTrue(done.await(60, TimeUnit.SECONDS), "the FX action did not run");
		if (failure.get() != null) {
			throw new AssertionError("FX action failed", failure.get());
		}
	}

	private static <T> T onFx(java.util.function.Supplier<T> supplier) throws Exception {
		AtomicReference<T> value = new AtomicReference<>();
		onFx(() -> value.set(supplier.get()));
		return value.get();
	}

	private static void waitFor(java.util.concurrent.Callable<Boolean> condition, String message)
			throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			if (Boolean.TRUE.equals(condition.call())) {
				return;
			}
			Thread.sleep(50);
		}
		assertTrue(Boolean.TRUE.equals(condition.call()), message);
	}
}
