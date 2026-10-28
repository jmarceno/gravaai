package dev.gravaai.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** Single-window navigation must not resize the main window when the hosted scene changes. */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
@ExtendWith(ApplicationExtension.class)
class StageScenesTest {

@Start
void start(Stage stage) {
// The ApplicationExtension owns the toolkit; individual tests create their own windows.
}

@Test
@DisplayName("replacing a hosted scene keeps the user's window bounds")
void keepsWindowBoundsAcrossSceneSwap() throws Exception {
CompletableFuture<Void> done = new CompletableFuture<>();
Platform.runLater(() -> {
Stage stage = new Stage();
try {
stage.setScene(new Scene(new StackPane(), 1280, 800));
stage.show();
stage.setWidth(1310);
stage.setHeight(845);
double width = stage.getWidth();
double height = stage.getHeight();
double x = stage.getX();
double y = stage.getY();

StageScenes.setSceneKeepingSize(stage, new Scene(new StackPane(), 1440, 900));

assertEquals(width, stage.getWidth(), 0.5, "scene swap must preserve width");
assertEquals(height, stage.getHeight(), 0.5, "scene swap must preserve height");
assertEquals(x, stage.getX(), 0.5, "scene swap must preserve x");
assertEquals(y, stage.getY(), 0.5, "scene swap must preserve y");
done.complete(null);
} catch (Throwable error) {
done.completeExceptionally(error);
} finally {
stage.close();
}
});
done.get(60, TimeUnit.SECONDS);
}
}
