package dev.gravaai.ui.dashboard;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import dev.gravaai.services.shortcuts.GlobalShortcutAction;
import dev.gravaai.services.shortcuts.GlobalShortcutBinding;
import dev.gravaai.services.shortcuts.GlobalShortcutKeyEvent;
import dev.gravaai.services.shortcuts.GlobalShortcuts;
import dev.gravaai.services.shortcuts.GlobalShortcutsConfig;
import dev.gravaai.services.shortcuts.GlobalShortcutsStatus;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Switch;
import dev.gravaai.ui.kit.Toast;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Settings -> Shortcuts (port of {@code GlobalShortcutsSettings.tsx}).
 *
 * <p>On COSMIC it lists the four actions with their bindings; clicking a binding captures the next
 * key press ({@code Super/Ctrl/Alt + key}, Escape cancels) and applies it through
 * {@link GlobalShortcutsController}. Every other desktop shows the {@code --action} CLI commands
 * with a Copy button instead. The service applies COSMIC's file splice; this page only renders and
 * captures.
 */
public final class GlobalShortcutsSettingsView extends VBox {

	/** {@code MODIFIER_CODES} in the React source. */
	private static final Pattern MODIFIER_CODES = Pattern.compile("^(Shift|Control|Alt|Meta|OS)(Left|Right)?$");

	private final GlobalShortcutsController controller;
	private final VBox rows = new VBox(16);
	private final Label errorBanner = new Label();
	private final javafx.event.EventHandler<KeyEvent> captureHandler = this::handleCaptureKey;

	private GlobalShortcutsConfig config;
	private GlobalShortcutsStatus status;
	private GlobalShortcutAction capturing;
	private boolean busy;

	public GlobalShortcutsSettingsView(GlobalShortcutsController controller) {
		this.controller = controller;
		getStyleClass().add("global-shortcuts-settings");
		setSpacing(24);
		errorBanner.getStyleClass().add("global-shortcuts-settings__error");
		errorBanner.setWrapText(true);
		errorBanner.setVisible(false);
		errorBanner.setManaged(false);
		getChildren().addAll(errorBanner, rows);
		CompletableFuture.runAsync(() -> {
			try {
				GlobalShortcutsConfig loadedConfig = controller.readConfig();
				GlobalShortcutsStatus loadedStatus = controller.status();
				Platform.runLater(() -> {
					config = loadedConfig;
					status = loadedStatus;
					rebuild();
				});
			} catch (RuntimeException error) {
				Platform.runLater(() -> showError(message(error)));
			}
		});
	}

	private void rebuild() {
		rows.getChildren().clear();
		if (config == null) {
			return;
		}
		boolean cosmic = controller.cosmicDesktop();
		if (status != null && status.error() != null && !status.error().isBlank()) {
			errorBanner.setText(status.error());
			errorBanner.setVisible(true);
			errorBanner.setManaged(true);
		} else {
			errorBanner.setVisible(false);
			errorBanner.setManaged(false);
		}

		Switch enabled = new Switch("", config.enabled());
		enabled.setAccessibleText("Global shortcuts");
		enabled.setDisable(busy || !cosmic);
		enabled.onCheckedChange(checked ->
				apply(new GlobalShortcutsConfig(checked, config.bindings())));
		rows.getChildren().add(new SettingsRow("Global shortcuts", cosmic
				? "Work in every app. Gravaai adds them to COSMIC's keyboard shortcuts for you."
				: "Your desktop doesn't let apps add shortcuts. Bind these commands in its keyboard settings:",
				enabled));

		if (!cosmic) {
			buildCliCommands(rows);
			return;
		}
		if (!config.enabled()) {
			return;
		}
		for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
			rows.getChildren().add(buildActionRow(action));
		}
		Label note = new Label(
				"While you pick a shortcut, a combination already used by a Gravaai shortcut also triggers it.");
		note.getStyleClass().add("global-shortcuts-settings__note");
		note.setWrapText(true);
		rows.getChildren().add(note);
		Button reset = new Button("Reset to defaults", Button.Variant.GHOST, Button.Size.SM);
		reset.setGraphic(Icon.of(Icons.ArrowCounterClockwise, 14));
		reset.setGraphicTextGap(6);
		reset.setDisable(busy);
		reset.setOnAction(event -> reset());
		rows.getChildren().add(reset);
	}

	private void buildCliCommands(VBox host) {
		java.util.Map<GlobalShortcutAction, String> commands =
				status == null ? java.util.Map.of() : status.commands();
		for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
			String command = commands.getOrDefault(action, "");
			Label code = new Label(command);
			code.getStyleClass().add("global-shortcuts-settings__command");
			code.setMaxWidth(Double.MAX_VALUE);
			Button copy = new Button("Copy", Button.Variant.SECONDARY, Button.Size.SM);
			copy.setOnAction(event -> copyCommand(command));
			host.getChildren().add(new SettingsRow(
					GlobalShortcuts.GLOBAL_SHORTCUT_LABELS.get(action), "", copy));
		}
	}

	private Region buildActionRow(GlobalShortcutAction action) {
		String label = GlobalShortcuts.GLOBAL_SHORTCUT_LABELS.get(action);
		GlobalShortcutBinding binding = config.bindings().get(action);
		String conflict = status == null ? null : status.conflicts().get(action);
		boolean isCapturing = capturing == action;

		Button bindingButton = new Button(isCapturing ? "Press a shortcut…"
				: binding == null ? "Not set" : GlobalShortcuts.formatGlobalBinding(binding),
				Button.Variant.SECONDARY, Button.Size.SM);
		bindingButton.getStyleClass().add("global-shortcuts-settings__binding");
		bindingButton.setMinWidth(132);
		bindingButton.setDisable(busy);
		bindingButton.setOnAction(event -> startCapture(action));

		Button clear = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		clear.setGraphic(Icon.of(Icons.X, 14));
		clear.setAccessibleText("Clear " + label + " shortcut");
		clear.setDisable(busy || binding == null || isCapturing);
		clear.setOnAction(event -> {
			java.util.Map<GlobalShortcutAction, GlobalShortcutBinding> bindings =
					new java.util.LinkedHashMap<>(config.bindings());
			bindings.put(action, null);
			apply(new GlobalShortcutsConfig(config.enabled(), bindings));
		});

		HBox controls = new HBox(4, bindingButton, clear);
		controls.setAlignment(Pos.CENTER_RIGHT);
		return new SettingsRow(label, conflict == null ? "" : conflict, controls);
	}

	private void startCapture(GlobalShortcutAction action) {
		if (busy) {
			return;
		}
		if (capturing != null) {
			capturing = null;
			rebuild();
			return;
		}
		capturing = action;
		rebuild();
		if (getScene() != null) {
			getScene().addEventFilter(KeyEvent.KEY_PRESSED, captureHandler);
		}
	}

	private void handleCaptureKey(KeyEvent event) {
		if (capturing == null) {
			return;
		}
		event.consume();
		String code = event.getCode().getName();
		if (MODIFIER_CODES.matcher(code).matches()) {
			return;
		}
		boolean plain = !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
				&& !event.isShiftDown();
		if (event.getCode() == KeyCode.ESCAPE && plain) {
			stopCapture();
			return;
		}
		GlobalShortcutKeyEvent keyEvent = new GlobalShortcutKeyEvent(javafxCode(event.getCode()),
				event.isControlDown(), event.isAltDown(), event.isShiftDown(), event.isMetaDown());
		GlobalShortcutBinding binding = GlobalShortcuts.keyboardEventToBinding(keyEvent);
		if (binding == null || !GlobalShortcuts.isValidGlobalBinding(binding)) {
			Platform.runLater(() -> Toast.show(this, "Use Super, Ctrl or Alt with a key")
					.variant(Toast.Variant.DANGER));
			return;
		}
		GlobalShortcutAction action = capturing;
		stopCapture();
		java.util.Map<GlobalShortcutAction, GlobalShortcutBinding> bindings =
				new java.util.LinkedHashMap<>(config.bindings());
		bindings.put(action, binding);
		apply(new GlobalShortcutsConfig(config.enabled(), bindings));
	}

	private void stopCapture() {
		capturing = null;
		if (getScene() != null) {
			getScene().removeEventFilter(KeyEvent.KEY_PRESSED, captureHandler);
		}
		rebuild();
	}

	private void apply(GlobalShortcutsConfig next) {
		if (busy) {
			return;
		}
		busy = true;
		rebuild();
		CompletableFuture.runAsync(() -> {
			try {
				GlobalShortcutsStatus nextStatus = controller.apply(next);
				GlobalShortcutsConfig stored = controller.readConfig();
				Platform.runLater(() -> {
					busy = false;
					config = stored;
					status = nextStatus;
					rebuild();
				});
			} catch (RuntimeException error) {
				Platform.runLater(() -> {
					busy = false;
					rebuild();
					showError(message(error));
				});
			}
		});
	}

	private void reset() {
		apply(GlobalShortcuts.DEFAULT_GLOBAL_SHORTCUTS);
	}

	private void copyCommand(String command) {
		ClipboardContent content = new ClipboardContent();
		content.putString(command);
		Clipboard.getSystemClipboard().setContent(content);
		Platform.runLater(() -> Toast.show(this, "Command copied").variant(Toast.Variant.SUCCESS));
	}

	private void showError(String message) {
		if (getScene() != null) {
			Toast.show(this, message).variant(Toast.Variant.DANGER);
		}
	}

	/** JavaFX {@code KeyCode} to the DOM {@code KeyboardEvent.code} the service expects. */
	static String javafxCode(KeyCode code) {
		String name = code.getName();
		if (code.isLetterKey()) {
			return "Key" + name.toUpperCase(java.util.Locale.ROOT);
		}
		if (code.isDigitKey()) {
			return "Digit" + name;
		}
		return switch (code) {
			case SPACE -> "Space";
			case ENTER -> "Enter";
			case ESCAPE -> "Escape";
			case HOME -> "Home";
			case END -> "End";
			case PAGE_UP -> "PageUp";
			case PAGE_DOWN -> "PageDown";
			case INSERT -> "Insert";
			case DELETE -> "Delete";
			case UP -> "ArrowUp";
			case DOWN -> "ArrowDown";
			case LEFT -> "ArrowLeft";
			case RIGHT -> "ArrowRight";
			case SHIFT, CONTROL, ALT, META -> name;
			default -> name.startsWith("F") && name.length() <= 3 ? name : name;
		};
	}

	private static String message(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
	}
}
