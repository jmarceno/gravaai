package dev.gravaai.ui.agent;

import org.jspecify.annotations.Nullable;

import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Switch;
import dev.gravaai.ui.kit.Toast;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Settings -> AI control (P3.2, plan §5.16): enable toggle, connection/status information, the
 * MCP command and JSON config with copy buttons, the control token (masked, reveal, copy,
 * regenerate) and the recent agent activity.
 *
 * <p>Faithful to {@code src/components/video-editor/dashboard/AgentSettings.tsx} where the React UI
 * has a matching row, plus the token/activity rows the Java app exposes. Everything goes through
 * {@link AgentUiService}; the page itself has no prompt box and no model — Gravaai only exposes MCP
 * tools over the local socket.
 */
public final class AgentSettingsView extends VBox implements AutoCloseable {

	private static final String STYLESHEET = "/ui/agent/agent.css";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private final AgentUiService service;
	private final AgentStatusChip chip;
	private final Switch enabledSwitch = new Switch("", false);
	private final Label statusText = new Label();
	private final Label socketValue = new Label();
	private final Label editorReadyValue = new Label();
	private final Label mcpCommandValue = new Label();
	private final Label claudeCommandValue = new Label();
	private final Label jsonValue = new Label();
	private final Label tokenValue = new Label();
	private final Label activityEmpty = new Label("No agent activity yet.");
	private final VBox activityRows = new VBox(0);
	private final Button revealButton;
	private final Button regenerateButton;
	private final Button copyTokenButton;
	private final AutoCloseable subscription;

	private AgentUiService.@Nullable Snapshot snapshot;
	private boolean busy;
	private boolean revealed;
	private boolean updating;

	public AgentSettingsView(AgentUiService service) {
		this.service = service;
		this.chip = new AgentStatusChip(service);
		getStyleClass().add("agent-settings-page");
		setSpacing(14);
		setFillWidth(true);

		Label title = new Label("AI control");
		title.getStyleClass().add("agent-settings__title");
		Label subtitle = new Label("Let an outside agent such as Claude Code edit this project from a "
				+ "prompt. Gravaai only exposes tools; there is no prompt box or model inside the app. "
				+ "Local socket only, protected by a token.");

		subtitle.getStyleClass().add("agent-settings__subtitle");
		subtitle.setWrapText(true);
		subtitle.setMaxWidth(Double.MAX_VALUE);
		VBox heading = new VBox(6, title, subtitle);
		heading.getStyleClass().add("agent-settings__heading");

		// ── enabled / connection ────────────────────────────────────────────
		enabledSwitch.setAccessibleText("AI control");
		enabledSwitch.onCheckedChange(checked -> {
			if (!updating && snapshot != null && checked != snapshot.enabled()) {
				service.setEnabled(checked);
			}
		});
		VBox enableSection = section(null, null, null);
		Label enableTitle = new Label("AI control (MCP)");
		enableTitle.getStyleClass().add("agent-settings__section-title");
		Label enableDescription = new Label("Off by default. When on, Gravaai listens on the local "
				+ "agent socket for an MCP client.");
		enableDescription.getStyleClass().add("agent-settings__description");
		enableDescription.setWrapText(true);
		VBox enableTexts = new VBox(4, enableTitle, enableDescription);
		HBox.setHgrow(enableTexts, Priority.ALWAYS);
		HBox enableRow = new HBox(16, enableTexts, enabledSwitch);
		enableRow.setAlignment(Pos.CENTER_LEFT);
		enableSection.getChildren().add(enableRow);

		statusText.getStyleClass().add("agent-settings__status-line");
		socketValue.getStyleClass().add("agent-settings__mono");
		editorReadyValue.getStyleClass().add("agent-settings__mono");
		HBox connectionDetails = new HBox(12, chip, spacer(), pill("Socket", socketValue),
				pill("Editor ready", editorReadyValue));
		connectionDetails.setAlignment(Pos.CENTER_LEFT);

		Button copyClaude = action("Copy Claude Code command", Icons.Copy,
				() -> copy("Claude Code command", snapshot == null ? null : snapshot.claudeCodeCommand()));
		Button copyJson = action("Copy JSON config", Icons.Copy,
				() -> copy("MCP JSON config", snapshot == null ? null : snapshot.jsonConfig()));
		regenerateButton = action("Regenerate token", Icons.ArrowCounterClockwise, () -> {
			if (busy) {
				return;
			}
			service.regenerateToken();
			toast("New token created; connected agents must reconnect", Toast.Variant.SUCCESS);
		});
		HBox statusButtons = new HBox(8, copyClaude, copyJson, regenerateButton);
		statusButtons.setAlignment(Pos.CENTER_LEFT);

		VBox statusSection = section("Status", null, null);
		statusSection.getChildren().addAll(statusText, connectionDetails, statusButtons);

		// ── MCP command + config ─────────────────────────────────────────────
		mcpCommandValue.getStyleClass().add("agent-settings__code");
		mcpCommandValue.setWrapText(true);
		mcpCommandValue.setMaxWidth(Double.MAX_VALUE);
		Button copyCommand = action("Copy command", Icons.Copy,
				() -> copy("MCP command", snapshot == null ? null : snapshot.mcpCommand()));
		HBox commandRow = new HBox(8, mcpCommandValue, copyCommand);
		HBox.setHgrow(mcpCommandValue, Priority.ALWAYS);
		commandRow.setAlignment(Pos.CENTER_LEFT);
		VBox commandSection = section("MCP command",
				"The command your MCP client starts. It changes from a development build to the "
						+ "packaged app image path.",
				commandRow);

		claudeCommandValue.getStyleClass().add("agent-settings__code");
		claudeCommandValue.setWrapText(true);
		claudeCommandValue.setMaxWidth(Double.MAX_VALUE);
		VBox claudeSection = section("Claude Code command",
				"One-line setup for Claude Code.", claudeCommandValue);

		jsonValue.getStyleClass().add("agent-settings__code");
		jsonValue.setWrapText(true);
		jsonValue.setMaxWidth(Double.MAX_VALUE);
		VBox jsonSection = section("Config",
				"Paste this into your MCP client's configuration if you prefer JSON over the command "
						+ "above.",
				jsonValue);

		// ── token ────────────────────────────────────────────────────────────
		tokenValue.getStyleClass().add("agent-settings__mono");
		revealButton = iconButton(Icons.Eye, "Reveal token", () -> {
			revealed = !revealed;
			updateToken();
		});
		copyTokenButton = iconButton(Icons.Copy, "Copy token", () -> copy("Control token",
				snapshot == null ? null : snapshot.token()));
		HBox tokenRow = new HBox(8, tokenValue, revealButton, copyTokenButton, regenerateButton == null
				? new HBox() : new HBox());
		tokenRow.setAlignment(Pos.CENTER_LEFT);
		// The token row reuses its own regenerate button so the switch stays in one place.
		Button tokenRegenerate = action("Regenerate", Icons.ArrowCounterClockwise, () -> {
			if (busy) {
				return;
			}
			service.regenerateToken();
			toast("New token created; connected agents must reconnect", Toast.Variant.SUCCESS);
		});
		tokenRow.getChildren().set(tokenRow.getChildren().size() - 1, tokenRegenerate);
		VBox tokenSection = section("Control token",
				"Every agent connection must present this token before it can call tools.",
				tokenRow);

		// ── recent activity ──────────────────────────────────────────────────
		activityEmpty.getStyleClass().add("agent-settings__description");
		activityRows.getStyleClass().add("agent-settings__activity");
		VBox activitySection = section("Recent activity",
				"Tool calls and edits from connected agents.", null);
		activitySection.getChildren().addAll(activityRows, activityEmpty);

		getChildren().addAll(heading, enableSection, statusSection, commandSection, claudeSection,
				jsonSection, tokenSection, activitySection);
		sceneProperty().addListener((observable, oldScene, newScene) -> installStyles(newScene));
		subscription = service.subscribe(this::onSnapshot);
	}

	/** Adds the agent stylesheet on top of the kit theme (scene may be null before mount). */
	public static void installStyles(Scene scene) {
		if (scene == null) {
			return;
		}
		URL resource = AgentSettingsView.class.getResource(STYLESHEET);
		if (resource == null) {
			return;
		}
		String url = resource.toExternalForm();
		if (!scene.getStylesheets().contains(url)) {
			scene.getStylesheets().add(url);
		}
	}

	private static VBox section(@Nullable String title, @Nullable String description, @Nullable Node control) {
		VBox section = new VBox(10);
		section.getStyleClass().add("agent-settings__section");
		if (title != null) {
			Label heading = new Label(title);
			heading.getStyleClass().add("agent-settings__section-title");
			section.getChildren().add(heading);
		}
		if (description != null && !description.isBlank()) {
			Label detail = new Label(description);
			detail.getStyleClass().add("agent-settings__description");
			detail.setWrapText(true);
			section.getChildren().add(detail);
		}
		if (control != null) {
			section.getChildren().add(control);
		}
		return section;
	}

	private static HBox pill(String label, Label value) {
		Label heading = new Label(label);
		heading.getStyleClass().add("agent-settings__pill-label");
		HBox pill = new HBox(6, heading, value);
		pill.getStyleClass().add("agent-settings__pill");
		pill.setAlignment(Pos.CENTER_LEFT);
		return pill;
	}

	private static Node spacer() {
		HBox spacer = new HBox();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		return spacer;
	}

	private static Button action(String text, String icon, Runnable action) {
		Button button = new Button("", Button.Variant.SECONDARY, Button.Size.SM);
		HBox graphic = new HBox(6, Icon.of(icon, 14), new Label(text));
		graphic.setAlignment(Pos.CENTER);
		button.setGraphic(graphic);
		button.setOnAction(event -> action.run());
		return button;
	}

	private static Button iconButton(String icon, String tooltip, Runnable action) {
		Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		button.icon(icon);
		button.setTooltip(new Tooltip(tooltip));
		button.setOnAction(event -> action.run());
		return button;
	}

	private void onSnapshot(AgentUiService.Snapshot next) {
		if (Platform.isFxApplicationThread()) {
			apply(next);
		} else {
			Platform.runLater(() -> apply(next));
		}
	}

	private void apply(AgentUiService.Snapshot next) {
		if (next == null) {
			return;
		}
		this.snapshot = next;
		updating = true;
		try {
			enabledSwitch.setChecked(next.enabled());
			enabledSwitch.setDisable(busy);
			statusText.setText(statusLine(next));
			socketValue.setText(next.socketPath() == null ? "—" : next.socketPath());
			editorReadyValue.setText(next.editorReady() ? "Ready" : "Not ready");
			mcpCommandValue.setText(next.mcpCommand() == null ? "—" : next.mcpCommand());
			claudeCommandValue.setText(next.claudeCodeCommand() == null ? "—" : next.claudeCodeCommand());
			jsonValue.setText(next.jsonConfig() == null ? "—" : next.jsonConfig());
			regenerateButton.setDisable(busy || !next.enabled());
			copyTokenButton.setDisable(next.token() == null);
			updateToken();
			updateActivity(next.recentActivity());
		} finally {
			updating = false;
		}
	}

	private void updateToken() {
		if (snapshot == null) {
			return;
		}
		tokenValue.setText(revealed ? snapshot.token() : mask(snapshot.token()));
		revealButton.setGraphic(Icon.of(revealed ? Icons.EyeSlash : Icons.Eye, 16));
	}

	static String mask(String token) {
		if (token == null || token.isBlank()) {
			return "—";
		}
		if (token.length() <= 8) {
			return "•".repeat(token.length());
		}
		return token.substring(0, 3) + "•".repeat(Math.max(6, token.length() - 7))
				+ token.substring(token.length() - 4);
	}

	private static String statusLine(AgentUiService.Snapshot snapshot) {
		return switch (snapshot.status()) {
			case OFF -> "Off";
			case UNAVAILABLE -> "Enabled · the agent socket is not available";
			case LISTENING -> "Listening · no agent connected";
			case CONNECTED -> "Listening · connected: " + snapshot.clients().stream()
					.map(AgentUiService.Client::client)
					.reduce((left, right) -> left + ", " + right).orElse("agent");
		};
	}

	private void updateActivity(List<AgentUiService.ActivityEntry> activity) {
		activityRows.getChildren().clear();
		if (activity.isEmpty()) {
			activityEmpty.setVisible(true);
			activityEmpty.setManaged(true);
			return;
		}
		activityEmpty.setVisible(false);
		activityEmpty.setManaged(false);
		for (AgentUiService.ActivityEntry entry : activity) {
			Label time = new Label(TIME.format(Instant.ofEpochMilli(entry.atMillis())
					.atZone(ZoneId.systemDefault())));
			time.getStyleClass().add("agent-settings__activity-time");
			Label text = new Label(entry.label());
			text.getStyleClass().add("agent-settings__activity-label");
			text.setWrapText(true);
			HBox row = new HBox(10, time, text);
			if (entry.failed()) {
				row.getChildren().add(Icon.of(Icons.WarningCircleIcon, 14));
			}
			row.getStyleClass().add("agent-settings__activity-row");
			row.setAlignment(Pos.CENTER_LEFT);
			activityRows.getChildren().add(row);
		}
	}

	private void copy(String label, @Nullable String text) {
		if (text == null || text.isBlank()) {
			toast("Nothing to copy yet", Toast.Variant.WARNING);
			return;
		}
		try {
			ClipboardContent content = new ClipboardContent();
			content.putString(text);
			Clipboard.getSystemClipboard().setContent(content);
			toast(label + " copied", Toast.Variant.SUCCESS);
		} catch (RuntimeException error) {
			toast("Could not copy to the clipboard", Toast.Variant.DANGER);
		}
	}

	private void toast(String message, Toast.Variant variant) {
		try {
			Toast.show(this, message).variant(variant);
		} catch (RuntimeException ignored) {
			// The page may not be mounted yet (tests/snapshot construction).
		}
	}

	/** The last snapshot the page rendered from (tests). */
	AgentUiService.@Nullable Snapshot currentSnapshot() {
		return snapshot;
	}

	Switch enabledSwitch() {
		return enabledSwitch;
	}

	Label tokenLabel() {
		return tokenValue;
	}

	Button revealButton() {
		return revealButton;
	}

	Button regenerateButton() {
		return regenerateButton;
	}

	Label statusLabel() {
		return statusText;
	}

	VBox activityRowsBox() {
		return activityRows;
	}

	Label mcpCommandValue() {
		return mcpCommandValue;
	}

	AgentStatusChip chip() {
		return chip;
	}

	@Override
	public void close() {
		chip.dispose();
		try {
			subscription.close();
		} catch (Exception ignored) {
			// listener handle is best-effort
		}
	}
}
