package dev.gravaai.ui.kit;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;

/**
 * HeroUI ColorPicker port (plan §5.12), source {@code src/components/ui/color-picker.tsx} +
 * {@code @heroui/styles/components/color-picker.css,color-area.css,color-slider.css}.
 *
 * <p>The trigger is the app's secondary Button with a ColorSwatch and label (compact variant shows
 * the hex). The popover is a 288 px dialog with a 256x160 HSB saturation/brightness area, a hue
 * slider, optional preset swatches, a hex field and the optional "Clear background" action.
 *
 * <pre>{@code
 * ColorPicker accent = new ColorPicker("Accent", "#2fb3a3");
 * accent.colors("#2fb3a3", "#e5656e", "#e8b339").onColorChange(color -> ...);
 * }</pre>
 */
public final class ColorPicker extends Button {

	private static final double AREA_WIDTH = 256;
	private static final double AREA_HEIGHT = 160;
	private static final double HUE_TRACK_HEIGHT = 20;

	private final String label;
	private boolean compact;
	private final StackPane swatch = new StackPane();
	private final Region swatchColor = new Region();
	private final Label valueLabel = new Label();
	private final Popover popover;
	private final VBox dialog = new VBox();
	private final Canvas area = new Canvas(AREA_WIDTH, AREA_HEIGHT);
	private final Region areaThumb = new Region();
	private final Pane areaPane = new Pane();
	private final Region hueTrack = new Region();
	private final Region hueThumb = new Region();
	private final Pane huePane = new Pane();
	private final FlowPane presets = new FlowPane(8, 8);
	private final Input hexField = new Input("#rrggbb");
	private final List<Color> presetColors = new ArrayList<>();
	private Color color = Color.BLACK;
	private Consumer<Color> onColorChange = color -> {};
	private @Nullable Runnable onClear;

	public ColorPicker(String label) {
		this(label, "#000000");
	}

	public ColorPicker(String label, String value) {
		super("", Button.Variant.SECONDARY, Button.Size.MD);
		this.label = label;
		this.compact = false;
		getStyleClass().add("color-picker__trigger");
		swatch.getStyleClass().add("color-swatch");
		Region checker = new Region();
		checker.getStyleClass().addAll("color-swatch", "color-swatch--checker");
		swatchColor.getStyleClass().add("color-swatch");
		swatch.setMinSize(16, 16);
		swatch.setPrefSize(16, 16);
		swatch.setMaxSize(16, 16);
		swatch.getChildren().addAll(checker, swatchColor);
		valueLabel.getStyleClass().add("color-picker__label");
		HBox graphic = new HBox(8, swatch, valueLabel);
		graphic.setAlignment(Pos.CENTER_LEFT);
		setGraphic(graphic);

		popover = new Popover(this);
		popover.side(Anchors.Side.BOTTOM).align(Anchors.Align.START).offset(8);
		popover.content(buildDialog());
		setColor(ColorMath.fromHex(value));
	}

	private VBox buildDialog() {
		dialog.getStyleClass().add("color-picker__dialog");
		dialog.setSpacing(16);
		dialog.setPrefWidth(288);
		dialog.setMaxWidth(288);

		area.getStyleClass().add("color-area");
		Rectangle areaClip = new Rectangle(AREA_WIDTH, AREA_HEIGHT);
		areaClip.setArcWidth(16);
		areaClip.setArcHeight(16);
		area.setClip(areaClip);
		areaThumb.getStyleClass().add("color-area__thumb");
		areaPane.getChildren().addAll(area, areaThumb);
		areaPane.setMinSize(AREA_WIDTH, AREA_HEIGHT);
		areaPane.setPrefSize(AREA_WIDTH, AREA_HEIGHT);
		areaPane.setMaxSize(AREA_WIDTH, AREA_HEIGHT);
		area.setOnMousePressed(this::areaPressed);
		area.setOnMouseDragged(this::areaDragged);
		areaThumb.setMouseTransparent(true);

		hueTrack.getStyleClass().add("color-slider__track");
		hueTrack.setStyle("-fx-background-color: linear-gradient(to right, #ff0000, #ffff00, #00ff00, "
				+ "#00ffff, #0000ff, #ff00ff, #ff0000);");
		hueThumb.getStyleClass().add("color-slider__thumb");
		hueThumb.setMouseTransparent(true);
		huePane.getChildren().addAll(hueTrack, hueThumb);
		huePane.setMinHeight(HUE_TRACK_HEIGHT);
		huePane.setPrefHeight(HUE_TRACK_HEIGHT);
		huePane.setMaxHeight(HUE_TRACK_HEIGHT);
		huePane.setOnMousePressed(this::huePressed);
		huePane.setOnMouseDragged(this::hueDragged);

		presets.getStyleClass().add("color-picker__swatches");
		hexField.setMaxWidth(Double.MAX_VALUE);
		hexField.setOnAction(event -> applyHex());
		hexField.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				applyHex();
			}
		});
		Label hexLabel = new Label("Hex color");
		hexLabel.getStyleClass().add("g-label");
		VBox hexBox = new VBox(4, hexLabel, hexField);
		VBox swatchBox = new VBox(8, presets);

		Button clear = new Button("Clear background", Button.Variant.GHOST, Button.Size.SM);
		clear.setId("clear");
		clear.setVisible(false);
		clear.setManaged(false);
		clear.setOnAction(event -> {
			popover.hide();
			if (onClear != null) {
				onClear.run();
			}
		});

		dialog.getChildren().addAll(areaPane, huePane, swatchBox, hexBox, clear);
		return dialog;
	}

	/** Sets the preset colours shown under the area (ColorSwatchPicker). */
	public ColorPicker colors(String... hexColors) {
		presetColors.clear();
		presets.getChildren().clear();
		for (String hex : hexColors) {
			Color preset = ColorMath.fromHex(hex);
			presetColors.add(preset);
			Region chip = new Region();
			chip.getStyleClass().add("color-swatch");
			chip.setMinSize(24, 24);
			chip.setPrefSize(24, 24);
			chip.setMaxSize(24, 24);
			chip.setStyle("-fx-background-color: " + ColorMath.toCss(preset)
					+ "; -fx-background-radius: 6px;");
			javafx.scene.control.Button button = new javafx.scene.control.Button();
			button.getStyleClass().add("color-picker__swatch-button");
			button.setGraphic(chip);
			button.setOnAction(event -> setColor(preset));
			presets.getChildren().add(button);
		}
		return this;
	}

	/** Adds the "Clear background" action (color-picker.tsx onClear). */
	public ColorPicker onClear(Runnable action) {
		this.onClear = action;
		if (action != null) {
			Button clear = (Button) dialog.lookup("#clear");
			if (clear != null) {
				clear.setVisible(true);
				clear.setManaged(true);
			}
		}
		return this;
	}

	/** Called with the new colour (React {@code onChange(color)}). */
	public ColorPicker onColorChange(Consumer<Color> listener) {
		this.onColorChange = listener == null ? color -> {} : listener;
		return this;
	}

	/** The current colour. */
	public Color color() {
		return color;
	}

	/** The current colour as the CSS hex string the app stores. */
	public String value() {
		return ColorMath.toHex(color);
	}

	public void setColor(String value) {
		setColor(ColorMath.fromHex(value));
	}

	public void setColor(Color newColor) {
		if (newColor == null) {
			return;
		}
		Color next = new Color(newColor.getRed(), newColor.getGreen(), newColor.getBlue(), newColor.getOpacity());
		boolean changed = !next.equals(color);
		color = next;
		updateSwatch();
		updateArea();
		updateHueThumb();
		hexField.setText(ColorMath.toHex(color));
		if (changed) {
			onColorChange.accept(color);
		}
	}

	private void updateSwatch() {
		swatchColor.setStyle("-fx-background-color: " + ColorMath.toCss(color)
				+ "; -fx-background-radius: 4px;");
		if (compact) {
			valueLabel.setText(color.getOpacity() <= 0 ? "None" : ColorMath.toHex(color).toUpperCase());
		} else {
			valueLabel.setText(label);
		}
	}

	private void updateArea() {
		ColorMath.Hsba hsb = ColorMath.toHsb(color);
		GraphicsContext graphics = area.getGraphicsContext2D();
		graphics.clearRect(0, 0, AREA_WIDTH, AREA_HEIGHT);
		graphics.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
				new Stop(0, Color.WHITE), new Stop(1, Color.hsb(hsb.hue(), 1, 1))));
		graphics.fillRect(0, 0, AREA_WIDTH, AREA_HEIGHT);
		graphics.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
				new Stop(0, Color.color(0, 0, 0, 0)), new Stop(1, Color.BLACK)));
		graphics.fillRect(0, 0, AREA_WIDTH, AREA_HEIGHT);
		areaThumb.resizeRelocate(hsb.saturation() * AREA_WIDTH - 10,
				(1 - hsb.brightness()) * AREA_HEIGHT - 10, 20, 20);
	}

	private void updateHueThumb() {
		double hue = ColorMath.toHsb(color).hue();
		hueThumb.resizeRelocate(hue / 360 * AREA_WIDTH - 12, (HUE_TRACK_HEIGHT - 16) / 2, 24, 16);
	}

	private void areaPressed(MouseEvent event) {
		applyArea(event.getX(), event.getY());
	}

	private void areaDragged(MouseEvent event) {
		applyArea(event.getX(), event.getY());
	}

	private void applyArea(double x, double y) {
		ColorMath.Hsba hsb = ColorMath.toHsb(color);
		double saturation = clamp01(x / AREA_WIDTH);
		double brightness = 1 - clamp01(y / AREA_HEIGHT);
		setColor(ColorMath.fromHsb(new ColorMath.Hsba(hsb.hue(), saturation, brightness, hsb.alpha())));
	}

	private void huePressed(MouseEvent event) {
		applyHue(event.getX());
	}

	private void hueDragged(MouseEvent event) {
		applyHue(event.getX());
	}

	private void applyHue(double x) {
		ColorMath.Hsba hsb = ColorMath.toHsb(color);
		double hue = clamp01(x / AREA_WIDTH) * 360;
		setColor(ColorMath.fromHsb(new ColorMath.Hsba(hue, hsb.saturation(), hsb.brightness(), hsb.alpha())));
	}

	private void applyHex() {
		try {
			setColor(ColorMath.fromHex(hexField.getText()));
		} catch (IllegalArgumentException error) {
			hexField.setText(ColorMath.toHex(color));
		}
	}

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}

	/** Compact variant: the trigger shows the hex value instead of the label. */
	public ColorPicker compact() {
		this.compact = true;
		updateSwatch();
		return this;
	}

	/** Opens the picker programmatically (showcase/tests). */
	public void showPicker() {
		popover.show();
	}

	/** The popover dialog (size assertions). */
	public Region dialog() {
		return dialog;
	}

}
