package dev.gravaai.ui.editor;

import dev.gravaai.ui.kit.Dialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/**
 * "How trimming works" dialog (port of {@code TutorialHelp.tsx}): explanation, the kept/removed
 * visual example and the two-step instructions.
 */
public final class TutorialHelpDialog {

	private final Dialog dialog;

	public TutorialHelpDialog() {
		dialog = new Dialog("How Trimming Works");
		dialog.description("Understanding how to cut out unwanted parts of your video.");
		dialog.width(620);

		Label explanation = new Label(
				"The Trim tool works by defining the segments you want to remove. "
						+ "Any part of the timeline that is covered by a red trim segment will be "
						+ "cut out when you export.");
		explanation.setWrapText(true);
		explanation.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
		VBox explanationCard = new VBox(explanation);
		explanationCard.setPadding(new Insets(16));
		explanationCard.setStyle("-fx-background-color: rgba(255,255,255,0.04); "
				+ "-fx-background-radius: 8px; -fx-border-color: rgba(255,255,255,0.05); "
				+ "-fx-border-radius: 8px;");

		Label visualTitle = new Label("VISUAL EXAMPLE");
		visualTitle.setStyle("-fx-font-size: 11px; -fx-text-fill: -g-muted; "
				+ "-fx-letter-spacing: 1px;");

		Pane track = new Pane();
		track.setPrefHeight(96);
		track.setMinHeight(96);
		track.setStyle("-fx-background-color: #000000; -fx-background-radius: 8px; "
				+ "-fx-border-color: rgba(255,255,255,0.10); -fx-border-radius: 8px;");
		Rectangle kept = new Rectangle();
		kept.setHeight(8);
		kept.setArcWidth(8);
		kept.setArcHeight(8);
		kept.setFill(Color.web("#475569"));
		kept.layoutXProperty().set(48);
		kept.layoutYProperty().set(44);
		kept.widthProperty().bind(track.widthProperty().subtract(96));
		track.getChildren().add(kept);
		track.getChildren().add(removedSegment(track, 0.2, 0.2));
		track.getChildren().add(removedSegment(track, 0.65, 0.15));
		track.getChildren().addAll(keptLabel(track, 0.05), keptLabel(track, 0.5),
				keptLabel(track, 0.9));

		Line arrow = new Line(0, 0, 0, 14);
		arrow.setStroke(Color.web("#8b929a"));
		HBox arrowRow = new HBox(arrow);
		arrowRow.setAlignment(Pos.CENTER);
		arrowRow.setPadding(new Insets(2, 0, 2, 0));

		HBox result = new HBox(4);
		result.setMinHeight(48);
		result.setPrefHeight(48);
		result.setAlignment(Pos.CENTER);
		result.setStyle("-fx-background-color: #000000; -fx-background-radius: 8px; "
				+ "-fx-border-color: rgba(255,255,255,0.10); -fx-border-radius: 8px;");
		for (int part = 1; part <= 3; part++) {
			Label label = new Label("Part " + part);
			label.setStyle("-fx-font-size: 11px; -fx-text-fill: #ffffff;");
			StackPane box = new StackPane(label);
			box.setPrefSize(150, 32);
			box.setStyle("-fx-background-color: #334155; -fx-background-radius: 4px;");
			result.getChildren().add(box);
		}

		HBox steps = new HBox(16,
				stepCard("1. Add Trim",
						"Press T or click the scissors icon to mark a section for removal."),
				stepCard("2. Adjust",
						"Drag the edges of the red region to cover exactly what you want to cut out."));
		steps.setAlignment(Pos.TOP_LEFT);
		HBox.setHgrow(steps.getChildren().get(0), javafx.scene.layout.Priority.ALWAYS);
		HBox.setHgrow(steps.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);

		VBox content = new VBox(20, explanationCard,
				new VBox(8, visualTitle, track, arrowRow, result), steps);
		dialog.content(content);
	}

	/** Opens the dialog inside the kit root that owns {@code owner}. */
	public void show(Node owner) {
		dialog.show(owner);
	}

	public Dialog dialog() {
		return dialog;
	}

	private static Node removedSegment(Pane track, double leftFraction, double widthFraction) {
		Rectangle removed = new Rectangle();
		removed.setHeight(32);
		removed.setArcWidth(6);
		removed.setArcHeight(6);
		removed.setFill(Color.web("#ef4444", 0.2));
		removed.setStroke(Color.web("#ef4444"));
		removed.layoutXProperty().bind(track.widthProperty().multiply(leftFraction));
		removed.layoutYProperty().set(32);
		removed.widthProperty().bind(track.widthProperty().multiply(widthFraction));
		StackPane host = new StackPane(removed);
		Label label = new Label("REMOVED");
		label.setStyle("-fx-font-size: 9px; -fx-font-weight: 700; -fx-text-fill: #ef4444; "
				+ "-fx-background-color: rgba(0,0,0,0.5); -fx-background-radius: 3px; "
				+ "-fx-padding: 1 4 1 4;");
		label.layoutXProperty().bind(removed.layoutXProperty().add(6));
		label.layoutYProperty().set(39);
		Pane layer = new Pane(removed, label);
		return layer;
	}

	private static Node keptLabel(Pane track, double leftFraction) {
		Label kept = new Label("Kept");
		kept.setStyle("-fx-font-size: 10px; -fx-text-fill: -g-muted;");
		kept.layoutXProperty().bind(track.widthProperty().multiply(leftFraction));
		kept.layoutYProperty().set(70);
		return kept;
	}

	private static Node stepCard(String title, String description) {
		Label heading = new Label(title);
		heading.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #ef4444;");
		Label body = new Label(description);
		body.setWrapText(true);
		body.setStyle("-fx-font-size: 11px; -fx-text-fill: -g-muted;");
		VBox card = new VBox(5, heading, body);
		card.setPadding(new Insets(12));
		card.setStyle("-fx-background-color: rgba(255,255,255,0.04); "
				+ "-fx-background-radius: 6px; -fx-border-color: rgba(255,255,255,0.05); "
				+ "-fx-border-radius: 6px;");
		card.setMaxWidth(Double.MAX_VALUE);
		return card;
	}

	/** Unused-import guards for the layout helpers above. */
	static Region spacer() {
		return new Region();
	}
}
