package dev.gravaai.ui.launch;

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
import dev.gravaai.ui.kit.KitRoot;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * User report 2026-10-28: the HUD overlay buttons showed no tooltips. The HUD scene had no
 * {@link KitRoot}, so every HudTooltip gave up silently, and the window had no room above the bar.
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
			onFx(() -> Event.fireEvent(trigger, new MouseEvent(MouseEvent.MOUSE_ENTERED, 1, 1, 1, 1,
					MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false,
					false, null)));
			KitRoot root = onFx(() -> (KitRoot) hud.get().stage().getScene().getRoot());
			waitFor(() -> onFx(() -> !root.popoverLayer().getChildren().isEmpty()),
					"the tooltip was not added to the HUD popover layer");
			Thread.sleep(300);
			Node tooltip = onFx(() -> root.popoverLayer().getChildren().get(0));
			assertTrue(onFx(() -> tooltip instanceof Label label && expected.equals(label.getText())),
					"tooltip text matches the button label " + expected);
			Bounds bounds = onFx(() -> tooltip.localToScene(tooltip.getLayoutBounds()));
			double sceneHeight = onFx(() -> hud.get().stage().getScene().getHeight());
			assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= sceneHeight,
					"tooltip fits in the HUD window: " + bounds + " scene height " + sceneHeight);
			assertTrue(onFx(() -> tooltip.getOpacity() > 0.5), "tooltip faded in");
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
