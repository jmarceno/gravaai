package dev.gravaai.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * HeroUI Dropdown port (plan §5.12), source {@code src/components/ui/dropdown-menu.tsx} +
 * {@code @heroui/styles/components/dropdown.css,menu.css,menu-item.css}.
 *
 * <p>Overlay background radius 24, menu padding 4, items 36 px high radius 16 px-2 py-1.5 with a
 * 12 px gap and hover background {@code -g-default}; labels are 9 px uppercase (the app's
 * {@code .ddLabel} from LaunchWindow.module.css), separators 94% wide. Keyboard: up/down +
 * enter, escape closes (Popup).
 */
public final class DropdownMenu {

	private record Row(HBox node, Runnable action) {}

	private final Node trigger;
	private final VBox menu = new VBox();
	private final VBox content = new VBox();
	private final Popup popup;
	private final List<Row> rows = new ArrayList<>();
	private final List<HBox> disabledRows = new ArrayList<>();
	private Consumer<Boolean> onOpenChange = open -> {};
	private int highlighted = -1;
	private boolean open;
	private final EventHandler<KeyEvent> keyHandler = this::handleKey;

	public DropdownMenu(Node trigger) {
		this.trigger = trigger;
		menu.getStyleClass().add("menu");
		content.getStyleClass().add("popover");
		content.getChildren().add(menu);
		popup = new Popup(trigger, content);
		popup.side(Anchors.Side.BOTTOM).align(Anchors.Align.START).offset(8).modal(true);
		popup.onHide(closed -> {
			open = false;
			highlighted = -1;
			applyHighlight();
			if (trigger.getScene() != null) {
				trigger.getScene().removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
			}
			onOpenChange.accept(false);
		});
		installTriggerToggle();
	}

	/**
	 * Installs the default click-to-open handler, unless a Button trigger already owns an
	 * action. Action-managed triggers (Home, Export) open/close this menu themselves via
	 * {@link #toggle()}; listening to MOUSE_CLICKED as well would open and immediately close it.
	 */
	private void installTriggerToggle() {
		if (trigger instanceof ButtonBase button && button.getOnAction() != null) {
			return;
		}
		trigger.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> toggle());
	}

	/** A menu item with an action. */
	public DropdownMenu item(String label, Runnable action) {
		return item(label, null, null, action);
	}

	/** A menu item with a keyboard shortcut hint (Kbd). */
	public DropdownMenu item(String label, String shortcut, Runnable action) {
		return item(label, null, shortcut, action);
	}

	/** A menu item with a leading icon. */
	public DropdownMenu item(String label, String iconName, String shortcut, Runnable action) {
		HBox row = new HBox();
		row.getStyleClass().add("menu-item");
		row.setAlignment(Pos.CENTER_LEFT);
		row.setSpacing(12);
		if (iconName != null) {
			row.getChildren().add(Icon.of(iconName, 16));
		}
		Label text = new Label(label);
		text.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(text, Priority.ALWAYS);
		row.getChildren().add(text);
		if (shortcut != null && !shortcut.isEmpty()) {
			Label hint = new Label(shortcut);
			hint.getStyleClass().add("menu-item__shortcut");
			row.getChildren().add(hint);
		}
		rows.add(new Row(row, action));
		row.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
			hide();
			if (action != null) {
				action.run();
			}
		});
		menu.getChildren().add(row);
		return this;
	}

	/** A danger item (destructive actions): danger text/icon colour. */
	public DropdownMenu dangerItem(String label, Runnable action) {
		item(label, action);
		Row row = rows.get(rows.size() - 1);
		row.node().getStyleClass().add("menu-item--danger");
		return this;
	}

	/** A disabled item (HeroUI isDisabled): no action, dimmed; keyboard navigation skips it. */
	public DropdownMenu disabledItem(String label, String iconName) {
		return disabledItem(label, iconName, false);
	}

	/** A disabled item with the danger styling (destructive actions). */
	public DropdownMenu dangerDisabledItem(String label, String iconName) {
		return disabledItem(label, iconName, true);
	}

	private DropdownMenu disabledItem(String label, String iconName, boolean danger) {
		HBox row = new HBox();
		row.getStyleClass().add("menu-item");
		row.setAlignment(Pos.CENTER_LEFT);
		row.setSpacing(12);
		if (iconName != null) {
			row.getChildren().add(Icon.of(iconName, 16));
		}
		Label text = new Label(label);
		text.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(text, Priority.ALWAYS);
		row.getChildren().add(text);
		if (danger) {
			row.getStyleClass().add("menu-item--danger");
		}
		row.setDisable(true);
		disabledRows.add(row);
		menu.getChildren().add(row);
		return this;
	}

	/** Updates a disabled item's state (dynamic HeroUI isDisabled bindings). */
	public void setDisabled(int disabledIndex, boolean disabled) {
		disabledRows.get(disabledIndex).setDisable(disabled);
	}

	/** A group label (HeroUI Dropdown.Section label / app .ddLabel). */
	public DropdownMenu label(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("menu__label");
		menu.getChildren().add(label);
		return this;
	}

	/** A separator between groups. */
	public DropdownMenu separator() {
		Region separator = new Region();
		separator.getStyleClass().add("menu__separator");
		separator.setMaxWidth(Double.MAX_VALUE);
		VBox.setMargin(separator, new javafx.geometry.Insets(4, 0, 4, 0));
		menu.getChildren().add(separator);
		return this;
	}

	public DropdownMenu onOpenChange(Consumer<Boolean> listener) {
		this.onOpenChange = listener == null ? open -> {} : listener;
		return this;
	}

	public void toggle() {
		if (popup.isShowing()) {
			hide();
		} else {
			show();
		}
	}

	public void show() {
		if (open) {
			return;
		}
		open = true;
		highlighted = -1;
		if (trigger.getScene() != null) {
			trigger.getScene().addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
		}
		popup.show();
		onOpenChange.accept(true);
	}

	public void hide() {
		popup.hide();
	}

	public boolean isShowing() {
		return popup.isShowing();
	}

	private void applyHighlight() {
		for (int i = 0; i < rows.size(); i++) {
			Kit.setClass(rows.get(i).node(), "menu-item--highlighted", i == highlighted);
		}
	}

	private void handleKey(KeyEvent event) {
		if (!open) {
			return;
		}
		switch (event.getCode()) {
			case DOWN -> {
				highlighted = Math.min(rows.size() - 1, highlighted + 1);
				applyHighlight();
				event.consume();
			}
			case UP -> {
				highlighted = Math.max(0, highlighted - 1);
				applyHighlight();
				event.consume();
			}
			case ENTER -> {
				if (highlighted >= 0 && highlighted < rows.size()) {
					Row row = rows.get(highlighted);
					hide();
					row.action().run();
				}
				event.consume();
			}
			default -> { /* escape handled by Popup */ }
		}
	}
}
