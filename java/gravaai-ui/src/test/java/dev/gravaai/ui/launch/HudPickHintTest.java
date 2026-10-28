package dev.gravaai.ui.launch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javafx.application.Platform;

/**
 * HUD feedback for the "Pick screen" action (the missing React
 * {@code toast.info("Click anywhere on the screen you want to record.")}).
 *
 * <p>Before the fix the pick was a silent 15 s wait: the HUD showed nothing while the picker
 * waited and nothing when it timed out, so the action looked dropped. The HUD now renders a hint
 * bubble above the bar for the pending pick and an error hint when a pick ends without a source.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class HudPickHintTest {

	@BeforeAll
	static void startJavaFx() throws Exception {
		try {
			CountDownLatch started = new CountDownLatch(1);
			Platform.startup(started::countDown);
			assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
		} catch (IllegalStateException alreadyRunning) {
			onFx(() -> { });
		}
		// Disposing the first test's HUD closes the last window; with implicit exit on, JavaFX
		// then shuts the toolkit down and the next test's runLater never runs.
		Platform.setImplicitExit(false);
		// The HUD loads the DM Sans faces in its constructor; warm them off the FX thread so the
		// first FX action is not dominated by first-call font loading (and cannot time out).
		dev.gravaai.ui.AppFonts.load();
	}

	@Test
	@DisplayName("pending pick shows the click instruction and clears when a source arrives")
	void pickingShowsTheClickInstruction() throws Exception {
		HudShowcase.HudHarnessPort port = new HudShowcase.HudHarnessPort();
		AtomicReference<HudOverlayWindow> hud = new AtomicReference<>();
		onFx(() -> {
			HudOverlayWindow window = new HudOverlayWindow(port);
			hud.set(window);
			window.show();
		});
		waitFor(() -> {
			HudOverlayWindow window = hud.get();
			return window != null && window.bar() != null;
		}, "the HUD bar was never built");
		try {
			double idleHeight = onFx(() -> hud.get().stage().getHeight());

			onFx(() -> port.setPicking(true));
			waitFor(() -> onFx(() -> hud.get().hintLabel().isVisible()),
					"the pick hint did not appear");
			assertTrue(onFx(() -> hud.get().hintLabel().getText().contains("Click anywhere")),
					"the hint should tell the user to click a screen");
			assertTrue(onFx(() -> hud.get().stage().getHeight()) > idleHeight,
					"the HUD stage must grow to fit the hint bubble");

			onFx(() -> port.setSourceName("Screen DP-1 (1080×1920)"));
			onFx(() -> port.setPicking(false));
			waitFor(() -> onFx(() -> !hud.get().hintLabel().isVisible()),
					"the hint did not clear when the pick selected a source");
		} finally {
			onFx(() -> hud.get().dispose());
		}
	}

	@Test
	@DisplayName("a pick that ends without a source shows the retry hint")
	void cancelledPickShowsRetryHint() throws Exception {
		HudShowcase.HudHarnessPort port = new HudShowcase.HudHarnessPort();
		AtomicReference<HudOverlayWindow> hud = new AtomicReference<>();
		onFx(() -> {
			HudOverlayWindow window = new HudOverlayWindow(port);
			hud.set(window);
			window.show();
		});
		waitFor(() -> hud.get() != null && hud.get().bar() != null, "the HUD bar was never built");
		try {
			onFx(() -> port.setPicking(true));
			waitFor(() -> onFx(() -> hud.get().hintLabel().isVisible()),
					"the pick hint did not appear");

			onFx(() -> port.setPicking(false));
			waitFor(() -> onFx(() -> hud.get().hintLabel().isVisible()
							&& hud.get().hintLabel().getText().contains("No screen was selected")),
					"a pick without a source should ask the user to try again");
			assertTrue(onFx(() -> hud.get().hintLabel().getStyleClass().contains("hud-hint--error")),
					"the retry hint is an error hint");

			onFx(() -> hud.get().hideHint());
			assertFalse(onFx(() -> hud.get().hintLabel().isVisible()));
		} finally {
			onFx(() -> hud.get().dispose());
		}
	}

	// ── small FX helpers ──────────────────────────────────────────────────

	private static void onFx(Runnable action) throws Exception {
		if (Platform.isFxApplicationThread()) {
			action.run();
			return;
		}
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

	private static void waitFor(Callable<Boolean> condition, String message) throws Exception {
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
