package dev.gravaai.ui.kit;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * HeroUI Slider port (plan §5.12), source {@code src/components/ui/slider.tsx},
 * {@code src/components/video-editor/SliderControl.tsx} and
 * {@code @heroui/styles/components/slider.css}.
 *
 * <p>Horizontal only (the app only uses horizontal sliders): a 16 px hit area whose 3 px rail
 * (Instrument mock {@code .slider}) carries the accent fill and the 9 px light knob, centered
 * exactly on the rail line. The pill scales to 0.9 while dragging over 250 ms ease-out. The
 * {@code label}/{@code output} row matches SliderControl: label on the left, formatted value on
 * the right.
 */
public final class Slider extends VBox {

	private static final double TRACK_INSET = 12;
	private static final double TRACK_HEIGHT = 16;
	private static final double RAIL_HEIGHT = 3;
	private static final double THUMB_SIZE = 9;

	private final DoubleProperty value = new SimpleDoubleProperty(0);
	private final DoubleProperty fraction = new SimpleDoubleProperty(0);
	private double min = 0;
	private double max = 100;
	private double step = 1;
	private final Label label = new Label();
	private final Label output = new Label();
	private final Pane track = new Pane();
	private final Region rail = new Region();
	private final Region fill = new Region();
	private final Region thumbPill = new Region();
	private final Pane thumb = new Pane();
	private boolean dragging;
	private Consumer<Double> onValueChange = value -> {};
	private Consumer<Double> onValueCommit = value -> {};
	private DoubleFunction<String> formatter = value -> formatDefault(value);
	private boolean showOutput = true;

	public Slider() {
		this(null);
	}

	/** A slider with an optional label row (null = no label); the output shows the formatted value. */
	public Slider(@Nullable String labelText) {
		getStyleClass().add("slider");
		setSpacing(8);
		setFillWidth(true);
		setFocusTraversable(true);

		HBox header = new HBox();
		label.getStyleClass().add("slider__label");
		output.getStyleClass().add("slider__output");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		header.getChildren().addAll(label, spacer, output);
		header.setAlignment(Pos.CENTER_LEFT);
		label.setVisible(labelText != null && !labelText.isEmpty());
		label.setManaged(label.isVisible());
		label.setText(labelText == null ? "" : labelText);
		if (!label.isVisible()) {
			showOutput = false;
		}
		output.setVisible(showOutput);
		output.setManaged(showOutput);
		getChildren().add(header);

		track.getStyleClass().add("slider__track");
		track.setMinHeight(TRACK_HEIGHT);
		track.setPrefHeight(TRACK_HEIGHT);
		track.setMaxHeight(TRACK_HEIGHT);
		rail.getStyleClass().add("slider__rail");
		// Unmanaged: a Pane's layout pass autosizes managed children to their preferred size,
		// which would wipe the bounds laid out below (a bare Region's pref size is 0×0).
		rail.setManaged(false);
		rail.setMouseTransparent(true);
		fill.getStyleClass().add("slider__fill");
		fill.setManaged(false);
		fill.setMouseTransparent(true);
		thumb.getStyleClass().add("slider__thumb");
		thumb.setManaged(false);
		thumb.setMouseTransparent(true);
		thumbPill.getStyleClass().add("slider__thumb-pill");
		thumbPill.setManaged(false);
		thumbPill.setMouseTransparent(true);
		thumb.getChildren().add(thumbPill);
		track.getChildren().addAll(rail, fill, thumb);
		getChildren().add(track);
		VBox.setVgrow(track, Priority.NEVER);

		track.setOnMousePressed(event -> {
			requestFocus();
			dragging = true;
			setDragging(true);
			setFromMouse(event.getX());
		});
		track.setOnMouseDragged(event -> setFromMouse(event.getX()));
		track.setOnMouseReleased(event -> {
			dragging = false;
			setDragging(false);
			onValueCommit.accept(getValue());
		});
		track.setOnKeyPressed(event -> {
			if (handleKey(event.getCode(), event.isShiftDown())) {
				event.consume();
			}
		});
		setOnKeyPressed(event -> {
			if (handleKey(event.getCode(), event.isShiftDown())) {
				event.consume();
			}
		});

		value.addListener((observable, oldValue, newValue) -> updateFromValue());
		updateFromValue();
	}

	private void setDragging(boolean dragging) {
		ScaleTransition scale = new ScaleTransition(Motion.SLIDER_THUMB, thumbPill);
		scale.setToX(dragging ? 0.9 : 1);
		scale.setToY(dragging ? 0.9 : 1);
		scale.setInterpolator(Motion.EASE_OUT);
		scale.play();
	}

	private boolean handleKey(KeyCode code, boolean shift) {
		double delta = shift ? step * 10 : step;
		switch (code) {
			case LEFT, DOWN -> {
				setValue(getValue() - delta);
				commit();
				return true;
			}
			case RIGHT, UP -> {
				setValue(getValue() + delta);
				commit();
				return true;
			}
			case HOME -> {
				setValue(min);
				commit();
				return true;
			}
			case END -> {
				setValue(max);
				commit();
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private void commit() {
		onValueCommit.accept(getValue());
	}

	private void setFromMouse(double x) {
		double width = track.getWidth();
		if (width <= 2 * TRACK_INSET) {
			return;
		}
		double usable = width - 2 * TRACK_INSET;
		double position = Math.max(0, Math.min(1, (x - TRACK_INSET) / usable));
		setValue(min + position * (max - min));
	}

	/** Clamps to the range and snaps to the step, like the React Aria slider. */
	public void setValue(double newValue) {
		double clamped = Math.max(min, Math.min(max, newValue));
		if (step > 0) {
			clamped = min + Math.round((clamped - min) / step) * step;
			clamped = Math.max(min, Math.min(max, clamped));
		}
		double rounded = max <= 1 ? clamped : Math.round(clamped * 1_000_000d) / 1_000_000d;
		if (Double.compare(value.get(), rounded) != 0) {
			value.set(rounded);
			onValueChange.accept(rounded);
		}
		updateFromValue();
	}

	public double getValue() {
		return value.get();
	}

	public DoubleProperty valueProperty() {
		return value;
	}

	private void updateFromValue() {
		double range = max - min;
		double ratio = range <= 0 ? 0 : (getValue() - min) / range;
		fraction.set(Math.max(0, Math.min(1, ratio)));
		output.setText(formatter.apply(getValue()));
		requestLayout();
	}

	@Override
	protected void layoutChildren() {
		super.layoutChildren();
		double width = track.getWidth();
		double usable = Math.max(0, width - 2 * TRACK_INSET);
		double center = TRACK_INSET + fraction.get() * usable;
		double fillWidth = Math.max(0, center - TRACK_INSET);
		// The rail, the fill and the knob share one center line: the knob's center sits exactly
		// on the rail (Instrument mock .slider).
		double railY = (TRACK_HEIGHT - RAIL_HEIGHT) / 2;
		rail.resizeRelocate(TRACK_INSET, railY, Math.max(0, width - 2 * TRACK_INSET), RAIL_HEIGHT);
		fill.resizeRelocate(TRACK_INSET, railY, Math.max(fillWidth, 0), RAIL_HEIGHT);
		thumb.resizeRelocate(center - THUMB_SIZE / 2, 0, THUMB_SIZE, TRACK_HEIGHT);
		thumbPill.resizeRelocate(0, (TRACK_HEIGHT - THUMB_SIZE) / 2, THUMB_SIZE, THUMB_SIZE);
		fill.setOpacity(fillWidth <= 0 ? 0 : 1);
	}

	/** Range formatting for the output label; default shows integers or one decimal. */
	public Slider formatter(DoubleFunction<String> formatter) {
		this.formatter = formatter == null ? value -> formatDefault(value) : formatter;
		updateFromValue();
		return this;
	}

	private static String formatDefault(double value) {
		if (value == Math.rint(value)) {
			return Long.toString(Math.round(value));
		}
		return String.format(Locale.ROOT, "%.1f", value);
	}

	public Slider range(double min, double max) {
		this.min = min;
		this.max = max;
		if (getValue() < min) {
			setValue(min);
		} else if (getValue() > max) {
			setValue(max);
		}
		updateFromValue();
		return this;
	}

	public Slider step(double step) {
		this.step = step;
		return this;
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	public double step() {
		return step;
	}

	/** Called while dragging / on every value change. */
	public Slider onValueChange(Consumer<Double> listener) {
		this.onValueChange = listener == null ? value -> {} : listener;
		return this;
	}

	/** Called on mouse release and on keyboard changes, like React's {@code onChangeEnd}. */
	public Slider onValueCommit(Consumer<Double> listener) {
		this.onValueCommit = listener == null ? value -> {} : listener;
		return this;
	}

	/** Hides the formatted value (a bare slider next to an icon, like the editor volume row). */
	public Slider hideOutput() {
		showOutput = false;
		output.setVisible(false);
		output.setManaged(false);
		return this;
	}

	/** Marks this slider as the SliderControl layout (text-xs label/output). */
	public Slider sliderControl() {
		getStyleClass().add("slider-control");
		return this;
	}

	/** The label node (for layout tweaks by screens). */
	public Label labelNode() {
		return label;
	}

	/** The output node (for layout tweaks by screens). */
	public Label outputNode() {
		return output;
	}
}
