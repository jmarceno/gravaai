package dev.gravaai.ui.kit;

import javafx.animation.ScaleTransition;
import javafx.scene.control.ContentDisplay;
import javafx.scene.text.FontWeight;

/**
 * HeroUI Button port (plan §5.12), source {@code src/components/ui/button.tsx} +
 * {@code @heroui/styles/components/button.css}.
 *
 * <p>Variants: default (primary), destructive (danger), destructive-soft, outline, secondary,
 * ghost, link. Sizes: sm, md (default), lg, icon. Animations: pressed scale 0.97/0.98/0.96 over
 * 250 ms ease-smooth (button.css {@code transition: transform 250ms var(--ease-smooth)}), colours
 * switch instantly because JavaFX CSS has no transitions.
 */
public class Button extends javafx.scene.control.Button {

	/** The React Button {@code Variant} union. */
	public enum Variant {
		DEFAULT("button--primary", "primary"),
		DESTRUCTIVE("button--danger", "danger"),
		DESTRUCTIVE_SOFT("button--danger-soft", "danger-soft"),
		OUTLINE("button--outline", "outline"),
		SECONDARY("button--secondary", "secondary"),
		GHOST("button--ghost", "ghost"),
		LINK("button--link", "ghost");

		private final String styleClass;
		private final String heroVariant;

		Variant(String styleClass, String heroVariant) {
			this.styleClass = styleClass;
			this.heroVariant = heroVariant;
		}

		public String styleClass() {
			return styleClass;
		}

		/** The HeroUI variant name, for diagnostics. */
		public String heroVariant() {
			return heroVariant;
		}
	}

	/** Button size: default maps to HeroUI {@code md} (36 px high on desktop). */
	public enum Size { SM, MD, LG, ICON }

	/** Icon size for {@code iconSize} (Tailwind size-3.5/4/5/6). */
	public enum IconSize {
		SM(14), DEFAULT(16), LG(20), XL(24);

		private final double pixels;

		IconSize(double pixels) {
			this.pixels = pixels;
		}

		public double pixels() {
			return pixels;
		}
	}

	private final Variant variant;
	private final Size size;
	private IconSize iconSize = IconSize.DEFAULT;

	public Button() {
		this("", Variant.DEFAULT, Size.MD);
	}

	public Button(String text) {
		this(text, Variant.DEFAULT, Size.MD);
	}

	public Button(String text, Variant variant) {
		this(text, variant, Size.MD);
	}

	// Subclassed by ColorPicker; the constructor only calls final JavaFX setters.
	@SuppressWarnings("this-escape")
	public Button(String text, Variant variant, Size size) {
		super(text);
		this.variant = variant;
		this.size = size;
		getStyleClass().add("button");
		getStyleClass().add(variant.styleClass());
		switch (size) {
			case SM -> getStyleClass().add("button--sm");
			case LG -> getStyleClass().add("button--lg");
			case ICON -> getStyleClass().add("button--icon-only");
			case MD -> { /* default */ }
		}
		setFocusTraversable(true);
		setContentDisplay(ContentDisplay.LEFT);
		setOnMousePressed(event -> animatePressed(event.isStillSincePress()));
	}

	private void animatePressed(boolean still) {
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
	}

	/** Sets the icon with the size this button's {@code iconSize} asks for. */
	public Button icon(String name) {
		setGraphic(Icon.of(name, iconSize.pixels()));
		return this;
	}

	/** Sets the icon with an explicit library weight. */
	public Button icon(String name, String weight) {
		setGraphic(Icon.of(name, iconSize.pixels(), weight));
		return this;
	}

	/** Sets the icon from an already-built {@link Icon}. */
	public Button icon(Icon icon) {
		setGraphic(icon);
		return this;
	}

	public Button iconSize(IconSize iconSize) {
		this.iconSize = iconSize;
		return this;
	}

	/** HeroUI wrapper behaviour: a ghost button with {@code aria-pressed} renders as secondary. */
	public void setAriaPressed(boolean pressed) {
		boolean isGhost = variant == Variant.GHOST;
		if (isGhost) {
			getStyleClass().removeAll("button--ghost", "button--secondary");
			getStyleClass().add(pressed ? "button--secondary" : "button--ghost");
		}
		setAccessibleRoleDescription(pressed ? "pressed" : "not pressed");
	}

	public Variant variant() {
		return variant;
	}

	public Size size() {
		return size;
	}

	/** Text setter with the fluent style the screens use. */
	public Button text(String text) {
		setText(text);
		return this;
	}

	public Button fullWidth() {
		getStyleClass().add("button--full-width");
		return this;
	}

	@Override
	protected void layoutChildren() {
		super.layoutChildren();
		// Font metrics for the bundled family are only correct after AppFonts.load(); keep the
		// preferred width honest when the text changes.
	}

	/** Applies a DM Sans weight to the label (Button defaults to 500 from the stylesheet). */
	public Button fontWeight(FontWeight weight) {
		setStyle("-fx-font-weight: " + weight.getWeight() / 100.0 + ";");
		return this;
	}
}
