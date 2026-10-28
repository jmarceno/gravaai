package dev.gravaai.ui.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import javafx.scene.control.Label;
import javafx.scene.paint.Color;

/**
 * The finalizing row ("Preparing recording") after stop. In the packaged app its title and
 * subtitle logged "Could not resolve '-g-fg'" and fell back to the default dark text, unreadable
 * on the dark HUD.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class HudFinalizingStyleTest {

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
	@DisplayName("the finalizing copy resolves the kit colours")
	void finalizingCopyUsesTheKitColours() throws Exception {
		List<String> cssWarnings = new CopyOnWriteArrayList<>();
		Handler capture = new Handler() {
			@Override
			public void publish(LogRecord record) {
				String message = record.getMessage();
				if (message != null && message.contains("Could not resolve")) {
					cssWarnings.add(message);
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
				HudOverlayWindow window = new HudOverlayWindow(port);
				hud.set(window);
				window.show();
			});
			waitFor(() -> onFx(() -> hud.get().bar() != null), "the HUD bar was never built");
			onFx(() -> port.setPhase(Phase.FINALIZING));
			waitFor(() -> onFx(() -> hud.get().stage().getScene().lookup(".hud-finalizing-title") != null),
					"the finalizing row was not shown");
			Thread.sleep(400);
			Label title = onFx(() -> {
				hud.get().stage().getScene().getRoot().applyCss();
				return (Label) hud.get().stage().getScene().lookup(".hud-finalizing-title");
			});
			assertNotNull(title);
			assertEquals(Color.WHITE, onFx(title::getTextFill), "title must use -g-fg (#ffffff)");
			assertTrue(cssWarnings.isEmpty(), () -> "unresolved CSS lookups: " + cssWarnings);
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
