package dev.gravaai.ui.windows;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * The main application window (dashboard / editor / meeting view).
 *
 * <p>P1.1 skeleton: an empty window that proves packaging and the startup path. The real dashboard
 * (plan §7.1 P1.10) and editor shell (P2.6) replace the content; the window title and default size
 * follow {@code electron/windows.ts}.
 */
public final class EditorWindow {

	private static final System.Logger LOG = System.getLogger("gravaai.ui");

	private final Stage stage;

	public EditorWindow(Stage stage) {
		this(stage, "Gravaai");
	}

	/** Placeholder window with a message (plan P1.10: "Editor arrives in Phase 2"). */
	public EditorWindow(Stage stage, String message) {
		this.stage = stage;
		stage.setTitle("Gravaai");
		stage.setMinWidth(900);
		stage.setMinHeight(600);

		Label placeholder = new Label(message);
		placeholder.setStyle("-fx-text-fill: #e6e8eb; -fx-font-size: 18px;");
		StackPane root = new StackPane(placeholder);
		root.getStyleClass().add("gravaai-root");
		root.setStyle("-fx-background-color: #121418;");

		Scene scene = new Scene(root, 1280, 800);
		scene.setFill(Color.web("#121418"));
		stage.setScene(scene);
	}

	public void show() {
		stage.show();
		LOG.log(System.Logger.Level.INFO, "editor shown at " + stage.getX() + "," + stage.getY() + " "
				+ stage.getWidth() + "x" + stage.getHeight() + " renderScale=" + stage.getRenderScaleX());
	}

	public Stage stage() {
		return stage;
	}
}
