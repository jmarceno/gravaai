package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.services.shortcuts.GlobalShortcutAction;
import dev.gravaai.services.shortcuts.GlobalShortcutBinding;
import dev.gravaai.services.shortcuts.GlobalShortcutKeyEvent;
import dev.gravaai.services.shortcuts.GlobalShortcuts;
import dev.gravaai.services.shortcuts.GlobalShortcutsConfig;
import dev.gravaai.services.shortcuts.GlobalShortcutsStatus;
import dev.gravaai.ui.i18n.UiI18n;
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

	private @Nullable GlobalShortcutsConfig config;
	private @Nullable GlobalShortcutsStatus status;
	private @Nullable GlobalShortcutAction capturing;
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
		Threads.startVirtual("gravaai-shortcuts-load", () -> {
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

	private GlobalShortcutsConfig loadedConfig() {
		return java.util.Objects.requireNonNull(config, "global-shortcuts config not loaded");
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

		GlobalShortcutsConfig current = loadedConfig();
		boolean portal = status != null && status.backend() == GlobalShortcutsStatus.Backend.PORTAL;
		Switch enabled = new Switch("", current.enabled());
		enabled.setAccessibleText(UiI18n.t("dashboard:settings.shortcuts.title"));
		enabled.setDisable(busy || !(cosmic || portal));
		enabled.onCheckedChange(checked ->
				apply(new GlobalShortcutsConfig(checked, current.bindings())));
		String description;
		if (cosmic) {
			description = UiI18n.t("dashboard:settings.shortcuts.cosmicDescription");
		} else if (portal) {
			description = UiI18n.t("dashboard:settings.shortcuts.portalDescription");
		} else {
			description = UiI18n.t("dashboard:settings.shortcuts.cliDescription");
		}
		rows.getChildren().add(new SettingsRow(UiI18n.t("dashboard:settings.shortcuts.title"),
				description, enabled));

		if (portal) {
			if (current.enabled()) {
				buildPortalTriggers(rows);
			}
			return;
		}
		if (!cosmic) {
			buildCliCommands(rows);
			return;
		}
		if (!current.enabled()) {
			return;
		}
		for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
			rows.getChildren().add(buildActionRow(action));
		}
		Label note = new Label(UiI18n.t("dashboard:settings.shortcuts.captureNote"));
		note.getStyleClass().add("global-shortcuts-settings__note");
		note.setWrapText(true);
		rows.getChildren().add(note);
		Button reset = new Button(UiI18n.t("dashboard:settings.shortcuts.resetToDefaults"),
				Button.Variant.GHOST, Button.Size.SM);
		reset.setGraphic(Icon.of(Icons.ArrowCounterClockwise, 14));
		reset.setGraphicTextGap(6);
		reset.setDisable(busy);
		reset.setOnAction(event -> reset());
		rows.getChildren().add(reset);
	}

	/** The keys the desktop bound: it owns them, so they are shown, not edited, here. */
	private void buildPortalTriggers(VBox host) {
		java.util.Map<GlobalShortcutAction, String> triggers =
				status == null ? java.util.Map.of() : status.triggers();
		for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
			Label trigger = new Label(triggers.getOrDefault(action,
					UiI18n.t("dashboard:settings.shortcuts.notSet")));
			trigger.getStyleClass().add("global-shortcuts-settings__command");
			host.getChildren().add(new SettingsRow(actionLabel(action), "", trigger));
		}
	}

	private void buildCliCommands(VBox host) {
		java.util.Map<GlobalShortcutAction, String> commands =
				status == null ? java.util.Map.of() : status.commands();
		for (GlobalShortcutAction action : GlobalShortcutAction.values()) {
			String command = commands.getOrDefault(action, "");
			Label code = new Label(command);
			code.getStyleClass().add("global-shortcuts-settings__command");
			code.setMaxWidth(Double.MAX_VALUE);
			Button copy = new Button(UiI18n.t("dashboard:settings.shortcuts.copy"),
					Button.Variant.SECONDARY, Button.Size.SM);
			copy.setOnAction(event -> copyCommand(command));
			host.getChildren().add(new SettingsRow(actionLabel(action), "", copy));
		}
	}

	/** Translation keys of the action row labels, literal so the key check sees them. */
	private static final java.util.Map<GlobalShortcutAction, String> ACTION_LABEL_KEYS = java.util.Map.of(
			GlobalShortcutAction.TOGGLE_RECORDING,
					"dashboard:settings.shortcuts.actionToggleRecording",
			GlobalShortcutAction.TOGGLE_PAUSE, "dashboard:settings.shortcuts.actionTogglePause",
			GlobalShortcutAction.CANCEL_RECORDING,
					"dashboard:settings.shortcuts.actionCancelRecording",
			GlobalShortcutAction.TOGGLE_MEETING,
					"dashboard:settings.shortcuts.actionToggleMeeting",
			GlobalShortcutAction.TOGGLE_DRAW, "dashboard:settings.shortcuts.actionToggleDraw");

	/** The translated row label of one action (the service keeps the English COSMIC label). */
	private static String actionLabel(GlobalShortcutAction action) {
		String key = ACTION_LABEL_KEYS.get(action);
		if (key == null) {
			throw new IllegalArgumentException("missing shortcut label key for " + action);
		}
		return UiI18n.t(key);
	}

	private Region buildActionRow(GlobalShortcutAction action) {
		String label = actionLabel(action);
		GlobalShortcutBinding binding = loadedConfig().bindings().get(action);
		String conflict = status == null ? null : status.conflicts().get(action);
		boolean isCapturing = capturing == action;

		String bindingLabel;
		if (isCapturing) {
			bindingLabel = UiI18n.t("dashboard:settings.shortcuts.pressAShortcut");
		} else if (binding == null) {
			bindingLabel = UiI18n.t("dashboard:settings.shortcuts.notSet");
		} else {
			bindingLabel = java.util.Objects.requireNonNull(
					GlobalShortcuts.formatGlobalBinding(binding),
					"could not format shortcut binding " + binding);
		}
		Button bindingButton = new Button(bindingLabel, Button.Variant.SECONDARY, Button.Size.SM);
		bindingButton.getStyleClass().add("global-shortcuts-settings__binding");
		bindingButton.setMinWidth(132);
		bindingButton.setDisable(busy);
		bindingButton.setOnAction(event -> startCapture(action));

		Button clear = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		clear.setGraphic(Icon.of(Icons.X, 14));
		clear.setAccessibleText(UiI18n.t("dashboard:settings.shortcuts.clearShortcut",
				java.util.Map.of("action", label)));
		clear.setDisable(busy || binding == null || isCapturing);
		clear.setOnAction(event -> {
			java.util.Map<GlobalShortcutAction, GlobalShortcutBinding> bindings =
					new java.util.LinkedHashMap<>(loadedConfig().bindings());
			bindings.put(action, null);
			apply(new GlobalShortcutsConfig(loadedConfig().enabled(), bindings));
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
			Platform.runLater(() -> Toast.show(this,
					UiI18n.t("dashboard:settings.shortcuts.invalidBinding"))
					.variant(Toast.Variant.DANGER));
			return;
		}
		GlobalShortcutAction action = capturing;
		stopCapture();
		java.util.Map<GlobalShortcutAction, GlobalShortcutBinding> bindings =
				new java.util.LinkedHashMap<>(loadedConfig().bindings());
		bindings.put(action, binding);
		apply(new GlobalShortcutsConfig(loadedConfig().enabled(), bindings));
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
		Threads.startVirtual("gravaai-shortcuts-apply", () -> {
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
		Platform.runLater(() -> Toast.show(this, UiI18n.t("dashboard:settings.shortcuts.commandCopied"))
				.variant(Toast.Variant.SUCCESS));
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
