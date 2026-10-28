package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** One user click must toggle a dropdown/popover exactly once. */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class OverlayTriggerTest {

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

@DisplayName("a button without an action toggles its dropdown once per click")
@Test
void dropdownAutoToggleOnce() throws Exception {
onFx(() -> {
Button trigger = new Button("Actions");
KitRoot root = sceneWith(trigger);
DropdownMenu menu = new DropdownMenu(trigger);

click(trigger);
assertTrue(menu.isShowing());
click(trigger);
assertFalse(menu.isShowing());
assertTrue(root.getChildren().contains(root.popoverLayer()));
});
}

@DisplayName("an action-managed button is not toggled twice by the dropdown")
@Test
void dropdownActionManagedTrigger() throws Exception {
onFx(() -> {
Button trigger = new Button("Actions");
sceneWith(trigger);
AtomicReference<DropdownMenu> menuRef = new AtomicReference<>();
trigger.setOnAction(event -> menuRef.get().toggle());
DropdownMenu menu = new DropdownMenu(trigger);
menuRef.set(menu);

// A real Button click fires the ActionEvent and then MOUSE_CLICKED. The popover's
// auto-click handler must not also run for this action-managed trigger.
trigger.fire();
click(trigger);
assertTrue(menu.isShowing());
});
}

@DisplayName("a button without an action toggles its popover once per click")
@Test
void popoverAutoToggleOnce() throws Exception {
onFx(() -> {
Button trigger = new Button("Volume");
sceneWith(trigger);
Popover popover = new Popover(trigger);

click(trigger);
assertTrue(popover.isShowing());
click(trigger);
assertFalse(popover.isShowing());
});
}

@DisplayName("an action-managed button is not toggled twice by the popover")
@Test
void popoverActionManagedTrigger() throws Exception {
onFx(() -> {
Button trigger = new Button("Projects");
sceneWith(trigger);
AtomicReference<Popover> popoverRef = new AtomicReference<>();
trigger.setOnAction(event -> popoverRef.get().toggle());
Popover popover = new Popover(trigger);
popoverRef.set(popover);

trigger.fire();
click(trigger);
assertTrue(popover.isShowing());
});
}

private static KitRoot sceneWith(Button trigger) {
StackPane content = new StackPane(trigger);
KitRoot root = new KitRoot(content);
new Scene(root, 640, 480);
root.resize(640, 480);
root.applyCss();
root.layout();
return root;
}

private static void click(Button target) {
Event.fireEvent(target, new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true,
false, false, null));
}

private static void onFx(ThrowingRunnable work) throws Exception {
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
assertTrue(done.await(60, TimeUnit.SECONDS), "FX work timed out");
if (failure.get() != null) {
throw new AssertionError(failure.get());
}
}

@FunctionalInterface
private interface ThrowingRunnable {
void run() throws Exception;
}
}
