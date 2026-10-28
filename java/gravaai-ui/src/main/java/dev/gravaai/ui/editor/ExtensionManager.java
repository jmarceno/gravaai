package dev.gravaai.ui.editor;

import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Extensions section shell (port of {@code ExtensionManager.tsx}): extension installation is
 * disabled in the React app, so the section keeps the same empty-state card until Phase 3.
 */
public final class ExtensionManager extends StackPane {

	public ExtensionManager() {
		Label title = new Label("Extensions are no longer available");
		title.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: -g-fg;");
		Label detail = new Label(
				"Extension installation and marketplace access have been disabled. "
						+ "This area is kept as a placeholder for existing projects and navigation.");
		detail.setWrapText(true);
		detail.setMaxWidth(240);
		detail.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted; -fx-text-alignment: center;");

		StackPane mark = new StackPane(Icon.of(Icons.PuzzlePiece, 24));
		mark.setMinSize(48, 48);
		mark.setPrefSize(48, 48);
		mark.setMaxSize(48, 48);
		mark.setStyle(
				"-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 16px; "
						+ "-fx-border-color: rgba(255,255,255,0.10); -fx-border-radius: 16px;");

		VBox column = new VBox(12, mark, title, detail);
		column.setAlignment(Pos.CENTER);
		column.setMaxWidth(240);
		getChildren().add(column);
		setAlignment(Pos.CENTER);
		setPadding(new javafx.geometry.Insets(24));
	}
}
