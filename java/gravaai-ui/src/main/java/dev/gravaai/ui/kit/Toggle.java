package dev.gravaai.ui.kit;

import java.util.function.Consumer;
import javafx.animation.ScaleTransition;

/**
 * HeroUI ToggleButton port (plan §5.12), source {@code src/components/ui/toggle.tsx} +
 * {@code @heroui/styles/components/toggle-button.css}.
 *
 * <p>The React wrapper always uses the ghost variant unless {@code variant="outline"} adds the
 * border; selected state is accent-soft. Pressed scale 0.97 over 250 ms ease-smooth.
 */
public final class Toggle extends javafx.scene.control.ToggleButton {

	/** Toggle variant from toggle.tsx. */
	public enum Variant { GHOST, OUTLINE }

	/** Toggle size. */
	public enum Size { SM, MD, LG }

	private Consumer<Boolean> onPressedChange = pressed -> {};

	public Toggle() {
		this("");
	}

	public Toggle(String text) {
		this(text, Variant.GHOST, Size.MD);
	}

	public Toggle(String text, Variant variant, Size size) {
		super(text);
		getStyleClass().add("toggle-button");
		getStyleClass().add(variant == Variant.OUTLINE ? "toggle-button--outline" : "toggle-button--ghost");
		switch (size) {
			case SM -> getStyleClass().add("toggle-button--sm");
			case LG -> getStyleClass().add("toggle-button--lg");
			case MD -> { /* default */ }
		}
		setFocusTraversable(true);
		selectedProperty().addListener((observable, oldValue, newValue) -> onPressedChange.accept(newValue));
		setOnMousePressed(event -> {
			if (isDisabled()) {
				return;
			}
			double target = switch (size) {
				case SM -> 0.98;
				case LG -> 0.96;
				default -> 0.97;
			};
			ScaleTransition press = Motion.scaleTo(this, target, Motion.BUTTON_PRESS, Motion.EASE_SMOOTH);
			press.play();
		});
		setOnMouseReleased(event -> Motion.scaleTo(this, 1, Motion.BUTTON_PRESS, Motion.EASE_SMOOTH).play());
	}

	/** Called with the new selected state, like the React {@code onPressedChange}. */
	public Toggle onPressedChange(Consumer<Boolean> listener) {
		this.onPressedChange = listener == null ? pressed -> {} : listener;
		return this;
	}

	/** Sets the icon at the HeroUI toggle icon size (16 px desktop). */
	public Toggle icon(String name) {
		setGraphic(Icon.of(name, 16));
		return this;
	}

	public Toggle icon(String name, String weight) {
		setGraphic(Icon.of(name, 16, weight));
		return this;
	}

	public Toggle pressed(boolean pressed) {
		setSelected(pressed);
		return this;
	}
}
