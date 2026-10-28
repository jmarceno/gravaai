package dev.gravaai.ui.editor;

import java.util.Map;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Popover;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Keyboard-shortcuts popover (port of {@code KeyboardShortcutsHelp.tsx}): a help icon that opens
 * the shortcut list with the customize link to {@link ShortcutsConfigDialog}.
 */
public final class KeyboardShortcutsHelp {

	private final Button trigger;
	private final VBox content = new VBox(8);
	private final Popover popover;
	private final Runnable openConfig;

	public KeyboardShortcutsHelp(Map<Shortcuts.Action, Shortcuts.Binding> shortcuts, boolean isMac,
			Runnable openConfig) {
		this.openConfig = openConfig == null ? () -> { } : openConfig;
		trigger = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		trigger.icon(Icons.Keyboard);
		trigger.setAccessibleText("Keyboard Shortcuts");
		popover = new Popover(trigger);
		popover.content(content);
		refresh(shortcuts, isMac);
	}

	/** The trigger node to place in the editor header/timeline toolbar. */
	public Node node() {
		return trigger;
	}

	public Popover popover() {
		return popover;
	}

	/** Rebuilds the list for the current bindings/platform. */
	public void refresh(Map<Shortcuts.Action, Shortcuts.Binding> shortcuts, boolean isMac) {
		content.getChildren().clear();
		content.setPadding(new javafx.geometry.Insets(12));
		content.setPrefWidth(320);

		Label title = new Label("Keyboard Shortcuts");
		title.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: -g-fg;");
		Button customize = new Button("Customize", Button.Variant.GHOST, Button.Size.SM);
		customize.icon(Icons.GearSix);
		customize.setGraphicTextGap(4);
		customize.setContentDisplay(ContentDisplay.LEFT);
		customize.setOnAction(event -> {
			popover.hide();
			this.openConfig.run();
		});
		HBox header = new HBox(8, title, spacer(), customize);
		header.setAlignment(Pos.CENTER_LEFT);
		content.getChildren().add(header);

		for (Shortcuts.Action action : Shortcuts.Action.values()) {
			content.getChildren().add(row(Shortcuts.label(action),
					Shortcuts.formatBinding(shortcuts.getOrDefault(action,
							Shortcuts.defaults().get(action)), isMac)));
		}

		Region divider = new Region();
		divider.setMinHeight(1);
		divider.setPrefHeight(1);
		divider.setStyle("-fx-background-color: rgba(255,255,255,0.06);");
		content.getChildren().add(divider);
		content.getChildren().addAll(
				row("Save Project", isMac ? "⌘ S" : "Ctrl + S"),
				row("Pan Timeline", "Shift + Scroll"),
				row("Zoom Timeline", "Ctrl + Scroll"),
				row("Cycle Annotations", "Tab"));
	}

	private HBox row(String label, String keys) {
		Label name = new Label(label);
		name.setStyle("-fx-font-size: 10px; -fx-text-fill: -g-muted;");
		Label kbd = new Label(keys);
		kbd.setStyle(
				"-fx-font-size: 10px; -fx-text-fill: rgba(255,255,255,0.8); "
						+ "-fx-background-color: rgba(255,255,255,0.08); "
						+ "-fx-background-radius: 4px; -fx-padding: 1 5 1 5; "
						+ "-fx-font-family: monospace;");
		HBox row = new HBox(8, name, spacer(), kbd);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private static Region spacer() {
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		return spacer;
	}

	/** Icon colour helper kept for screens that mount the trigger in a dark bar. */
	public static Icon helpIcon() {
		Icon icon = Icon.of(Icons.Keyboard, 16);
		icon.setIconColor(Color.web("#8b929a"));
		return icon;
	}
}
