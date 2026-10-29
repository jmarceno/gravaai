package dev.gravaai.ui.kit;

import org.jspecify.annotations.Nullable;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * HeroUI Meter port used by {@code src/components/ui/audio-level-meter.tsx}, plus
 * {@code @heroui/styles/components/meter.css} {@code --sm}: a 4 px track radius 2 in the default
 * colour and an accent/warning/danger fill whose width animates over 300 ms ease-out.
 *
 * <p>The React component maps level 0-100 to the meter value and picks the colour from the raw
 * level: {@code level > 85 ? "danger" : level > 65 ? "warning" : "accent"}.
 */
public final class AudioLevelMeter extends Pane {

	private final Region track = new Region();
	private final Region fill = new Region();
	private final DoubleProperty level = new SimpleDoubleProperty(0);
	private final DoubleProperty fraction = new SimpleDoubleProperty(0);
	private @Nullable Timeline animation;
	private boolean trackSized;

	public AudioLevelMeter() {
		getStyleClass().add("meter");
		setMinHeight(4);
		setPrefHeight(4);
		setMaxHeight(4);
		track.getStyleClass().add("meter__track");
		fill.getStyleClass().add("meter__fill");
		getChildren().addAll(track, fill);
		level.addListener((observable, oldValue, newValue) -> update());
		widthProperty().addListener((observable, oldValue, newValue) -> requestLayout());
	}

	/** Sets the level 0-100 (clamped); colours change at 65 and 85. */
	public void setLevel(double newLevel) {
		double clamped = Math.min(100, Math.max(0, newLevel));
		if (Double.compare(level.get(), clamped) == 0) {
			return;
		}
		double from = fraction.get();
		level.set(clamped);
		double to = clamped / 100.0;
		if (animation != null) {
			animation.stop();
		}
		animation = new Timeline(
				new KeyFrame(javafx.util.Duration.ZERO, new KeyValue(fraction, from)),
				new KeyFrame(Motion.METER_FILL, new KeyValue(fraction, to, Motion.EASE_OUT)));
		animation.play();
	}

	public double getLevel() {
		return level.get();
	}

	public DoubleProperty levelProperty() {
		return level;
	}

	/** The HeroUI colour token for a level: danger above 85, warning above 65, else accent. */
	public static String colorClass(double level) {
		if (level > 85) {
			return "meter__fill--danger";
		}
		if (level > 65) {
			return "meter__fill--warning";
		}
		return "";
	}

	private void update() {
		fill.getStyleClass().removeAll("meter__fill--warning", "meter__fill--danger");
		String colorClass = colorClass(getLevel());
		if (!colorClass.isEmpty()) {
			fill.getStyleClass().add(colorClass);
		}
		fraction.set(getLevel() / 100.0);
		requestLayout();
	}

	@Override
	protected void layoutChildren() {
		double width = getWidth();
		double height = Math.max(4, getHeight());
		track.resizeRelocate(0, 0, width, 4);
		fill.resizeRelocate(0, 0, width * fraction.get(), 4);
	}
}
