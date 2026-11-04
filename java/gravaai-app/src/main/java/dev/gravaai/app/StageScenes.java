package dev.gravaai.app;

import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Scene swaps inside the app's single main window.
 *
 * <p>The dashboard, the editor and the meeting view each build a {@link Scene} with their own
 * preferred size. Assigning such a scene to a showing stage can make JavaFX resize the window to
 * the new scene's dimensions (or to the new minimum size), which the user sees as the main window
 * jumping when opening a meeting or a recording. Single-window navigation must keep the window
 * bounds the user chose, so every hosted scene swap goes through {@link #setSceneKeepingSize}.
 */
public final class StageScenes {

private StageScenes() {
}

/** Replaces the stage scene while preserving the current window bounds when it is showing. */
public static void setSceneKeepingSize(Stage stage, Scene scene) {
if (stage == null || scene == null) {
return;
}
boolean restore = stage.isShowing() && stage.getScene() != null
&& stage.getWidth() > 0 && stage.getHeight() > 0;
boolean maximized = stage.isMaximized();
double width = stage.getWidth();
double height = stage.getHeight();
double x = stage.getX();
double y = stage.getY();
if (restore && !maximized) {
// JavaFX resizes a showing window to the new scene's own size inside setScene whenever the
// window's width/height were never set programmatically (a user drag does not count), which
// is what made the window jump — going Home re-applied the dashboard scene's 1280x800, e.g.
// after opening a project straight from the HUD, whose stage was still hidden at editor open.
// Marking the size explicit first keeps setScene from touching the window bounds at all.
stage.setWidth(width);
stage.setHeight(height);
}
stage.setScene(scene);
if (!restore) {
return;
}
if (maximized) {
// A maximized stage owns its bounds; re-assert the state in case setScene reset it.
stage.setMaximized(true);
return;
}
// Same values as above, so this only fires if the peer moved the window anyway.
stage.setWidth(width);
stage.setHeight(height);
stage.setX(x);
stage.setY(y);
}
}
