package dev.gravaai.ui.overlays.ink;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.stage.Stage;
import javafx.util.Duration;

import dev.gravaai.services.recording.InkLog;
import dev.gravaai.services.recording.InkTelemetry.InkTool;
import dev.gravaai.ui.i18n.UiI18n;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.overlays.OverlayStage;

/**
 * Draw mode: a transparent overlay over the recorded monitor that the presenter draws on with the
 * mouse or a tablet. The screen capture records it like any other window, so the ink is in the
 * video exactly as it was seen; every stroke is also reported to the {@link InkLog} for the
 * {@code .ink.json} sidecar.
 *
 * <p>The stage is requested at the monitor's full bounds, but window managers may clamp it (COSMIC
 * keeps XWayland windows inside the work area), so the canvas follows the stage's real size and
 * strokes are reported in screen coordinates taken from the events themselves. Staying on top is a
 * desktop difference and belongs to the {@link InkStacking} passed in.
 *
 * <p>Leaving draw mode fades the ink out and closes the overlay: on a desktop that ignores
 * keep-above, ink left behind a click-through overlay would drop under the next window clicked.
 *
 * <p>FX thread only.
 */
public final class InkOverlay {

	/** The palette's colours, in the order the {@code 1}–{@code 6} keys pick them. */
	static final List<String> COLORS = List.of("#ff3b30", "#ffcc00", "#34c759", "#0a84ff", "#ffffff", "#000000");
	/** Line widths in logical pixels, thin to thick. */
	static final double[] WIDTHS = {4, 8, 14};

	private static final String TITLE = "gravaai-ink-overlay";
	private static final Duration FADE_OUT = Duration.millis(250);

	private final InkLog log;
	private final InkStacking stacking;
	private final Runnable onClosed;
	private final Stage stage;
	private final Pane root = new Pane();
	private final Canvas canvas = new Canvas();
	private final HBox palette = new HBox(6);
	private final List<DrawnStroke> strokes = new ArrayList<>();
	private final List<Region> swatches = new ArrayList<>();
	private final List<Region> widthDots = new ArrayList<>();

	private int colorIndex;
	private int widthIndex = 1;
	private @Nullable DrawnStroke current;
	private boolean active = true;
	private double paletteDragX;
	private double paletteDragY;
	/** The area being recorded, in screen coordinates; ink outside it is not drawn. */
	private final Optional<Rectangle2D> area;

	/** A stroke on screen, in scene coordinates. */
	private static final class DrawnStroke {
		final Optional<String> id;
		final Color color;
		final double width;
		final List<double[]> points = new ArrayList<>();

		DrawnStroke(Optional<String> id, Color color, double width) {
			this.id = id;
			this.color = color;
			this.width = width;
		}
	}

	private InkOverlay(InkLog log, InkStacking stacking, Optional<Rectangle2D> area, Runnable onClosed) {
		this.log = log;
		this.stacking = stacking;
		this.area = area;
		this.onClosed = onClosed;
		this.stage = OverlayStage.create(TITLE);
	}

	/**
	 * Opens draw mode over one monitor.
	 *
	 * @param monitor the monitor's full bounds in screen coordinates
	 * @param area the recorded area when recording an area; ink is kept inside it
	 * @param onClosed runs once the overlay has closed, however it was closed
	 */
	public static InkOverlay open(InkLog log, InkStacking stacking, Rectangle2D monitor,
			Optional<Rectangle2D> area, Runnable onClosed) {
		OverlayStage.requireFxThread();
		InkOverlay overlay = new InkOverlay(log, stacking, area, onClosed);
		overlay.show(monitor);
		return overlay;
	}

	/** True until the overlay starts closing. */
	public boolean isActive() {
		return active;
	}

	/** Leaves draw mode: the ink fades out and the overlay closes. */
	public void close() {
		if (!active) {
			return;
		}
		active = false;
		if (current != null) {
			current.id.ifPresent(log::endStroke);
			current = null;
		}
		log.endSession();
		FadeTransition fade = new FadeTransition(FADE_OUT, root);
		fade.setToValue(0);
		fade.setOnFinished(event -> stage.close());
		fade.play();
	}

	// ── window ─────────────────────────────────────────────────────────────

	private void show(Rectangle2D monitor) {
		root.setStyle("-fx-background-color: transparent;");
		root.setCursor(Cursor.CROSSHAIR);
		root.getChildren().addAll(canvas, buildPalette());
		stage.setScene(OverlayStage.scene(root, monitor.getWidth(), monitor.getHeight()));
		stage.setX(monitor.getMinX());
		stage.setY(monitor.getMinY());
		stage.setWidth(monitor.getWidth());
		stage.setHeight(monitor.getHeight());
		// The window manager decides the final bounds; the canvas follows whatever it chose.
		stage.widthProperty().addListener((observable, was, now) -> resize());
		stage.heightProperty().addListener((observable, was, now) -> resize());
		stage.xProperty().addListener((observable, was, now) -> applyAreaClip());
		stage.yProperty().addListener((observable, was, now) -> applyAreaClip());
		stage.setOnHidden(event -> {
			active = false;
			log.endSession();
			onClosed.run();
		});

		root.addEventHandler(MouseEvent.MOUSE_PRESSED, this::onPress);
		root.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::onDrag);
		root.addEventHandler(MouseEvent.MOUSE_RELEASED, this::onRelease);
		stage.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

		log.beginSession();
		stage.show();
		stage.toFront();
		stage.requestFocus();
		resize();
		stacking.attach(stage, this::isActive);
	}

	private void resize() {
		double width = Math.max(1, stage.getWidth());
		double height = Math.max(1, stage.getHeight());
		canvas.setWidth(width);
		canvas.setHeight(height);
		palette.applyCss();
		palette.autosize();
		palette.relocate(Math.round((width - palette.getWidth()) / 2), 16);
		applyAreaClip();
		redraw();
	}

	/** Keeps the canvas inside the recorded area, mapped into the stage's real position. */
	private void applyAreaClip() {
		area.ifPresentOrElse(rect -> canvas.setClip(new Rectangle(rect.getMinX() - stage.getX(),
				rect.getMinY() - stage.getY(), rect.getWidth(), rect.getHeight())),
				// JavaFX's own "no clip" value: a whole-monitor recording keeps the full canvas.
				() -> canvas.setClip(null));
	}

	// ── drawing ────────────────────────────────────────────────────────────

	private void onPress(MouseEvent event) {
		if (!active || event.getButton() != MouseButton.PRIMARY || isOnPalette(event)) {
			return;
		}
		if (area.isPresent() && !area.get().contains(event.getScreenX(), event.getScreenY())) {
			return;
		}
		Color color = Color.web(COLORS.get(colorIndex));
		double width = WIDTHS[widthIndex];
		Optional<String> id = log.beginStroke(InkTool.PEN, COLORS.get(colorIndex), width,
				event.getScreenX(), event.getScreenY());
		DrawnStroke stroke = new DrawnStroke(id, color, width);
		stroke.points.add(new double[] {event.getX(), event.getY()});
		strokes.add(stroke);
		current = stroke;
		palette.setVisible(false);
		// A tap draws a dot: a zero-length round-capped line.
		drawSegment(canvas.getGraphicsContext2D(), stroke, 0);
		event.consume();
	}

	private void onDrag(MouseEvent event) {
		DrawnStroke stroke = current;
		if (stroke == null) {
			return;
		}
		double[] last = stroke.points.getLast();
		// Sub-pixel jitter adds points without adding shape.
		if (Math.hypot(event.getX() - last[0], event.getY() - last[1]) < 1) {
			return;
		}
		stroke.points.add(new double[] {event.getX(), event.getY()});
		stroke.id.ifPresent(id -> log.extendStroke(id, event.getScreenX(), event.getScreenY()));
		drawSegment(canvas.getGraphicsContext2D(), stroke, stroke.points.size() - 1);
		event.consume();
	}

	private void onRelease(MouseEvent event) {
		DrawnStroke stroke = current;
		if (stroke == null) {
			return;
		}
		current = null;
		stroke.id.ifPresent(log::endStroke);
		palette.setVisible(true);
		// The incremental segments end at the last midpoint; the full redraw closes the tail.
		redraw();
		event.consume();
	}

	private void undo() {
		if (current != null || strokes.isEmpty()) {
			return;
		}
		strokes.removeLast().id.ifPresent(log::removeStroke);
		redraw();
	}

	private void clear() {
		if (current != null) {
			return;
		}
		strokes.clear();
		log.clearAll();
		redraw();
	}

	private void redraw() {
		GraphicsContext graphics = canvas.getGraphicsContext2D();
		graphics.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
		for (DrawnStroke stroke : strokes) {
			for (int index = 0; index < stroke.points.size(); index++) {
				drawSegment(graphics, stroke, index);
			}
			if (stroke.points.size() > 1) {
				double[] tail = InkSmoothing.midpoint(stroke.points.get(stroke.points.size() - 2),
						stroke.points.getLast());
				double[] last = stroke.points.getLast();
				style(graphics, stroke);
				graphics.strokeLine(tail[0], tail[1], last[0], last[1]);
			}
		}
	}

	/** Draws the piece of {@code stroke} that point {@code index} adds (midpoint smoothing). */
	private static void drawSegment(GraphicsContext graphics, DrawnStroke stroke, int index) {
		style(graphics, stroke);
		InkSmoothing.Segment segment = InkSmoothing.segment(stroke.points, index);
		graphics.beginPath();
		graphics.moveTo(segment.fromX(), segment.fromY());
		graphics.quadraticCurveTo(segment.controlX(), segment.controlY(), segment.toX(), segment.toY());
		graphics.stroke();
	}

	private static void style(GraphicsContext graphics, DrawnStroke stroke) {
		graphics.setStroke(stroke.color);
		graphics.setLineWidth(stroke.width);
		graphics.setLineCap(StrokeLineCap.ROUND);
		graphics.setLineJoin(StrokeLineJoin.ROUND);
	}

	// ── keys ───────────────────────────────────────────────────────────────

	private void onKey(KeyEvent event) {
		KeyCode code = event.getCode();
		if (code == KeyCode.ESCAPE) {
			close();
		} else if (code == KeyCode.Z && event.isShortcutDown()) {
			undo();
		} else if (code == KeyCode.BACK_SPACE || code == KeyCode.DELETE) {
			clear();
		} else if (code == KeyCode.OPEN_BRACKET) {
			selectWidth(Math.max(0, widthIndex - 1));
		} else if (code == KeyCode.CLOSE_BRACKET) {
			selectWidth(Math.min(WIDTHS.length - 1, widthIndex + 1));
		} else if (code.isDigitKey()) {
			int digit = code.getCode() - KeyCode.DIGIT1.getCode();
			int numpad = code.getCode() - KeyCode.NUMPAD1.getCode();
			int index = digit >= 0 && digit < COLORS.size() ? digit
					: (numpad >= 0 && numpad < COLORS.size() ? numpad : -1);
			if (index < 0) {
				return;
			}
			selectColor(index);
		} else {
			return;
		}
		event.consume();
	}

	// ── palette ────────────────────────────────────────────────────────────

	private Region buildPalette() {
		palette.getStyleClass().add("ink-palette");
		palette.setAlignment(Pos.CENTER);
		palette.setPadding(new Insets(6, 10, 6, 10));
		palette.setStyle("-fx-background-color: rgba(28,28,30,0.92); -fx-background-radius: 999;"
				+ " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 2);");

		Region grip = new Region();
		grip.setPrefSize(10, 22);
		grip.setStyle("-fx-background-color: rgba(255,255,255,0.25); -fx-background-radius: 3;"
				+ " -fx-scale-x: 0.3;");
		grip.setCursor(Cursor.MOVE);
		grip.setOnMousePressed(event -> {
			paletteDragX = event.getSceneX() - palette.getLayoutX();
			paletteDragY = event.getSceneY() - palette.getLayoutY();
			event.consume();
		});
		grip.setOnMouseDragged(event -> {
			palette.relocate(event.getSceneX() - paletteDragX, event.getSceneY() - paletteDragY);
			event.consume();
		});
		palette.getChildren().add(grip);

		for (int index = 0; index < COLORS.size(); index++) {
			int chosen = index;
			Region swatch = new Region();
			swatch.setPrefSize(20, 20);
			swatch.setCursor(Cursor.HAND);
			Tooltip.install(swatch, new Tooltip(UiI18n.t("launch:ink.color") + " " + (index + 1)));
			swatch.setOnMouseClicked(event -> {
				selectColor(chosen);
				event.consume();
			});
			swatches.add(swatch);
			palette.getChildren().add(swatch);
		}
		palette.getChildren().add(separator());
		for (int index = 0; index < WIDTHS.length; index++) {
			int chosen = index;
			Region dot = new Region();
			dot.setPrefSize(24, 24);
			dot.setCursor(Cursor.HAND);
			Circle mark = new Circle(Math.max(2, WIDTHS[index] / 2));
			mark.setFill(Color.WHITE);
			mark.setMouseTransparent(true);
			Pane holder = new Pane(dot, mark);
			mark.setCenterX(12);
			mark.setCenterY(12);
			Tooltip.install(dot, new Tooltip(UiI18n.t("launch:ink.width")));
			dot.setOnMouseClicked(event -> {
				selectWidth(chosen);
				event.consume();
			});
			widthDots.add(dot);
			palette.getChildren().add(holder);
		}
		palette.getChildren().add(separator());
		palette.getChildren().addAll(
				iconButton(Icons.ArrowCounterClockwise, UiI18n.t("launch:ink.undo"), this::undo),
				iconButton(Icons.Trash, UiI18n.t("launch:ink.clear"), this::clear),
				iconButton(Icons.XIcon, UiI18n.t("launch:ink.stopDrawing"), this::close));
		palette.addEventHandler(MouseEvent.MOUSE_PRESSED, MouseEvent::consume);
		paintPalette();
		return palette;
	}

	private static Region separator() {
		Region line = new Region();
		line.setPrefSize(1, 20);
		line.setStyle("-fx-background-color: rgba(255,255,255,0.18);");
		HBox.setMargin(line, new Insets(0, 2, 0, 2));
		return line;
	}

	private static Button iconButton(String icon, String tooltip, Runnable action) {
		Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		Icon graphic = Icon.of(icon, 16);
		graphic.setStyle("-fx-text-fill: white;");
		button.icon(graphic);
		button.setAccessibleText(tooltip);
		Tooltip.install(button, new Tooltip(tooltip));
		button.setOnAction(event -> action.run());
		button.setFocusTraversable(false);
		return button;
	}

	private void selectColor(int index) {
		colorIndex = index;
		paintPalette();
	}

	private void selectWidth(int index) {
		widthIndex = index;
		paintPalette();
	}

	private void paintPalette() {
		for (int index = 0; index < swatches.size(); index++) {
			boolean selected = index == colorIndex;
			swatches.get(index).setStyle("-fx-background-color: " + COLORS.get(index) + ";"
					+ " -fx-background-radius: 999; -fx-border-radius: 999;"
					+ " -fx-border-color: " + (selected ? "white" : "rgba(255,255,255,0.3)") + ";"
					+ " -fx-border-width: " + (selected ? 2.5 : 1) + ";");
		}
		for (int index = 0; index < widthDots.size(); index++) {
			widthDots.get(index).setStyle(index == widthIndex
					? "-fx-background-color: rgba(255,255,255,0.18); -fx-background-radius: 999;"
					: "-fx-background-color: transparent;");
		}
	}

	private boolean isOnPalette(MouseEvent event) {
		return palette.isVisible()
				&& palette.getBoundsInParent().contains(event.getX(), event.getY());
	}
}
