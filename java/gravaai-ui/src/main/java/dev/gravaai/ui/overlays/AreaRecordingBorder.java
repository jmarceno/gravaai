package dev.gravaai.ui.overlays;

import org.jspecify.annotations.Nullable;

import javafx.application.Platform;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import dev.gravaai.services.sources.CaptureArea;
import dev.gravaai.ui.windows.X11Windows;

/**
 * The click-through border around an area recording (port of {@code showAreaRecordingBorder} in
 * {@code electron/windows.ts}, plan §5.4).
 *
 * <p>A transparent frameless stage sits 3 px outside the recorded rectangle with a 2 px dashed
 * white outline and the same 1 px dark halo as the React version. It must never intercept mouse
 * events, so {@link X11Windows#setClickThrough} replaces its X11 input shape with zero rectangles
 * (the Java equivalent of {@code setIgnoreMouseEvents(true)}).
*/
public final class AreaRecordingBorder {

	private static final System.Logger LOG = System.getLogger("gravaai.ui.overlays");
	/** {@code const outset = 3} in windows.ts. */
	private static final double OUTSET = 3;

	private @Nullable Stage stage;

	/** Shows (or moves) the border for an area; call on the JavaFX application thread. */
	public void show(CaptureArea area) {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(() -> show(area));
			return;
		}
		hide();
		double x = Math.round(area.x()) - OUTSET;
		double y = Math.round(area.y()) - OUTSET;
		double width = Math.round(area.width()) + OUTSET * 2;
		double height = Math.round(area.height()) + OUTSET * 2;

		Region border = new Region();
		border.setStyle(
				"-fx-background-color: transparent;"
						+ "-fx-border-color: rgba(255,255,255,0.95);"
						+ "-fx-border-width: 2;"
						+ "-fx-border-style: dashed;");
		// box-shadow: 0 0 0 1px rgba(0,0,0,.45), inset 0 0 0 1px rgba(0,0,0,.45)
		border.setEffect(new DropShadow(1, 0, 0, Color.rgb(0, 0, 0, 0.45)));
		StackPane content = new StackPane(border);
		content.setStyle("-fx-background-color: transparent;");

		Stage borderStage = OverlayStage.create("gravaai-area-recording-border");
		borderStage.setScene(OverlayStage.scene(content, width, height));
		borderStage.setX(x);
		borderStage.setY(y);
		borderStage.setWidth(width);
		borderStage.setHeight(height);
		borderStage.setOnHidden(event -> {
			if (stage == borderStage) {
				stage = null;
			}
		});
		borderStage.show();
		// The border belongs to Gravaai and is excluded from the capture; it must not
		// swallow clicks (windows.ts calls setIgnoreMouseEvents(true)).
		try {
			X11Windows.setClickThrough(borderStage);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "Could not make the area border click-through: " + error);
		}
		stage = borderStage;
	}

	/** Hides the border if one is shown. */
	public void hide() {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(this::hide);
			return;
		}
		Stage current = stage;
		stage = null;
		if (current != null && current.isShowing()) {
			current.close();
		}
	}

	public boolean isShowing() {
		return stage != null && stage.isShowing();
	}
}
