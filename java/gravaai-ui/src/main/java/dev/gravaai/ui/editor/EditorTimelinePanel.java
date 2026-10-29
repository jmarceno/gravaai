package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;

/**
 * Timeline strip below the preview (port of {@code layout/EditorTimelinePanel.tsx}):
 * 22% of the window height, min 180 / max 280. The dashboard teammate's
 * {@code dev.gravaai.ui.editor.timeline.TimelineView} is mounted through {@link #setContent};
 * until it is wired, the empty-timeline visual from the reference screenshot is rendered.
 */
public final class EditorTimelinePanel extends VBox {

	private final Pane host = new Pane();
	private @Nullable Node content;

	public EditorTimelinePanel() {
		getStyleClass().add("editor-timeline");
		VBox.setVgrow(host, javafx.scene.layout.Priority.ALWAYS);
		host.setMinHeight(0);
		// The timeline scrolls its own rows; nothing may paint past the panel (React overflow-hidden).
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
		clip.widthProperty().bind(host.widthProperty());
		clip.heightProperty().bind(host.heightProperty());
		host.setClip(clip);
		getChildren().add(host);
		setEmptyTimeline();
	}

	/** Mounts the real timeline view (or any host node). */
	public void setContent(Node timelineContent) {
		this.content = timelineContent;
		host.getChildren().setAll(timelineContent == null ? emptyTimeline() : timelineContent);
		if (timelineContent != null) {
			anchorToHost(timelineContent);
		}
	}

	public @Nullable Node content() {
		return content;
	}

	private void setEmptyTimeline() {
		host.getChildren().setAll(emptyTimeline());
	}

	private void anchorToHost(Node node) {
		if (node instanceof Region region) {
			region.prefWidthProperty().bind(host.widthProperty());
			region.prefHeightProperty().bind(host.heightProperty());
		}
	}

	/** Empty timeline: red playhead over a bordered clip row, like the reference screenshot. */
	private Pane emptyTimeline() {
		Pane root = new Pane();
		root.prefHeightProperty().bind(host.heightProperty().subtract(8));
		root.prefWidthProperty().bind(host.widthProperty());
		root.setMinHeight(0);

		Region track = new Region();
		track.getStyleClass().add("editor-timeline__track");
		track.setLayoutY(40);
		track.setLayoutX(12);
		track.prefWidthProperty().bind(root.widthProperty().subtract(24));
		track.setPrefHeight(58);
		track.setMinHeight(58);

		Region leftHandle = new Region();
		leftHandle.getStyleClass().add("editor-timeline__handle");
		leftHandle.setPrefSize(4, 44);
		leftHandle.setLayoutX(16);
		leftHandle.setLayoutY(47);

		Region rightHandle = new Region();
		rightHandle.getStyleClass().add("editor-timeline__handle");
		rightHandle.setPrefSize(4, 44);
		rightHandle.layoutXProperty().bind(root.widthProperty().subtract(20));
		rightHandle.setLayoutY(47);

		double playheadX = 28;
		Polygon triangle = new Polygon(playheadX - 6, 0, playheadX + 6, 0, playheadX, 8);
		triangle.getStyleClass().add("editor-timeline__playhead");
		Line line = new Line(playheadX, 0, playheadX, 0);
		line.getStyleClass().add("editor-timeline__playhead");
		line.setStroke(Color.web("#ef4444"));
		line.setStrokeWidth(1.5);
		line.endYProperty().bind(root.heightProperty());

		root.getChildren().addAll(track, leftHandle, rightHandle, line, triangle);
		return root;
	}
}
