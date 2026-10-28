package dev.gravaai.ui.kit;

import javafx.scene.control.ContentDisplay;
import javafx.scene.text.Text;

/**
 * HeroUI Label port (plan §5.12), source {@code src/components/ui/label.tsx} +
 * {@code @heroui/styles/components/label.css}.
 *
 * <p>{@code text-sm font-medium}. {@link #required()} renders the {@code ::after "*"} marker as a
 * right-hand graphic in the danger colour, because JavaFX has no pseudo-elements.
 */
public class Label extends javafx.scene.control.Label {

	public Label() {
		this("");
	}

	public Label(String text) {
		super(text);
		getStyleClass().add("g-label");
		setContentDisplay(ContentDisplay.RIGHT);
		setGraphicTextGap(2);
	}

	/** Adds the danger-coloured required marker. */
	public Label required() {
		Text marker = new Text(" *");
		marker.getStyleClass().add("g-label__required");
		setGraphic(marker);
		return this;
	}

	/** Invalid form label: danger text colour (label.css {@code .label--invalid}). */
	public Label invalid() {
		getStyleClass().add("label--invalid");
		return this;
	}

	public Label text(String text) {
		setText(text);
		return this;
	}

	public Label fontSize(double pixels) {
		setStyle("-fx-font-size: " + pixels + "px;");
		return this;
	}
}
