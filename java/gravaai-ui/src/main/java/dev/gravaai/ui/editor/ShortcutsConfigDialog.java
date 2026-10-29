package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Dialog;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Label;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Shortcut configuration dialog (port of {@code ShortcutsConfigDialog.tsx}): click a binding, press
 * the new combination, conflicts are detected with {@link Shortcuts#findConflict} and can be swapped
 * or cancelled; fixed shortcuts are listed read-only. Save hands the draft back to the caller, which
 * persists it (same split as the React context).
 */
public final class ShortcutsConfigDialog {

	private final boolean isMac;
	private final Consumer<Map<Shortcuts.Action, Shortcuts.Binding>> onSave;
	private final Runnable onClose;
	private final Dialog dialog;
	private final VBox body = new VBox(16);
	private final Label status = new Label();
	private final Map<Shortcuts.Action, Button> buttons = new LinkedHashMap<>();
	private Map<Shortcuts.Action, Shortcuts.Binding> draft;
	private Shortcuts.@Nullable Action captureFor;
	private Shortcuts.@Nullable Action conflictFor;
	private Shortcuts.@Nullable Binding pendingBinding;
	private Shortcuts.@Nullable Conflict conflict;

	public ShortcutsConfigDialog(boolean isMac,
			Map<Shortcuts.Action, Shortcuts.Binding> initial,
			Consumer<Map<Shortcuts.Action, Shortcuts.Binding>> onSave, Runnable onClose) {
		this.isMac = isMac;
		this.onSave = onSave == null ? shortcuts -> { } : onSave;
		this.onClose = onClose == null ? () -> { } : onClose;
		this.draft = Shortcuts.mergeWithDefaults(initial);

		dialog = new Dialog("Keyboard Shortcuts");
		dialog.description("Click a shortcut then press the new key combination. "
				+ "Press Esc to cancel.");
		dialog.width(520);
		dialog.content(body);
		dialog.panel().addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);

		status.setWrapText(true);
		status.setStyle("-fx-font-size: 11px; -fx-text-fill: -g-warning;");
		status.setManaged(false);
		status.setVisible(false);

		Button reset = new Button("Reset to defaults", Button.Variant.GHOST, Button.Size.SM);
		reset.icon(Icons.ArrowCounterClockwise);
		reset.setGraphicTextGap(6);
		reset.setContentDisplay(ContentDisplay.LEFT);
		reset.setOnAction(event -> {
			draft = Shortcuts.defaults();
			conflict = null;
			conflictFor = null;
			setStatus("Reset to default shortcuts — click Save to apply", false);
			rebuild();
		});
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.SM);
		cancel.setOnAction(event -> close());
		Button save = new Button("Save", Button.Variant.DEFAULT, Button.Size.SM);
		save.setOnAction(event -> {
			onSave.accept(new LinkedHashMap<>(draft));
			close();
		});
		HBox footer = new HBox(8, reset, spacer, cancel, save);
		footer.setAlignment(Pos.CENTER_LEFT);
		dialog.footer(footer);
		rebuild();
	}

	/** Opens the dialog inside the kit root that owns {@code owner}. */
	public void show(javafx.scene.Node owner) {
		rebuild();
		dialog.show(owner);
	}

	public void close() {
		captureFor = null;
		conflict = null;
		dialog.close();
		onClose.run();
	}

	/** The current draft (test/automation access). */
	public Map<Shortcuts.Action, Shortcuts.Binding> draft() {
		return new LinkedHashMap<>(draft);
	}

	public Dialog dialog() {
		return dialog;
	}

	private void rebuild() {
		body.getChildren().clear();
		buttons.clear();

		Label configurable = sectionTitle("Configurable");
		body.getChildren().add(configurable);
		for (Shortcuts.Action action : Shortcuts.Action.values()) {
			Button binding = new Button("", Button.Variant.SECONDARY, Button.Size.SM);
			binding.getStyleClass().add("shortcuts-config__binding");
			binding.setOnAction(event -> {
				conflict = null;
				conflictFor = null;
				captureFor = captureFor == action ? null : action;
				rebuild();
			});
			buttons.put(action, binding);
			HBox row = new HBox(12, new Label(Shortcuts.label(action)), spacer(), binding);
			row.setAlignment(Pos.CENTER_LEFT);
			row.setPadding(new Insets(8, 0, 8, 0));
			row.setStyle("-fx-border-color: transparent transparent rgba(255,255,255,0.06) transparent;");
			body.getChildren().add(row);

			if (conflictFor == action && conflict instanceof Shortcuts.ConfigurableConflict other) {
				body.getChildren().add(conflictRow(other.action()));
			}
			refreshBindingButton(action);
		}

		body.getChildren().add(sectionTitle("Fixed"));
		for (Shortcuts.FixedShortcut fixed : Shortcuts.FIXED_SHORTCUTS) {
			Label keys = new Label(fixed.display());
			keys.setStyle("-fx-font-size: 10px; -fx-text-fill: rgba(255,255,255,0.8); "
					+ "-fx-background-color: rgba(255,255,255,0.08); "
					+ "-fx-background-radius: 4px; -fx-padding: 2 8 2 8;");
			HBox row = new HBox(12, new Label(fixed.label()), spacer(), keys);
			row.setAlignment(Pos.CENTER_LEFT);
			row.setPadding(new Insets(6, 0, 6, 0));
			body.getChildren().add(row);
		}
		body.getChildren().add(status);
	}

	private void refreshBindingButton(Shortcuts.Action action) {
		Button button = buttons.get(action);
		if (button == null) {
			return;
		}
		button.setText(captureFor == action ? "Press a key…"
				: Shortcuts.formatBinding(draft.get(action), isMac));
	}

	private HBox conflictRow(Shortcuts.Action usedBy) {
		Label message = new Label("Already used by " + Shortcuts.label(usedBy));
		message.setStyle("-fx-font-size: 11px; -fx-text-fill: -g-warning;");
		Button swap = new Button("Swap", Button.Variant.GHOST, Button.Size.SM);
		swap.setOnAction(event -> {
			Shortcuts.Binding previous = draft.get(conflictFor);
			draft.put(conflictFor, pendingBinding);
			draft.put(usedBy, previous);
			conflict = null;
			conflictFor = null;
			rebuild();
		});
		Button cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.SM);
		cancel.setOnAction(event -> {
			conflict = null;
			conflictFor = null;
			rebuild();
		});
		HBox row = new HBox(8, message, spacer(), swap, cancel);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(4, 8, 4, 8));
		row.setStyle("-fx-background-color: rgba(232,179,57,0.12); -fx-background-radius: 6px;");
		return row;
	}

	private void handleKey(KeyEvent event) {
		if (captureFor == null) {
			return;
		}
		event.consume();
		if (event.getCode() == KeyCode.ESCAPE) {
			captureFor = null;
			rebuild();
			return;
		}
		if (event.getCode().isModifierKey()) {
			return;
		}

		Shortcuts.Binding binding = new Shortcuts.Binding(keyFromEvent(event),
				event.isControlDown() || event.isMetaDown(), event.isShiftDown(),
				event.isAltDown());
		Shortcuts.Action action = captureFor;
		Shortcuts.Conflict found = Shortcuts.findConflict(binding, action, draft);
		captureFor = null;

		if (found instanceof Shortcuts.FixedConflict fixed) {
			setStatus("This shortcut is reserved for \"" + fixed.label()
					+ "\" and cannot be reassigned.", true);
			rebuild();
			return;
		}
		if (found instanceof Shortcuts.ConfigurableConflict configurable) {
			conflict = configurable;
			conflictFor = action;
			pendingBinding = binding;
			setStatus("", false);
			rebuild();
			return;
		}
		draft.put(action, binding);
		setStatus("", false);
		rebuild();
	}

	private void setStatus(String message, boolean visible) {
		status.setText(message);
		status.setManaged(visible);
		status.setVisible(visible);
	}

	private static String keyFromEvent(KeyEvent event) {
		if (event.getCode() == KeyCode.SPACE) {
			return " ";
		}
		String text = event.getText();
		if (text != null && !text.isEmpty() && text.codePoints().allMatch(Character::isLetterOrDigit)) {
			return text.toLowerCase(Locale.ROOT);
		}
		return event.getCode().getName().toLowerCase(Locale.ROOT);
	}

	private static Label sectionTitle(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: -g-fg;");
		return label;
	}

	private static Region spacer() {
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		return spacer;
	}

	/** Icon used by the dialog title in the React source. */
	static Icon keyboardIcon() {
		return Icon.of(Icons.Keyboard, 16);
	}

	/** Exposed for the shell/tests: the action list in visual order. */
	static List<Shortcuts.Action> actions() {
		return List.of(Shortcuts.Action.values());
	}
}
