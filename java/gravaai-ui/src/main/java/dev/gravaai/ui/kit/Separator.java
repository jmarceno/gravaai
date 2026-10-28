package dev.gravaai.ui.kit;

import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * HeroUI Separator port (plan §5.12), source {@code src/components/ui/separator.tsx} +
 * {@code @heroui/styles/components/separator.css}. 1 px line in the separator colour, with the
 * secondary/tertiary colour levels.
 */
public final class Separator extends Region {

	/** Separator colour level (separator.css {@code .separator--*}). */
	public enum Tone { DEFAULT, SECONDARY, TERTIARY }

	public Separator() {
		this(Orientation.HORIZONTAL, Tone.DEFAULT);
	}

	public Separator(Tone tone) {
		this(Orientation.HORIZONTAL, tone);
	}

	public Separator(Orientation orientation) {
		this(orientation, Tone.DEFAULT);
	}

	public Separator(Orientation orientation, Tone tone) {
		getStyleClass().add("separator");
		if (orientation == Orientation.VERTICAL) {
			getStyleClass().add("separator--vertical");
			setMaxWidth(1);
			setMinWidth(1);
			setPrefWidth(1);
			setMinHeight(8);
			setMaxHeight(Double.MAX_VALUE);
			// Fill the parent's cross axis like the React separator's self-stretch.
			javafx.scene.layout.HBox.setHgrow(this, Priority.NEVER);
		} else {
			setMinHeight(1);
			setPrefHeight(1);
			setMaxHeight(1);
			setMaxWidth(Double.MAX_VALUE);
		}
		switch (tone) {
			case SECONDARY -> getStyleClass().add("separator--secondary");
			case TERTIARY -> getStyleClass().add("separator--tertiary");
			case DEFAULT -> { /* default */ }
		}
	}

	/** Orientation of the line. */
	public enum Orientation { HORIZONTAL, VERTICAL }

	/** Vertical separator with the given tone. */
	public static Separator vertical() {
		return new Separator(Orientation.VERTICAL, Tone.DEFAULT);
	}
}
