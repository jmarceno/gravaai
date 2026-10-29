package dev.gravaai.ui.kit;

import java.util.Locale;
import java.util.function.Consumer;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.geometry.Pos;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * HeroUI Switch port (plan §5.12), source {@code src/components/ui/switch.tsx} +
 * {@code @heroui/styles/components/switch.css}.
 *
 * <p>Track 40x20 px radius 12, thumb 22x16 radius 8; unchecked thumb margin 2 px, checked
 * {@code 100% - 1.5rem}; the margin change animates over 300 ms with the ease-out-fluid curve.
 * Sizes sm/md/lg follow the CSS modifiers.
 */
public final class Switch extends HBox {

	private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

	/** Switch size (switch.css size variants). */
	public enum Size { SM, MD, LG }

	private final Size size;
	private final StackPane control = new StackPane();
	private final Region thumb = new Region();
	private final Label label = new Label();
	private final BooleanProperty selected = new SimpleBooleanProperty(false);
	private Consumer<Boolean> onCheckedChange = checked -> {};
	private final TranslateTransition thumbAnimation;

	public Switch() {
		this("", Size.MD, false);
	}

	public Switch(String labelText) {
		this(labelText, Size.MD, false);
	}

	public Switch(String labelText, boolean checked) {
		this(labelText, Size.MD, checked);
	}

	public Switch(String labelText, Size size, boolean checked) {
		this.size = size;
		getStyleClass().add("switch");
		if (size != Size.MD) {
			getStyleClass().add("switch--" + size.name().toLowerCase(Locale.ROOT));
		}
		setSpacing(12);
		setAlignment(Pos.CENTER_LEFT);

		control.getStyleClass().add("switch__control");
		control.setAlignment(Pos.CENTER_LEFT);
		control.getChildren().add(thumb);
		thumb.getStyleClass().add("switch__thumb");

		label.getStyleClass().add("switch__label");
		label.setText(labelText);
		label.setVisible(!labelText.isEmpty());
		label.setManaged(!labelText.isEmpty());
		// Long labels wrap in narrow panels (the editor settings rail) instead of ending in "...".
		label.setWrapText(true);
		label.setMinWidth(0);
		control.setMinWidth(Region.USE_PREF_SIZE);
		HBox.setHgrow(label, Priority.ALWAYS);

		getChildren().addAll(control, label);
		setOnMouseClicked(event -> {
			requestFocus();
			toggle();
		});
		setFocusTraversable(true);
		setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.SPACE || event.getCode() == KeyCode.ENTER) {
				toggle();
				event.consume();
			}
		});

		thumbAnimation = new TranslateTransition(Motion.SWITCH_THUMB, thumb);
		thumbAnimation.setInterpolator(Motion.EASE_OUT_FLUID);
		selected.addListener((observable, oldValue, newValue) -> {
			pseudoClassStateChanged(SELECTED, newValue);
			animateThumb();
			onCheckedChange.accept(newValue);
		});
		setChecked(checked);
		applyThumbStart();
	}

	/** Flips the switch (used by click and keyboard). */
	public void toggle() {
		if (!isDisabled()) {
			setChecked(!isChecked());
		}
	}

	public boolean isChecked() {
		return selected.get();
	}

	public void setChecked(boolean checked) {
		if (selected.get() == checked) {
			pseudoClassStateChanged(SELECTED, checked);
			return;
		}
		// The property listener updates the pseudo-class, animates the thumb and notifies the
		// consumer. Animating here as well would restart the 300 ms HeroUI transition twice.
		selected.set(checked);
	}

	public BooleanProperty checkedProperty() {
		return selected;
	}

	/** Called with the new state, like the React {@code onCheckedChange}. */
	public Switch onCheckedChange(Consumer<Boolean> listener) {
		this.onCheckedChange = listener == null ? checked -> {} : listener;
		return this;
	}

	public Switch label(String text) {
		label.setText(text);
		label.setVisible(!text.isEmpty());
		label.setManaged(!text.isEmpty());
		return this;
	}

	private double trackWidth() {
		return switch (size) {
			case SM -> 32;
			case LG -> 48;
			case MD -> 40;
		};
	}

	private double thumbWidth() {
		return switch (size) {
			case SM -> 17;
			case LG -> 27;
			case MD -> 22;
		};
	}

	private void applyThumbStart() {
		thumb.setTranslateX(checkedX());
	}

	private double checkedX() {
		double x = isChecked() ? trackWidth() - thumbWidth() - 2 : 2;
		return x;
	}

	private void animateThumb() {
		thumbAnimation.stop();
		thumbAnimation.setToX(checkedX());
		thumbAnimation.playFromStart();
	}

	@Override
	protected void layoutChildren() {
		super.layoutChildren();
	}
}
