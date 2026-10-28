package dev.gravaai.spike;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

/** Editor-shaped window: toolbar, GL preview, settings panel and a timeline strip with a live playhead. */
final class EditorWindow {
	private static final double TIMELINE_SECONDS = 30;

	private final Stage stage;
	private final GlPreviewRenderer renderer;
	private final Label stats = new Label("starting…");
	private final Label recordingState = new Label("Idle");

	EditorWindow(Stage stage, GlPreviewRenderer renderer) {
		this.stage = stage;
		this.renderer = renderer;

		ImageView preview = new ImageView();
		preview.setPreserveRatio(true);
		preview.setSmooth(true);
		renderer.attach(preview);

		StackPane previewPane = new StackPane(preview, stats);
		previewPane.getStyleClass().add("preview");
		StackPane.setAlignment(stats, Pos.TOP_LEFT);
		StackPane.setMargin(stats, new Insets(10));
		stats.getStyleClass().add("stats");
		preview.fitWidthProperty().bind(previewPane.widthProperty().subtract(32));
		preview.fitHeightProperty().bind(previewPane.heightProperty().subtract(32));
		previewPane.setMinSize(0, 0);

		BorderPane root = new BorderPane();
		root.getStyleClass().add("editor");
		root.setTop(toolbar());
		root.setCenter(previewPane);
		root.setRight(settingsPanel());
		root.setBottom(timeline());

		Scene scene = new Scene(root, 1280, 800);
		scene.getStylesheets().add(EditorWindow.class.getResource("spike.css").toExternalForm());
		stage.setScene(scene);
		stage.setTitle("Gravaai — Java spike");
		stage.setMinWidth(900);
		stage.setMinHeight(600);
	}

	void show() {
		stage.show();
		Log.info("startup: editor shown at " + Log.sinceProcessStart() + " ms");
		Platform.runLater(() -> Log.info("startup: editor first pulse done at " + Log.sinceProcessStart() + " ms"));
		Log.info("editor shown at " + stage.getX() + "," + stage.getY() + " " + stage.getWidth() + "x" + stage.getHeight()
				+ " renderScale=" + stage.getRenderScaleX());
	}

	void setRecording(boolean recording, String elapsed) {
		recordingState.setText(recording ? "Recording " + elapsed : "Idle");
	}

	private HBox toolbar() {
		Label title = new Label("Untitled project");
		title.getStyleClass().add("title");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		recordingState.getStyleClass().add("muted");
		Button export = new Button("Export");
		export.getStyleClass().add("accent");
		export.setOnAction(e -> Log.info("export clicked (spike: no-op)"));
		HBox bar = new HBox(12, title, spacer, recordingState, export);
		bar.getStyleClass().add("toolbar");
		bar.setAlignment(Pos.CENTER_LEFT);
		return bar;
	}

	private VBox settingsPanel() {
		Slider zoom = new Slider(1.0, 3.0, 1.0);
		zoom.valueProperty().addListener((o, a, v) -> renderer.manualZoom = v.floatValue());
		Slider padding = new Slider(0.0, 0.2, renderer.padding);
		padding.valueProperty().addListener((o, a, v) -> renderer.padding = v.floatValue());
		CheckBox autoZoom = new CheckBox("Auto zoom on cursor");
		autoZoom.setSelected(renderer.autoZoom);
		autoZoom.selectedProperty().addListener((o, a, v) -> renderer.autoZoom = v);
		CheckBox blur = new CheckBox("Motion blur");
		blur.setSelected(renderer.motionBlur);
		blur.selectedProperty().addListener((o, a, v) -> renderer.motionBlur = v);

		VBox panel = new VBox(10,
				section("Zoom"), zoom, autoZoom, blur,
				section("Background"), new Label("Padding"), padding);
		panel.getStyleClass().add("panel");
		panel.setPrefWidth(280);
		return panel;
	}

	private static Label section(String text) {
		Label label = new Label(text.toUpperCase());
		label.getStyleClass().add("section");
		return label;
	}

	private Pane timeline() {
		Pane track = new Pane();
		track.getStyleClass().add("timeline");
		track.setPrefHeight(120);

		Rectangle clip = new Rectangle();
		clip.getStyleClass().add("clip");
		clip.setY(20);
		clip.setHeight(36);
		clip.setArcWidth(10);
		clip.setArcHeight(10);
		clip.setX(16);
		clip.widthProperty().bind(track.widthProperty().subtract(32));

		Rectangle zoomRegion = new Rectangle();
		zoomRegion.getStyleClass().add("zoom-region");
		zoomRegion.setY(66);
		zoomRegion.setHeight(24);
		zoomRegion.setArcWidth(10);
		zoomRegion.setArcHeight(10);

		Line playhead = new Line();
		playhead.getStyleClass().add("playhead");
		playhead.setStartY(8);
		playhead.endYProperty().bind(track.heightProperty().subtract(8));

		track.getChildren().addAll(clip, zoomRegion, playhead);

		new AnimationTimer() {
			private long windowStart;
			private long pulses;
			private long presentedAtStart;

			@Override
			public void handle(long now) {
				double usable = track.getWidth() - 32;
				double t = renderer.timeSeconds % TIMELINE_SECONDS;
				double x = 16 + usable * t / TIMELINE_SECONDS;
				playhead.setStartX(x);
				playhead.setEndX(x);
				// The auto-zoom cycle is 6 s with zoom held from 0.8 s to 3.2 s.
				double cycleStart = t - (t % 6);
				zoomRegion.setX(16 + usable * cycleStart / TIMELINE_SECONDS);
				zoomRegion.setWidth(usable * 4.0 / TIMELINE_SECONDS);

				pulses++;
				if (windowStart == 0) {
					windowStart = now;
					presentedAtStart = renderer.framesPresented.get();
				} else if (now - windowStart >= 1_000_000_000L) {
					double seconds = (now - windowStart) / 1e9;
					long presented = renderer.framesPresented.get();
					String line = renderer.failure != null
							? "GL failed: " + renderer.failure
							: String.format("%s%npulse %.0f fps · presented %.0f fps · draw %.2f ms · readback+copy %.2f ms",
									renderer.glRenderer, pulses / seconds, (presented - presentedAtStart) / seconds,
									renderer.renderMillis, renderer.readbackMillis);
					stats.setText(line);
					if ((now / 1_000_000_000L) % 5 == 0) {
						Log.info("stats " + line.replace('\n', ' '));
					}
					windowStart = now;
					pulses = 0;
					presentedAtStart = presented;
				}
			}
		}.start();
		return track;
	}
}
