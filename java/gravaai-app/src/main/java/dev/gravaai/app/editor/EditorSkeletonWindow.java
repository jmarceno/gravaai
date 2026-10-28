package dev.gravaai.app.editor;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Minimal startup window for the P2.11 direct-editor fast path: a dark full-window poster of the
 * project's first frame (plus a small loading label) that becomes visible within the startup budget
 * while the real {@link EditorWindow} is built on the following FX pulse.
 *
 * <p>Deliberately tiny (one StackPane, one ImageView, one Label) so its construction stays a few
 * milliseconds; the full editor chrome arrives with the real window.
 */
public final class EditorSkeletonWindow {

	private final Stage stage;
	private final ImageView poster;
	private final Label opening;
	/** True when the skeleton created its own stage; a hosted skeleton must not close the app. */
	private final boolean ownsStage;

	private EditorSkeletonWindow(Stage stage, ImageView poster, Label opening, boolean ownsStage) {
		this.stage = stage;
		this.poster = poster;
		this.opening = opening;
		this.ownsStage = ownsStage;
	}

	/** Creates and shows the skeleton window for the project being opened. */
	public static EditorSkeletonWindow show(String projectName) {
		return show(new Stage(), projectName, null, true);
	}

	public static EditorSkeletonWindow show(String projectName, Image posterImage) {
		// The single-window app hosts the skeleton on the primary stage; this standalone variant is
		// kept for tools/tests that deliberately want a throwaway window.
		return show(new Stage(StageStyle.UNDECORATED), projectName, posterImage, true);
	}

	/**
	 * Builds the skeleton on an already-created stage (the JavaFX primary stage), which avoids a
	 * second native window creation on the startup budget path.
	 */
	public static EditorSkeletonWindow show(Stage stage, String projectName, Image posterImage) {
		return show(stage, projectName, posterImage, false);
	}

	private static EditorSkeletonWindow show(Stage stage, String projectName, Image posterImage,
			boolean ownsStage) {
		ImageView poster = new ImageView();
		poster.setPreserveRatio(true);
		poster.setSmooth(true);
		poster.setVisible(false);

		Label opening = new Label("Opening " + projectName + "…");
		opening.setTextFill(Color.web("#8b929a"));
		opening.setPadding(new Insets(24));
		StackPane root = new StackPane(poster, opening);
		StackPane.setAlignment(opening, Pos.BOTTOM_CENTER);
		root.setStyle("-fx-background-color: #0b0d10;");
		poster.fitWidthProperty().bind(root.widthProperty());
		poster.fitHeightProperty().bind(root.heightProperty());

		Scene scene = new Scene(root, 1440, 900, Color.web("#0b0d10"));
		stage.setTitle("Gravaai - " + projectName);
		stage.setScene(scene);
		EditorSkeletonWindow window = new EditorSkeletonWindow(stage, poster, opening, ownsStage);
		window.setPoster(posterImage);
		stage.show();
		return window;
	}

	/** Shows the first project frame (poster) in the preview area. */
	public void setPoster(Image image) {
		if (image == null || image.isError()) {
			return;
		}
		poster.setImage(image);
		poster.setVisible(true);
		opening.setVisible(false);
	}

	public void close() {
		// A hosted skeleton shares the primary stage: closing it would close the app window.
		if (ownsStage) {
			stage.close();
		}
	}

	public Stage stage() {
		return stage;
	}
}
