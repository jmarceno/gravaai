package dev.gravaai.ui.kit;

/**
 * HeroUI TextArea port (plan §5.12), source {@code src/components/ui/input.tsx} re-export +
 * {@code @heroui/styles/components/textarea.css}. Same field styling as {@link Input}.
 */
public class TextArea extends javafx.scene.control.TextArea {

	public TextArea() {
		this("");
	}

	public TextArea(String promptText) {
		super();
		getStyleClass().add("textarea");
		setPromptText(promptText);
		setWrapText(true);
	}

	public TextArea prompt(String promptText) {
		setPromptText(promptText);
		return this;
	}

	public TextArea text(String text) {
		setText(text);
		return this;
	}

	public TextArea fullWidth() {
		setMaxWidth(Double.MAX_VALUE);
		return this;
	}
}
