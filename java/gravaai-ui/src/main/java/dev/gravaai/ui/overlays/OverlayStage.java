package dev.gravaai.ui.overlays;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;

/**
 * Transparent, frameless overlay windows (plan §5.4).
 *
 * <p>Every overlay is a {@link StageStyle#TRANSPARENT} stage with a transparent scene fill; the
 * scene root is a {@link KitRoot} so kit popovers/toasts work inside it. Always-on-top is requested
 * (harmless: COSMIC ignores it, plan §3) and client positioning is only best-effort on COSMIC.
 */
public final class OverlayStage {

	private OverlayStage() {
	}

	/** Creates an undecorated transparent stage; call on the JavaFX application thread. */
	public static Stage create(String title) {
		requireFxThread();
		Stage stage = new Stage(StageStyle.TRANSPARENT);
		stage.setTitle(title);
		stage.setAlwaysOnTop(true);
		stage.setResizable(false);
		return stage;
	}

	/** Wraps content in a kit root and a transparent-fill scene of the requested size. */
	public static Scene scene(Node content, double width, double height) {
		KitRoot root = new KitRoot(content);
		Scene scene = new Scene(root, width, height);
		scene.setFill(Color.TRANSPARENT);
		Theme.install(scene);
		return scene;
	}

	/** Centres a stage on the primary display's work area. */
	public static void centerOnPrimary(Stage stage, double width, double height) {
		Screen screen = Screen.getPrimary();
		Rectangle2D bounds = screen.getVisualBounds();
		stage.setX(Math.round(bounds.getMinX() + (bounds.getWidth() - width) / 2));
		stage.setY(Math.round(bounds.getMinY() + (bounds.getHeight() - height) / 2));
	}

	/** Throws when touched off the JavaFX application thread; all JavaFX nodes require it. */
	public static void requireFxThread() {
		if (!Platform.isFxApplicationThread()) {
			throw new IllegalStateException("Overlay windows must be created on the JavaFX application thread");
		}
	}
}
