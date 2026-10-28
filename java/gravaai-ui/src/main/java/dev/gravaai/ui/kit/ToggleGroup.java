package dev.gravaai.ui.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/**
 * HeroUI ToggleButtonGroup port (plan §5.12), source {@code src/components/ui/toggle-group.tsx} +
 * {@code @heroui/styles/components/toggle-button-group.css}.
 *
 * <p>Attached mode: the group's buttons share edges, only the outer corners are rounded, and a
 * 1 px separator (current color at 15% opacity) sits between items. Single mode mirrors React's
 * {@code selectionMode="single"}: selecting one clears the others; an empty selection reports "".
 */
public final class ToggleGroup extends HBox {

	/** Selection behaviour from the React {@code type} prop. */
	public enum Mode { SINGLE, MULTIPLE }

	private record Item(Toggle button, String value) {}

	private final Mode mode;
	private final List<Item> items = new ArrayList<>();
	private final Map<Toggle, String> values = new LinkedHashMap<>();
	private Consumer<String> onValueChange = value -> {};
	private Consumer<List<String>> onValuesChange = values -> {};

	public ToggleGroup(Mode mode) {
		this.mode = mode;
		getStyleClass().add("toggle-group");
		setSpacing(0);
	}

	/** Adds an item with text. */
	public Toggle item(String value, String text) {
		Toggle button = new Toggle(text);
		return register(value, button);
	}

	/** Adds an item whose content is already built (icon, icon+text, ...). */
	public Toggle item(String value, Node content) {
		Toggle button = new Toggle();
		button.setGraphic(content);
		return register(value, button);
	}

	/** Adds an item with an icon and optional text. */
	public Toggle item(String value, String text, String iconName) {
		Toggle button = new Toggle(text);
		button.icon(iconName);
		return register(value, button);
	}

	private Toggle register(String value, Toggle button) {
		button.getStyleClass().add("toggle-button");
		items.add(new Item(button, value));
		values.put(button, value);
		button.selectedProperty().addListener((observable, oldValue, selected) -> {
			if (selected) {
				applySelection(button);
			} else if (mode == Mode.SINGLE) {
				notifyValue();
			} else {
				notifyValue();
			}
		});
		rebuild();
		return button;
	}

	private void applySelection(Toggle selected) {
		if (mode == Mode.SINGLE) {
			for (Item item : items) {
				if (item.button() != selected && item.button().isSelected()) {
					item.button().setSelected(false);
				}
			}
		}
		notifyValue();
	}

	private void notifyValue() {
		String value = getSelected();
		onValueChange.accept(value);
		onValuesChange.accept(getSelectedValues());
	}

	/** Rebuilds children with the separators between items (attached mode). */
	private void rebuild() {
		getChildren().clear();
		for (int i = 0; i < items.size(); i++) {
			getChildren().add(items.get(i).button());
			if (i < items.size() - 1) {
				Region separator = new Region();
				separator.getStyleClass().add("toggle-group__separator");
				separator.setMouseTransparent(true);
				getChildren().add(separator);
			}
		}
		// Only the outer edges stay rounded (toggle-button-group.css).
		for (int i = 0; i < items.size(); i++) {
			Toggle button = items.get(i).button();
			button.setStyle("-fx-background-radius: "
					+ (i == 0 && i == items.size() - 1 ? "24px"
							: i == 0 ? "24px 0 0 24px" : i == items.size() - 1 ? "0 24px 24px 0" : "0")
					+ ";");
		}
	}

	/** Detached mode: separate pills with a 4 px gap ({@code --detached}). */
	public ToggleGroup detached() {
		getStyleClass().add("toggle-group--detached");
		setSpacing(4);
		for (Item item : items) {
			item.button().setStyle("-fx-background-radius: 24px;");
		}
		return this;
	}

	/** Selects the item with {@code value}; pass null/blank to clear. */
	public void setSelected(String value) {
		for (Item item : items) {
			item.button().setSelected(value != null && value.equals(item.value()));
		}
	}

	/** The selected item's value, or "" when none is selected. */
	public String getSelected() {
		for (Item item : items) {
			if (item.button().isSelected()) {
				return item.value();
			}
		}
		return "";
	}

	/** All selected values (multiple mode). */
	public List<String> getSelectedValues() {
		List<String> selected = new ArrayList<>();
		for (Item item : items) {
			if (item.button().isSelected()) {
				selected.add(item.value());
			}
		}
		return selected;
	}

	public ToggleGroup onValueChange(Consumer<String> listener) {
		this.onValueChange = listener == null ? value -> {} : listener;
		return this;
	}

	public ToggleGroup onValuesChange(Consumer<List<String>> listener) {
		this.onValuesChange = listener == null ? values -> {} : listener;
		return this;
	}

	public ToggleGroup fullWidth() {
		getStyleClass().add("toggle-group--full-width");
		setMaxWidth(Double.MAX_VALUE);
		return this;
	}

	public List<Toggle> buttons() {
		return items.stream().map(Item::button).toList();
	}
}
