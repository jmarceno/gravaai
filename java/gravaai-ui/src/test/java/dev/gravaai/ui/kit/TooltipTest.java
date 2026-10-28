package dev.gravaai.ui.kit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The in-window tooltip must never become a mouse target. */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class TooltipTest {

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

@DisplayName("tooltip overlay content is mouse-transparent")
@Test
void contentIsMouseTransparent() throws Exception {
onFx(() -> {
Button target = new Button("Undo");
KitRoot root = new KitRoot(new StackPane(target));
Scene scene = new Scene(root, 640, 480);
root.resize(640, 480);
root.applyCss();
root.layout();

Tooltip tooltip = Tooltip.install(target, "Undo the last edit");
tooltip.showNow();

assertFalse(root.popoverLayer().getChildren().isEmpty());
assertTrue(root.popoverLayer().getChildren().get(0).isMouseTransparent());
assertTrue(scene.getRoot() == root);
});
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
