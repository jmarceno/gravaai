package dev.gravaai.ui.dashboard;

import java.util.concurrent.CompletableFuture;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Switch;
import dev.gravaai.ui.kit.Toast;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/**
 * Settings -> AI control (port of {@code AgentSettings.tsx}).
 *
 * <p>Off by default. When on, Gravaai listens on the local agent socket for an outside MCP client
 * (Claude Code, ...). The page only shows the switch, the status line and the copyable MCP
 * configuration; prompt handling and models stay outside the app. The agent service is Phase 3, so
 * the page talks to {@link AgentSettingsController} (Lead decision).
 */
public final class AgentSettingsView extends VBox {

	private final AgentSettingsController controller;
	private final VBox rows = new VBox(24);
	private AgentSettingsController.State state;
	private boolean busy;

	public AgentSettingsView(AgentSettingsController controller) {
		this.controller = controller;
		getStyleClass().add("agent-settings");
		setSpacing(24);
		getChildren().add(rows);
		CompletableFuture.runAsync(() -> {
			try {
				AgentSettingsController.State loaded = controller.state();
				Platform.runLater(() -> {
					state = loaded;
					rebuild();
				});
			} catch (RuntimeException error) {
				Platform.runLater(() -> showError(message(error)));
			}
		});
	}

	private void rebuild() {
		rows.getChildren().clear();
		if (state == null) {
			return;
		}
		Switch enabled = new Switch("", state.enabled());
		enabled.setAccessibleText("AI control");
		enabled.setDisable(busy);
		enabled.onCheckedChange(checked -> update(() -> controller.setEnabled(checked)));
		rows.getChildren().add(new SettingsRow("AI control (MCP)",
				"Let an outside agent such as Claude Code edit this project from a prompt. Gravaai only "
						+ "exposes tools; there is no prompt box or model inside the app. Local socket only, "
						+ "protected by a token.",
				enabled));

		Button copyCommand = new Button("Copy Claude Code command", Button.Variant.SECONDARY, Button.Size.SM);
		copyCommand.setDisable(busy);
		copyCommand.setOnAction(event -> copy("Claude Code command", state.claudeCodeCommand()));
		Button copyJson = new Button("Copy JSON config", Button.Variant.SECONDARY, Button.Size.SM);
		copyJson.setDisable(busy);
		copyJson.setOnAction(event -> copy("MCP JSON config", state.jsonConfig()));
		Button regenerate = new Button("Regenerate token", Button.Variant.GHOST, Button.Size.SM);
		regenerate.setDisable(busy || !state.enabled());
		regenerate.setOnAction(event -> update(controller::regenerateToken));
		FlowPane buttons = new FlowPane(8, 8, copyCommand, copyJson, regenerate);
		buttons.setAlignment(Pos.CENTER_LEFT);
		rows.getChildren().add(new SettingsRow("Status", statusText(), buttons, true));

		if (state.jsonConfig() != null && !state.jsonConfig().isBlank()) {
			Label config = new Label(state.jsonConfig());
			config.getStyleClass().add("agent-settings__config");
			config.setWrapText(true);
			config.setMaxWidth(Double.MAX_VALUE);
			rows.getChildren().add(new SettingsRow("Config",
					"Paste this into your MCP client's configuration if you prefer JSON over the command above.",
					config, true));
		}
	}

	private String statusText() {
		if (!state.enabled()) {
			return "Off";
		}
		if (state.clients().isEmpty()) {
			return "Listening · no agent connected";
		}
		String names = state.clients().stream().map(AgentSettingsController.Client::client)
				.reduce((left, right) -> left + ", " + right).orElse("");
		return "Listening · connected: " + names;
	}

	private void update(java.util.function.Supplier<AgentSettingsController.State> action) {
		if (busy) {
			return;
		}
		busy = true;
		rebuild();
		CompletableFuture.runAsync(() -> {
			try {
				AgentSettingsController.State next = action.get();
				Platform.runLater(() -> {
					busy = false;
					state = next;
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

	private void copy(String label, String text) {
		if (text == null || text.isBlank()) {
			Toast.show(this, "Nothing to copy yet").variant(Toast.Variant.WARNING);
			return;
		}
		ClipboardContent content = new ClipboardContent();
		content.putString(text);
		Clipboard.getSystemClipboard().setContent(content);
		Toast.show(this, label + " copied").variant(Toast.Variant.SUCCESS);
	}

	private void showError(String message) {
		Toast.show(this, message).variant(Toast.Variant.DANGER);
	}

	private static String message(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
	}
}
