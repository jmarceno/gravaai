package dev.gravaai.ui.kit;

/**
 * HeroUI Input port (plan §5.12), source {@code src/components/ui/input.tsx} +
 * {@code @heroui/styles/components/input.css,textfield.css}.
 *
 * <p>radius-field 12 px, field background, px-3 py-2 (12/8), text-sm (14), 2 px accent focus ring.
 */
public final class Input extends javafx.scene.control.TextField {

	public Input() {
		this("");
	}

	public Input(String promptText) {
		super();
		getStyleClass().add("input");
		setPromptText(promptText);
	}

	public Input prompt(String promptText) {
		setPromptText(promptText);
		return this;
	}

	public Input text(String text) {
		setText(text);
		return this;
	}

	public Input fullWidth() {
		setMaxWidth(Double.MAX_VALUE);
		return this;
	}

	/** Marks the field invalid (HeroUI {@code data-invalid}); screens add their own message. */
	public Input invalid(boolean invalid) {
		Kit.setClass(this, "input--invalid", invalid);
		return this;
	}
}
