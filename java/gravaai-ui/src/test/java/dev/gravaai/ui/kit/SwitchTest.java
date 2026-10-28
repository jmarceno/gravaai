package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Interaction regression tests for the HeroUI switch. */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class SwitchTest {

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

@DisplayName("clicking the track or thumb toggles the switch once")
@Test
void trackClicksToggleOnce() throws Exception {
onFx(() -> {
Switch control = new Switch("Mirror");
AtomicInteger changes = new AtomicInteger();
control.onCheckedChange(value -> changes.incrementAndGet());

// The HBox children are the control/track and its label. Clicking either has to
// bubble to the switch exactly once; the control must not consume the event.
Node track = control.getChildrenUnmodifiable().get(0);
click(track);
assertTrue(control.isChecked());
assertEquals(1, changes.get());

click(track);
assertFalse(control.isChecked());
assertEquals(2, changes.get());
});
}

@DisplayName("clicking the label toggles the switch once")
@Test
void labelClicksToggleOnce() throws Exception {
onFx(() -> {
Switch control = new Switch("Loop cursor");
AtomicInteger changes = new AtomicInteger();
control.onCheckedChange(value -> changes.incrementAndGet());

Node label = control.getChildrenUnmodifiable().get(1);
click(label);
assertTrue(control.isChecked());
assertEquals(1, changes.get());
});
}

@DisplayName("space and enter toggle exactly once")
@Test
void keyboardTogglesOnce() throws Exception {
onFx(() -> {
Switch control = new Switch("Enabled");
AtomicInteger changes = new AtomicInteger();
control.onCheckedChange(value -> changes.incrementAndGet());

Event.fireEvent(control, key(KeyCode.SPACE));
assertTrue(control.isChecked());
assertEquals(1, changes.get());

Event.fireEvent(control, key(KeyCode.ENTER));
assertFalse(control.isChecked());
assertEquals(2, changes.get());
});
}

private static MouseEvent clickEvent() {
return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true,
false, false, null);
}

private static KeyEvent key(KeyCode code) {
return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
}

private static void click(Node target) {
Event.fireEvent(target, clickEvent());
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
