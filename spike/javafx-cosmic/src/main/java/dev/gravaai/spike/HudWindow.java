package dev.gravaai.spike;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/** Floating recording HUD: transparent, undecorated, always on top, draggable by its handle. */
final class HudWindow {
	private static final String MIC = "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM6 11a6 6 0 0 0 12 0h-2a4 4 0 0 1-8 0zm5 7h2v3h-2z";
	private static final String CAMERA = "M4 7h11a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1zm13 3 4-3v10l-4-3z";
	private static final String TIMER = "M12 5a8 8 0 1 0 0 16 8 8 0 0 0 0-16zm0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12zm-1 2v4.4l3.3 2 1-1.6-2.3-1.4V9zM9 1h6v2H9z";

	private final Stage stage = new Stage(StageStyle.TRANSPARENT);
	private final EditorWindow editor;
	private final Button record = new Button("Record");
	private final Timeline clock;
	private int elapsedSeconds;
	private boolean recording;
	private double dragOffsetX;
	private double dragOffsetY;
	private X11Bridge x11;
	private long xWindow;

	HudWindow(EditorWindow editor) {
		this.editor = editor;
		clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
		clock.setCycleCount(Timeline.INDEFINITE);

		GridPane handle = dragHandle();
		Label display = new Label(primaryDisplayName());
		display.getStyleClass().add("hud-display");

		record.getStyleClass().add("hud-record");
		record.setGraphic(new Circle(5, Color.WHITE));
		record.setOnAction(e -> toggleRecording());

		HBox pill = new HBox(6, handle, display, ghost(MIC, "Microphone"), ghost(CAMERA, "Webcam"), ghost(TIMER, "Countdown"), record);
		pill.getStyleClass().add("hud-pill");
		pill.setAlignment(Pos.CENTER_LEFT);

		// Margin leaves room for the drop shadow inside the transparent window.
		StackPane root = new StackPane(pill);
		root.setPadding(new Insets(14));
		root.setStyle("-fx-background-color: transparent;");

		Scene scene = new Scene(root);
		scene.setFill(Color.TRANSPARENT);
		scene.getStylesheets().add(HudWindow.class.getResource("spike.css").toExternalForm());
		stage.setScene(scene);
		stage.setTitle("Gravaai HUD");
		stage.setAlwaysOnTop(true);
		stage.setResizable(false);

		stage.alwaysOnTopProperty().addListener((o, a, v) -> Log.info("hud alwaysOnTop=" + v));
		stage.focusedProperty().addListener((o, a, v) -> Log.info("hud focused=" + v));
		stage.xProperty().addListener((o, a, v) -> logPosition());
		stage.yProperty().addListener((o, a, v) -> logPosition());
	}

	void show() {
		stage.show();
		Rectangle2D area = Screen.getPrimary().getVisualBounds();
		stage.setX(area.getMinX() + (area.getWidth() - stage.getWidth()) / 2);
		stage.setY(area.getMaxY() - stage.getHeight() - 24);
		Log.info("hud shown " + stage.getWidth() + "x" + stage.getHeight() + " alwaysOnTop=" + stage.isAlwaysOnTop()
				+ " requested at " + stage.getX() + "," + stage.getY());

		// COSMIC drops JavaFX's keep-above; ask its window manager directly once the window is mapped.
		Timeline afterMap = new Timeline(new KeyFrame(Duration.millis(300), e -> {
			try {
				x11 = new X11Bridge();
				xWindow = x11.findWindow(stage.getTitle());
				Log.info("hud x11 window 0x" + Long.toHexString(xWindow));
				if (xWindow != 0) {
					x11.keepAbove(xWindow);
				}
			} catch (Throwable t) {
				Log.error("x11 bridge unavailable", t);
			}
		}));
		afterMap.play();
	}

	private void logPosition() {
		Log.info("hud position " + stage.getX() + "," + stage.getY());
	}

	private GridPane dragHandle() {
		GridPane dots = new GridPane();
		dots.setHgap(3);
		dots.setVgap(3);
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 2; col++) {
				dots.add(new Circle(1.6, Color.web("#8b929a")), col, row);
			}
		}
		dots.getStyleClass().add("hud-handle");
		// COSMIC reverts client-side moves, so the drag is handed to the window manager when possible.
		dots.setOnMousePressed(e -> {
			dragOffsetX = e.getScreenX() - stage.getX();
			dragOffsetY = e.getScreenY() - stage.getY();
			if (x11 != null && xWindow != 0) {
				try {
					x11.beginMove(xWindow, (int) e.getScreenX(), (int) e.getScreenY());
					return;
				} catch (Throwable t) {
					Log.error("wm move failed", t);
				}
			}
		});
		dots.setOnMouseDragged(e -> {
			if (x11 == null || xWindow == 0) {
				stage.setX(e.getScreenX() - dragOffsetX);
				stage.setY(e.getScreenY() - dragOffsetY);
			}
		});
		return dots;
	}

	private static Button ghost(String path, String tooltip) {
		SVGPath icon = new SVGPath();
		icon.setContent(path);
		icon.getStyleClass().add("hud-icon");
		Button button = new Button();
		button.setGraphic(icon);
		button.getStyleClass().add("hud-ghost");
		button.setTooltip(new Tooltip(tooltip));
		button.setOnAction(e -> Log.info("hud " + tooltip + " clicked"));
		return button;
	}

	private void toggleRecording() {
		recording = !recording;
		elapsedSeconds = 0;
		if (recording) {
			record.getStyleClass().add("recording");
			clock.playFromStart();
		} else {
			record.getStyleClass().remove("recording");
			clock.stop();
		}
		updateLabels();
		Log.info("hud recording=" + recording);
	}

	private void tick() {
		elapsedSeconds++;
		updateLabels();
	}

	private void updateLabels() {
		String elapsed = String.format("%d:%02d", elapsedSeconds / 60, elapsedSeconds % 60);
		record.setText(recording ? elapsed : "Record");
		editor.setRecording(recording, elapsed);
	}

	private static String primaryDisplayName() {
		Rectangle2D bounds = Screen.getPrimary().getBounds();
		return String.format("Display %.0f×%.0f", bounds.getWidth(), bounds.getHeight());
	}
}
