package dev.gravaai.ui.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import dev.gravaai.ui.kit.Button;
import javafx.application.Platform;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import javafx.scene.Node;
import javafx.scene.Parent;

/**
 * User report 2026-10-28: the HUD overlay buttons showed no tooltips. The HUD is now an opaque
 * window sized to the bar (2026-10-29), so its buttons carry native tooltips in their own popup.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class HudTooltipVisibleTest {

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
	@DisplayName("hovering a HUD button shows its tooltip inside the HUD window")
	void hoverShowsTooltip() throws Exception {
		HudShowcase.HudHarnessPort port = new HudShowcase.HudHarnessPort();
		AtomicReference<HudOverlayWindow> hud = new AtomicReference<>();
		try {
			onFx(() -> {
				HudOverlayWindow window = new HudOverlayWindow(port);
				hud.set(window);
				window.show();
			});
			waitFor(() -> onFx(() -> hud.get().bar() != null), "the HUD bar was never built");
			Thread.sleep(300);
			Button trigger = onFx(() -> firstLabelledButton(hud.get().bar()));
			assertNotNull(trigger, "no labelled HUD button");
			String expected = onFx(trigger::getAccessibleText);
			String tooltip = onFx(() -> trigger.getTooltip() == null ? null : trigger.getTooltip().getText());
			assertEquals(expected, tooltip, "native tooltip matches the button label");
			assertEquals(StageStyle.UNDECORATED, onFx(() -> hud.get().stage().getStyle()),
					"the HUD is a normal (opaque) floating window");
			assertTrue(onFx(() -> hud.get().stage().getScene().getFill() instanceof Color fill
					&& fill.getOpacity() == 1.0), "the HUD scene is opaque");
		} finally {
			if (hud.get() != null) {
				onFx(() -> hud.get().dispose());
			}
		}
	}

	private static Button firstLabelledButton(Node node) {
		if (node instanceof Button button && button.getAccessibleText() != null
				&& !button.getAccessibleText().isBlank()) {
			return button;
		}
		if (node instanceof Parent parent) {
			for (Node child : parent.getChildrenUnmodifiable()) {
				Button found = firstLabelledButton(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
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
