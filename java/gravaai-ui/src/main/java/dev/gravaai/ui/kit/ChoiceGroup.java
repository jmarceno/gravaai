package dev.gravaai.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.layout.FlowPane;

/**
 * HeroUI TagGroup port (plan §5.12), source {@code src/components/ui/choice-group.tsx} +
 * {@code @heroui/styles/components/tag.css}.
 *
 * <p>Mutually exclusive settings as tag chips: 12 px radius, px-2 py-1, text-xs medium,
 * {@code min-h-8}; selected chips use {@code accent-soft} + {@code accent-soft-foreground}. Like
 * the React TagGroup, the selection never becomes empty ({@code disallowEmptySelection}).
 */
public final class ChoiceGroup extends FlowPane {

	/** One tag chip. */
	public final class Item extends javafx.scene.control.ToggleButton {
		private final String value;

		Item(String value, String label) {
			super(label);
			this.value = value;
			getStyleClass().add("tag");
			setFocusTraversable(true);
		}

		@Override
		public void fire() {
			if (!isDisabled()) {
				select(value);
			}
		}
	}

	private final List<Item> items = new ArrayList<>();
	private String value = "";
	private Consumer<String> onValueChange = value -> {};

	public ChoiceGroup() {
		getStyleClass().add("tag-group__list");
		setHgap(8);
		setVgap(8);
		setAlignment(Pos.CENTER_LEFT);
	}

	/** Adds a chip with a text label. */
	public Item item(String itemValue, String label) {
		Item item = new Item(itemValue, label);
		items.add(item);
		getChildren().add(item);
		return item;
	}

	/** Adds a chip with an icon and a text label. */
	public Item item(String itemValue, String label, String iconName) {
		Item item = item(itemValue, label);
		item.setGraphic(Icon.of(iconName, 14));
		return item;
	}

	/** Selects a value; blank is ignored because the group never allows an empty selection. */
	public void setValue(String newValue) {
		if (newValue == null || newValue.isEmpty()) {
			return;
		}
		select(newValue);
	}

	private void select(String newValue) {
		value = newValue;
		for (Item item : items) {
			item.setSelected(item.value.equals(newValue));
		}
		onValueChange.accept(value);
	}

	public String value() {
		return value;
	}

	public ChoiceGroup onValueChange(Consumer<String> listener) {
		this.onValueChange = listener == null ? value -> {} : listener;
		return this;
	}
}
