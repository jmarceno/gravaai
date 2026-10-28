package dev.gravaai.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * HeroUI Select port (plan §5.12), source {@code src/components/ui/select.tsx} +
 * {@code @heroui/styles/components/select.css,list-box-item.css}.
 *
 * <p>Trigger: field background, radius 12, px-3 py-2 (12/8), min height 36, chevron indicator on
 * the end. Popup: overlay background radius 24, list-box p-1.5 (6), items 36 px radius 16 with a
 * check indicator on the selected one. The popup is the kit's in-window overlay, not an OS window.
 *
 * <pre>{@code
 * Select quality = new Select();
 * quality.placeholder("Quality");
 * quality.item("1080p", "1080p").item("720p", "720p");
 * quality.onValueChange(value -> ...);
 * }</pre>
 */
public final class Select extends VBox {

	private final HBox trigger = new HBox();
	private final Label valueLabel = new Label();
	private final Icon indicator = Icon.of(Icons.CaretDown, 16);
	private final VBox listBox = new VBox();
	private final ScrollPane scroll = new ScrollPane(listBox);
	private final VBox popupContent = new VBox(scroll);
	private final List<Item> items = new ArrayList<>();
	private final Popup popup;
	private String value = "";
	private String placeholder = "Select";
	private Consumer<String> onValueChange = value -> {};
	private int highlighted = -1;
	private final EventHandler<KeyEvent> keyHandler = this::handleKey;
	private boolean open;

	private record Item(String value, String label, HBox node, Icon check) {}

	public Select() {
		getStyleClass().add("select");
		setSpacing(4);

		trigger.getStyleClass().add("select__trigger");
		trigger.setAlignment(Pos.CENTER_LEFT);
		trigger.setFocusTraversable(true);
		valueLabel.getStyleClass().add("select__value");
		valueLabel.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(valueLabel, Priority.ALWAYS);
		indicator.getStyleClass().add("select__indicator");
		trigger.getChildren().addAll(valueLabel, indicator);
		trigger.setOnMouseClicked(event -> {
			requestFocus();
			toggle();
		});
		trigger.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.SPACE || event.getCode() == KeyCode.ENTER
					|| event.getCode() == KeyCode.DOWN) {
				toggle();
				event.consume();
			}
		});

		popupContent.getStyleClass().add("popover");
		listBox.getStyleClass().add("list-box");
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setMaxHeight(320);
		scroll.getStyleClass().add("custom-scrollbar");
		popup = new Popup(trigger, popupContent);
		popup.side(Anchors.Side.BOTTOM).align(Anchors.Align.START).offset(4).modal(true);
		popup.onHide(closed -> {
			open = false;
			indicator.getStyleClass().remove("select__indicator--open");
			trigger.getStyleClass().remove("showing");
			if (getScene() != null) {
				getScene().removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
			}
		});

		getChildren().add(trigger);
		updateValueLabel();
	}

	/** Adds a selectable item. */
	public Select item(String itemValue, String label) {
		HBox row = new HBox();
		row.getStyleClass().add("list-box-item");
		row.setAlignment(Pos.CENTER_LEFT);
		Label text = new Label(label);
		text.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(text, Priority.ALWAYS);
		Icon check = Icon.of(Icons.Check, 12, "bold");
		check.getStyleClass().add("list-box-item__indicator");
		check.setVisible(false);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		row.getChildren().addAll(text, spacer, check);
		Item item = new Item(itemValue, label, row, check);
		items.add(item);
		row.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
			setValue(itemValue);
			hide();
		});
		listBox.getChildren().add(row);
		return this;
	}

	/** A non-selectable group label (HeroUI ListBox.Section / Label). */
	public Select group(String label) {
		Label heading = new Label(label);
		heading.getStyleClass().add("menu__label");
		listBox.getChildren().add(heading);
		return this;
	}

	/** A separator between item groups. */
	public Select separator() {
		Separator line = new Separator();
		line.setMaxWidth(Double.MAX_VALUE);
		VBox.setMargin(line, new javafx.geometry.Insets(4, 0, 4, 0));
		listBox.getChildren().add(line);
		return this;
	}

	public Select placeholder(String placeholder) {
		this.placeholder = placeholder == null ? "" : placeholder;
		updateValueLabel();
		return this;
	}

	/** Selects a value by key; blank clears the selection. */
	public Select value(String newValue) {
		setValue(newValue);
		return this;
	}

	private void setValue(String newValue) {
		String next = newValue == null ? "" : newValue;
		if (next.equals(value)) {
			return;
		}
		value = next;
		updateValueLabel();
		for (Item item : items) {
			item.check().setVisible(item.value().equals(value));
		}
		onValueChange.accept(value);
	}

	public String value() {
		return value;
	}

	private void updateValueLabel() {
		boolean hasValue = !value.isEmpty();
		String text = placeholder;
		for (Item item : items) {
			if (item.value().equals(value)) {
				text = item.label();
				break;
			}
		}
		valueLabel.setText(text);
		Kit.setClass(valueLabel, "select__value--placeholder", !hasValue);
	}

	public Select onValueChange(Consumer<String> listener) {
		this.onValueChange = listener == null ? value -> {} : listener;
		return this;
	}

	/** Full width trigger/popup (select.css {@code --full-width}). */
	public Select fullWidth() {
		setMaxWidth(Double.MAX_VALUE);
		trigger.setMaxWidth(Double.MAX_VALUE);
		popupContent.setMinWidth(180);
		return this;
	}

	public Select disabled(boolean disabled) {
		setDisable(disabled);
		return this;
	}

	public HBox trigger() {
		return trigger;
	}

	private void toggle() {
		if (isDisabled()) {
			return;
		}
		if (open) {
			hide();
		} else {
			show();
		}
	}

	private void show() {
		if (open) {
			return;
		}
		open = true;
		popupContent.setPrefWidth(Math.max(trigger.getWidth(), 180));
		highlighted = Math.max(0, indexOf(value));
		applyHighlight();
		if (getScene() != null) {
			getScene().addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
		}
		trigger.getStyleClass().add("showing");
		indicator.getStyleClass().add("select__indicator--open");
		popup.show();
	}

	private void hide() {
		popup.hide();
	}

	private int indexOf(String key) {
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).value().equals(key)) {
				return i;
			}
		}
		return 0;
	}

	private void applyHighlight() {
		for (int i = 0; i < items.size(); i++) {
			Kit.setClass(items.get(i).node(), "list-box-item--highlighted", i == highlighted);
		}
	}

	private void handleKey(KeyEvent event) {
		if (!open) {
			return;
		}
		switch (event.getCode()) {
			case DOWN -> {
				highlighted = Math.min(items.size() - 1, highlighted + 1);
				applyHighlight();
				event.consume();
			}
			case UP -> {
				highlighted = Math.max(0, highlighted - 1);
				applyHighlight();
				event.consume();
			}
			case ENTER, SPACE -> {
				if (highlighted >= 0 && highlighted < items.size()) {
					setValue(items.get(highlighted).value());
					hide();
				}
				event.consume();
			}
			default -> { /* Popup handles Escape */ }
		}
	}
}
