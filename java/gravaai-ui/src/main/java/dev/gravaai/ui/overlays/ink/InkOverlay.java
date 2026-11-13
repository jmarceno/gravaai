package dev.gravaai.ui.overlays.ink;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
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
 * <p>Tools: pen (Shift for a straight line), highlighter, arrow, rectangle and ellipse. With
 * disappearing ink on, each stroke fades by itself a few seconds after it is drawn.
 *
 * <p>Two canvases: finished strokes on the base layer, the stroke in progress on a live layer
 * redrawn on every pointer move, so a long session never redraws everything per event and
 * translucent strokes do not darken where their own segments overlap.
 *
 * <p>The stage is requested at the monitor's full bounds, but window managers may clamp it (COSMIC
 * keeps XWayland windows inside the work area), so the canvases follow the stage's real size and
 * strokes are reported in screen coordinates taken from the events themselves. When an area is
 * recorded, ink and the palette are kept inside that rectangle, because only the area is captured.
 * Staying on top is a desktop difference and belongs to the {@link InkStacking} passed in.
 *
 * <p>FX thread only.
 */
public final class InkOverlay {

	/** The palette's colours, in the order the {@code 1}–{@code 6} keys pick them. */
	static final List<String> COLORS = List.of("#ff3b30", "#ffcc00", "#34c759", "#0a84ff", "#ffffff", "#000000");
	/** Line widths in logical pixels, thin to thick, for the pen and the shapes. */
	static final double[] PEN_WIDTHS = {4, 8, 14};
	/** The highlighter's widths: a marker, not a pen. */
	static final double[] HIGHLIGHTER_WIDTHS = {18, 28, 40};
	static final double HIGHLIGHTER_ALPHA = 0.4;
	/** How long a stroke stays before it fades, with disappearing ink on. */
	static final Duration DISAPPEAR_AFTER = Duration.seconds(3);
	private static final Duration DISAPPEAR_FADE = Duration.millis(400);

	private static final String TITLE = "gravaai-ink-overlay";
	private static final Duration FADE_OUT = Duration.millis(250);
	private static final List<InkTool> TOOLS = List.of(InkTool.PEN, InkTool.HIGHLIGHTER, InkTool.ARROW,
			InkTool.RECTANGLE, InkTool.ELLIPSE);

	private final InkLog log;
	private final InkStacking stacking;
	private final Runnable onClosed;
	private final Consumer<InkPreferences> onPreferences;
	private final Stage stage;
	private final Pane root = new Pane();
	private final Canvas base = new Canvas();
	private final Canvas live = new Canvas();
	private final HBox palette = new HBox(6);
	private final List<DrawnStroke> strokes = new ArrayList<>();
	private final List<Button> toolButtons = new ArrayList<>();
	private final List<Region> swatches = new ArrayList<>();
	private final List<Region> widthDots = new ArrayList<>();
	private final List<Circle> widthMarks = new ArrayList<>();
	private final Button disappearButton = new Button("", Button.Variant.GHOST, Button.Size.ICON);
	/** The area being recorded, in screen coordinates; ink outside it is not drawn. */
	private final Optional<Rectangle2D> area;

	private InkPreferences preferences;
	private @Nullable DrawnStroke current;
	private boolean active = true;
	private double paletteDragX;
	private double paletteDragY;

	/** A stroke on screen, in scene coordinates. */
	private static final class DrawnStroke {
		final Optional<String> id;
		final InkTool tool;
		final Color color;
		final double width;
		/** A two-point stroke: a shape, or a pen line drawn with Shift held. */
		final boolean twoPoint;
		final List<double[]> points = new ArrayList<>();
		double opacity = 1;

		DrawnStroke(Optional<String> id, InkTool tool, Color color, double width, boolean twoPoint) {
			this.id = id;
			this.tool = tool;
			this.color = color;
			this.width = width;
			this.twoPoint = twoPoint;
		}
	}

	private InkOverlay(InkLog log, InkStacking stacking, Optional<Rectangle2D> area, InkPreferences preferences,
			Consumer<InkPreferences> onPreferences, Runnable onClosed) {
		this.log = log;
		this.stacking = stacking;
		this.area = area;
		this.preferences = preferences;
		this.onPreferences = onPreferences;
		this.onClosed = onClosed;
		this.stage = OverlayStage.create(TITLE);
	}

	/**
	 * Opens draw mode over one monitor.
	 *
	 * @param monitor the monitor's full bounds in screen coordinates
	 * @param area the recorded area when recording an area; ink is kept inside it
	 * @param preferences the palette as it was last left
	 * @param onPreferences receives every palette change, to remember it
	 * @param onClosed runs once the overlay has closed, however it was closed
	 */
	public static InkOverlay open(InkLog log, InkStacking stacking, Rectangle2D monitor,
			Optional<Rectangle2D> area, InkPreferences preferences, Consumer<InkPreferences> onPreferences,
			Runnable onClosed) {
		OverlayStage.requireFxThread();
		InkOverlay overlay = new InkOverlay(log, stacking, area, preferences, onPreferences, onClosed);
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
		live.setMouseTransparent(true);
		root.getChildren().addAll(base, live, buildPalette());
		stage.setScene(OverlayStage.scene(root, monitor.getWidth(), monitor.getHeight()));
		stage.setX(monitor.getMinX());
		stage.setY(monitor.getMinY());
		stage.setWidth(monitor.getWidth());
		stage.setHeight(monitor.getHeight());
		// The window manager decides the final bounds; the canvases follow whatever it chose.
		stage.widthProperty().addListener((observable, was, now) -> resize());
		stage.heightProperty().addListener((observable, was, now) -> resize());
		stage.xProperty().addListener((observable, was, now) -> followWindowManager());
		stage.yProperty().addListener((observable, was, now) -> followWindowManager());
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
		for (Canvas canvas : List.of(base, live)) {
			canvas.setWidth(width);
			canvas.setHeight(height);
		}
		palette.applyCss();
		palette.autosize();
		placePalette();
		applyAreaClip();
		redrawBase();
		redrawLive();
	}

	/**
	 * The window manager moved or resized the stage against the requested bounds: the canvases and
	 * the palette follow the real bounds, because the area is fixed on the monitor underneath.
	 */
	private void followWindowManager() {
		applyAreaClip();
		placePalette();
	}

	/** Hangs the palette top-centre of what is recorded: the area, or the whole stage. */
	private void placePalette() {
		if (palette.getWidth() <= 0 || !(stage.getWidth() > 0)) {
			return;
		}
		InkPalettePlacement.Position position = InkPalettePlacement.initial(stageBounds(), area,
				palette.getWidth(), palette.getHeight());
		palette.relocate(Math.round(position.x()), Math.round(position.y()));
	}

	/** The stage's real bounds in screen coordinates. */
	private Rectangle2D stageBounds() {
		return new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
	}

	/** Keeps the canvases inside the recorded area, mapped into the stage's real position. */
	private void applyAreaClip() {
		for (Canvas canvas : List.of(base, live)) {
			area.ifPresentOrElse(rect -> canvas.setClip(new Rectangle(rect.getMinX() - stage.getX(),
					rect.getMinY() - stage.getY(), rect.getWidth(), rect.getHeight())),
					// JavaFX's own "no clip" value: a whole-monitor recording keeps the full canvas.
					() -> canvas.setClip(null));
		}
	}

	// ── drawing ────────────────────────────────────────────────────────────

	private double strokeWidth(InkTool tool) {
		double[] widths = tool == InkTool.HIGHLIGHTER ? HIGHLIGHTER_WIDTHS : PEN_WIDTHS;
		return widths[preferences.widthIndex()];
	}

	private void onPress(MouseEvent event) {
		if (!active || event.getButton() != MouseButton.PRIMARY || isOnPalette(event)) {
			return;
		}
		if (area.isPresent() && !area.get().contains(event.getScreenX(), event.getScreenY())) {
			return;
		}
		InkTool tool = preferences.tool();
		String color = COLORS.get(preferences.colorIndex());
		double width = strokeWidth(tool);
		Optional<String> id = log.beginStroke(tool, color, width, event.getScreenX(), event.getScreenY());
		boolean twoPoint = tool.isShape() || (event.isShiftDown() && tool != InkTool.HIGHLIGHTER);
		DrawnStroke stroke = new DrawnStroke(id, tool, Color.web(color), width, twoPoint);
		stroke.points.add(new double[] {event.getX(), event.getY()});
		if (twoPoint) {
			stroke.points.add(new double[] {event.getX(), event.getY()});
		}
		current = stroke;
		palette.setVisible(false);
		redrawLive();
		event.consume();
	}

	private void onDrag(MouseEvent event) {
		DrawnStroke stroke = current;
		if (stroke == null) {
			return;
		}
		if (stroke.twoPoint) {
			stroke.points.set(1, new double[] {event.getX(), event.getY()});
		} else {
			double[] last = stroke.points.getLast();
			// Sub-pixel jitter adds points without adding shape.
			if (Math.hypot(event.getX() - last[0], event.getY() - last[1]) < 1) {
				return;
			}
			stroke.points.add(new double[] {event.getX(), event.getY()});
			stroke.id.ifPresent(id -> log.extendStroke(id, event.getScreenX(), event.getScreenY()));
		}
		redrawLive();
		event.consume();
	}

	private void onRelease(MouseEvent event) {
		DrawnStroke stroke = current;
		if (stroke == null) {
			return;
		}
		current = null;
		if (stroke.twoPoint) {
			// A shape is its press and its release.
			stroke.points.set(1, new double[] {event.getX(), event.getY()});
			stroke.id.ifPresent(id -> log.extendStroke(id, event.getScreenX(), event.getScreenY()));
		}
		stroke.id.ifPresent(log::endStroke);
		strokes.add(stroke);
		palette.setVisible(!preferences.paletteHidden());
		redrawLive();
		redrawBase();
		if (preferences.disappearing()) {
			scheduleDisappear(stroke);
		}
		event.consume();
	}

	/** Disappearing ink: the stroke waits, fades, then leaves the screen (and the log). */
	private void scheduleDisappear(DrawnStroke stroke) {
		PauseTransition wait = new PauseTransition(DISAPPEAR_AFTER);
		wait.setOnFinished(waited -> {
			if (!strokes.contains(stroke)) {
				return;
			}
			int frames = 12;
			Timeline fade = new Timeline();
			for (int frame = 1; frame <= frames; frame++) {
				double opacity = 1 - (double) frame / frames;
				fade.getKeyFrames().add(new KeyFrame(DISAPPEAR_FADE.multiply((double) frame / frames), tick -> {
					stroke.opacity = opacity;
					redrawBase();
				}));
			}
			fade.setOnFinished(faded -> {
				if (strokes.remove(stroke)) {
					stroke.id.ifPresent(log::removeStroke);
					redrawBase();
				}
			});
			fade.play();
		});
		wait.play();
	}

	private void undo() {
		if (current != null || strokes.isEmpty()) {
			return;
		}
		strokes.removeLast().id.ifPresent(log::removeStroke);
		redrawBase();
	}

	private void clear() {
		if (current != null) {
			return;
		}
		strokes.clear();
		log.clearAll();
		redrawBase();
	}

	private void redrawBase() {
		GraphicsContext graphics = base.getGraphicsContext2D();
		graphics.clearRect(0, 0, base.getWidth(), base.getHeight());
		for (DrawnStroke stroke : strokes) {
			render(graphics, stroke);
		}
	}

	private void redrawLive() {
		GraphicsContext graphics = live.getGraphicsContext2D();
		graphics.clearRect(0, 0, live.getWidth(), live.getHeight());
		if (current != null) {
			render(graphics, current);
		}
	}

	private static void render(GraphicsContext graphics, DrawnStroke stroke) {
		graphics.save();
		graphics.setGlobalAlpha(stroke.opacity * (stroke.tool == InkTool.HIGHLIGHTER ? HIGHLIGHTER_ALPHA : 1));
		graphics.setStroke(stroke.color);
		graphics.setLineWidth(stroke.width);
		graphics.setLineCap(stroke.tool == InkTool.HIGHLIGHTER ? StrokeLineCap.SQUARE : StrokeLineCap.ROUND);
		graphics.setLineJoin(StrokeLineJoin.ROUND);
		double[] first = stroke.points.getFirst();
		double[] last = stroke.points.getLast();
		switch (stroke.tool) {
			case RECTANGLE -> {
				InkShapes.Box box = InkShapes.box(first[0], first[1], last[0], last[1]);
				graphics.strokeRoundRect(box.x(), box.y(), box.width(), box.height(), 6, 6);
			}
			case ELLIPSE -> {
				InkShapes.Box box = InkShapes.box(first[0], first[1], last[0], last[1]);
				graphics.strokeOval(box.x(), box.y(), box.width(), box.height());
			}
			case ARROW -> {
				graphics.strokeLine(first[0], first[1], last[0], last[1]);
				double[] head = InkShapes.arrowHead(first[0], first[1], last[0], last[1], stroke.width);
				if (head.length == 4) {
					graphics.strokePolyline(new double[] {head[0], last[0], head[2]},
							new double[] {head[1], last[1], head[3]}, 3);
				}
			}
			case PEN, HIGHLIGHTER -> {
				if (stroke.twoPoint || stroke.points.size() == 1) {
					graphics.strokeLine(first[0], first[1], last[0], last[1]);
				} else {
					freehand(graphics, stroke.points);
				}
			}
		}
		graphics.restore();
	}

	/** One path through the midpoints, so a translucent stroke never doubles over itself. */
	private static void freehand(GraphicsContext graphics, List<double[]> points) {
		graphics.beginPath();
		double[] start = points.getFirst();
		graphics.moveTo(start[0], start[1]);
		for (int index = 1; index < points.size(); index++) {
			InkSmoothing.Segment segment = InkSmoothing.segment(points, index);
			graphics.quadraticCurveTo(segment.controlX(), segment.controlY(), segment.toX(), segment.toY());
		}
		double[] last = points.getLast();
		graphics.lineTo(last[0], last[1]);
		graphics.stroke();
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
			update(preferences.withWidth(preferences.widthIndex() - 1));
		} else if (code == KeyCode.CLOSE_BRACKET) {
			update(preferences.withWidth(preferences.widthIndex() + 1));
		} else if (code == KeyCode.P) {
			update(preferences.withTool(InkTool.PEN));
		} else if (code == KeyCode.H) {
			update(preferences.withTool(InkTool.HIGHLIGHTER));
		} else if (code == KeyCode.A) {
			update(preferences.withTool(InkTool.ARROW));
		} else if (code == KeyCode.R) {
			update(preferences.withTool(InkTool.RECTANGLE));
		} else if (code == KeyCode.E) {
			update(preferences.withTool(InkTool.ELLIPSE));
		} else if (code == KeyCode.D) {
			update(preferences.withDisappearing(!preferences.disappearing()));
		} else if (code == KeyCode.TAB) {
			update(preferences.withPaletteHidden(!preferences.paletteHidden()));
		} else if (code.isDigitKey()) {
			int digit = code.getCode() - KeyCode.DIGIT1.getCode();
			int numpad = code.getCode() - KeyCode.NUMPAD1.getCode();
			int index = digit >= 0 && digit < COLORS.size() ? digit
					: (numpad >= 0 && numpad < COLORS.size() ? numpad : -1);
			if (index < 0) {
				return;
			}
			update(preferences.withColor(index));
		} else {
			return;
		}
		event.consume();
	}

	private void update(InkPreferences next) {
		if (next.equals(preferences)) {
			return;
		}
		preferences = next;
		palette.setVisible(!next.paletteHidden() && current == null);
		paintPalette();
		onPreferences.accept(next);
	}

	// ── palette ────────────────────────────────────────────────────────────

	private Region buildPalette() {
		palette.getStyleClass().add("ink-palette");
		palette.setAlignment(Pos.CENTER);
		palette.setPadding(new Insets(6, 10, 6, 10));
		palette.setStyle("-fx-background-color: rgba(28,28,30,0.92); -fx-background-radius: 999;"
				+ " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 2);");

		Region grip = new Region();
		grip.setPrefSize(4, 22);
		grip.setStyle("-fx-background-color: rgba(255,255,255,0.25); -fx-background-radius: 3;");
		grip.setCursor(Cursor.MOVE);
		grip.setOnMousePressed(event -> {
			paletteDragX = event.getSceneX() - palette.getLayoutX();
			paletteDragY = event.getSceneY() - palette.getLayoutY();
			event.consume();
		});
		grip.setOnMouseDragged(event -> {
			InkPalettePlacement.Position position = InkPalettePlacement.dragged(stageBounds(), area,
					palette.getWidth(), palette.getHeight(),
					event.getSceneX() - paletteDragX, event.getSceneY() - paletteDragY);
			palette.relocate(Math.round(position.x()), Math.round(position.y()));
			event.consume();
		});
		palette.getChildren().add(grip);

		for (InkTool tool : TOOLS) {
			Button button = textButton(toolGlyph(tool), UiI18n.t(toolLabelKey(tool)),
					() -> update(preferences.withTool(tool)));
			toolButtons.add(button);
			palette.getChildren().add(button);
		}
		palette.getChildren().add(separator());
		for (int index = 0; index < COLORS.size(); index++) {
			int chosen = index;
			Region swatch = new Region();
			swatch.setPrefSize(20, 20);
			swatch.setCursor(Cursor.HAND);
			Tooltip.install(swatch, new Tooltip(UiI18n.t("launch:ink.color") + " (" + (index + 1) + ")"));
			swatch.setOnMouseClicked(event -> {
				update(preferences.withColor(chosen));
				event.consume();
			});
			swatches.add(swatch);
			palette.getChildren().add(swatch);
		}
		palette.getChildren().add(separator());
		for (int index = 0; index < PEN_WIDTHS.length; index++) {
			int chosen = index;
			Region dot = new Region();
			dot.setPrefSize(24, 24);
			dot.setCursor(Cursor.HAND);
			Circle mark = new Circle(12, 12, 2);
			mark.setFill(Color.WHITE);
			mark.setMouseTransparent(true);
			Pane holder = new Pane(dot, mark);
			Tooltip.install(dot, new Tooltip(UiI18n.t("launch:ink.width") + " ([ ])"));
			dot.setOnMouseClicked(event -> {
				update(preferences.withWidth(chosen));
				event.consume();
			});
			widthDots.add(dot);
			widthMarks.add(mark);
			palette.getChildren().add(holder);
		}
		palette.getChildren().add(separator());
		disappearButton.icon(whiteIcon(Icons.TimerIcon));
		disappearButton.setAccessibleText(UiI18n.t("launch:ink.disappearing"));
		Tooltip.install(disappearButton, new Tooltip(UiI18n.t("launch:ink.disappearing")));
		disappearButton.setOnAction(event -> update(preferences.withDisappearing(!preferences.disappearing())));
		disappearButton.setFocusTraversable(false);
		palette.getChildren().addAll(
				disappearButton,
				iconButton(Icons.ArrowCounterClockwise, UiI18n.t("launch:ink.undo"), this::undo),
				iconButton(Icons.Trash, UiI18n.t("launch:ink.clear"), this::clear),
				iconButton(Icons.EyeSlashIcon, UiI18n.t("launch:ink.hidePalette"),
						() -> update(preferences.withPaletteHidden(true))),
				iconButton(Icons.XIcon, UiI18n.t("launch:ink.stopDrawing"), this::close));
		palette.addEventHandler(MouseEvent.MOUSE_PRESSED, MouseEvent::consume);
		palette.setVisible(!preferences.paletteHidden());
		paintPalette();
		return palette;
	}

	private static String toolGlyph(InkTool tool) {
		return switch (tool) {
			case PEN -> "✎";
			case HIGHLIGHTER -> "▐";
			case ARROW -> "↗";
			case RECTANGLE -> "▭";
			case ELLIPSE -> "◯";
		};
	}

	private static String toolLabelKey(InkTool tool) {
		return switch (tool) {
			case PEN -> "launch:ink.pen";
			case HIGHLIGHTER -> "launch:ink.highlighter";
			case ARROW -> "launch:ink.arrow";
			case RECTANGLE -> "launch:ink.rectangle";
			case ELLIPSE -> "launch:ink.ellipse";
		};
	}

	private static Region separator() {
		Region line = new Region();
		line.setPrefSize(1, 20);
		line.setStyle("-fx-background-color: rgba(255,255,255,0.18);");
		HBox.setMargin(line, new Insets(0, 2, 0, 2));
		return line;
	}

	private static Icon whiteIcon(String icon) {
		Icon graphic = Icon.of(icon, 16);
		graphic.setStyle("-fx-text-fill: white;");
		return graphic;
	}

	private static Button iconButton(String icon, String tooltip, Runnable action) {
		Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		button.icon(whiteIcon(icon));
		button.setAccessibleText(tooltip);
		Tooltip.install(button, new Tooltip(tooltip));
		button.setOnAction(event -> action.run());
		button.setFocusTraversable(false);
		return button;
	}

	private static Button textButton(String glyph, String tooltip, Runnable action) {
		Button button = new Button(glyph, Button.Variant.GHOST, Button.Size.ICON);
		button.setStyle("-fx-text-fill: white; -fx-font-size: 15px;");
		button.setAccessibleText(tooltip);
		Tooltip.install(button, new Tooltip(tooltip));
		button.setOnAction(event -> action.run());
		button.setFocusTraversable(false);
		return button;
	}

	private void paintPalette() {
		for (int index = 0; index < toolButtons.size(); index++) {
			boolean selected = TOOLS.get(index) == preferences.tool();
			toolButtons.get(index).setStyle("-fx-text-fill: white; -fx-font-size: 15px;"
					+ (selected ? " -fx-background-color: rgba(255,255,255,0.2); -fx-background-radius: 6;" : ""));
		}
		for (int index = 0; index < swatches.size(); index++) {
			boolean selected = index == preferences.colorIndex();
			swatches.get(index).setStyle("-fx-background-color: " + COLORS.get(index) + ";"
					+ " -fx-background-radius: 999; -fx-border-radius: 999;"
					+ " -fx-border-color: " + (selected ? "white" : "rgba(255,255,255,0.3)") + ";"
					+ " -fx-border-width: " + (selected ? 2.5 : 1) + ";");
		}
		double[] widths = preferences.tool() == InkTool.HIGHLIGHTER ? HIGHLIGHTER_WIDTHS : PEN_WIDTHS;
		double largest = widths[widths.length - 1];
		for (int index = 0; index < widthDots.size(); index++) {
			// The dots show the tool's widths to scale, the thickest filling the 24 px slot.
			widthMarks.get(index).setRadius(Math.max(2, widths[index] / largest * 9));
			widthDots.get(index).setStyle(index == preferences.widthIndex()
					? "-fx-background-color: rgba(255,255,255,0.18); -fx-background-radius: 999;"
					: "-fx-background-color: transparent;");
		}
		disappearButton.setStyle(preferences.disappearing()
				? "-fx-background-color: rgba(255,204,0,0.35); -fx-background-radius: 6;" : "");
		disappearButton.setAriaPressed(preferences.disappearing());
	}

	private boolean isOnPalette(MouseEvent event) {
		return palette.isVisible()
				&& palette.getBoundsInParent().contains(event.getX(), event.getY());
	}
}
