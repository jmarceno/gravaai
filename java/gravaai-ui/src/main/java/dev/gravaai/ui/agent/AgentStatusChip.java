package dev.gravaai.ui.agent;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Label;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * "AI editing" header chip (port of {@code AgentStatusChip.tsx}, plan §5.16/P3.2): shows that an
 * outside agent is connected, what it last did, and the two escape hatches (revert the last change,
 * turn AI control off).
 *
 * <p>The chip hides itself when AI control is off and there is nothing to revert, so the editor
 * header can mount it unconditionally. It follows the purple AI accent of the React UI
 * ({@code #8B5CF6}) and the ping animation for a live connection.
 */
public final class AgentStatusChip extends HBox {

	private static final Color AI_COLOUR = Color.web("#8B5CF6");
	private static final Duration PING_DURATION = Duration.millis(900);

	private final AgentUiService service;
	private final Label title = new Label("AI editing");
	private final Circle ping = new Circle(4, AI_COLOUR);
	private final Circle core = new Circle(4, AI_COLOUR);
	private final StackPane dot = new StackPane(ping, core);
	private final Button revert;
	private final Button disable;
	private final Tooltip tooltip = new Tooltip();
	private final AutoCloseable subscription;
	private Animation pingAnimation;
	private AgentUiService.Snapshot snapshot;

	public AgentStatusChip(AgentUiService service) {
		this.service = service;
		getStyleClass().add("agent-status-chip");
		setAlignment(Pos.CENTER_LEFT);
		setSpacing(8);
		setMaxHeight(36);

		ping.setOpacity(0.7);
		ping.setVisible(false);
		dot.setMinSize(8, 8);
		dot.setPrefSize(8, 8);
		dot.setMaxSize(8, 8);
		dot.setMouseTransparent(true);
		title.getStyleClass().add("agent-status-chip__label");

		HBox revertGraphic = new HBox(6, Icon.of(Icons.ArrowCounterClockwise, 14), new Label("Revert"));
		revertGraphic.setAlignment(Pos.CENTER);
		revert = new Button("", Button.Variant.GHOST, Button.Size.SM);
		revert.setGraphic(revertGraphic);
		revert.getStyleClass().add("agent-status-chip__revert");
		revert.setVisible(false);
		revert.setManaged(false);
		revert.setOnAction(event -> service.revertLastAiChange());

		disable = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		disable.icon(Icons.X);
		disable.getStyleClass().add("agent-status-chip__disable");
		disable.setTooltip(new Tooltip("Turn off AI control"));
		disable.setOnAction(event -> service.setEnabled(false));

		Tooltip.install(this, tooltip);
		getChildren().addAll(dot, title, revert, disable);
		subscription = service.subscribe(this::onSnapshot);
	}

	private void onSnapshot(AgentUiService.Snapshot next) {
		runOnFxThread(() -> apply(next));
	}

	private void apply(AgentUiService.Snapshot next) {
		this.snapshot = next;
		boolean connected = next != null && next.connected();
		boolean show = next != null && next.enabled() && (connected || next.lastSummary() != null);
		setVisible(show);
		setManaged(show);
		if (!show) {
			stopPing();
			return;
		}
		revert.setVisible(next.lastSummary() != null);
		revert.setManaged(next.lastSummary() != null);
		boolean connectedNow = connected;
		Tooltip.install(revert, new Tooltip("Revert: " + next.lastSummary()));
		tooltip.setText(connectedNow
				? "Agent connected: " + describeClients(next) + (next.lastSummary() == null ? ""
						: "\nLast change: " + next.lastSummary())
				: "AI control is on; no agent is connected right now");
		updatePing(connectedNow);
	}

	private static String describeClients(AgentUiService.Snapshot snapshot) {
		return snapshot.clients().stream().map(AgentUiService.Client::client)
				.reduce((left, right) -> left + ", " + right).orElse("agent");
	}

	private void updatePing(boolean connected) {
		if (!connected) {
			stopPing();
			disable.setDisable(false);
			return;
		}
		ping.setVisible(true);
		if (pingAnimation != null) {
			return;
		}
		ScaleTransition scale = new ScaleTransition(PING_DURATION, ping);
		scale.setFromX(1);
		scale.setFromY(1);
		scale.setToX(2.6);
		scale.setToY(2.6);
		FadeTransition fade = new FadeTransition(PING_DURATION, ping);
		fade.setFromValue(0.7);
		fade.setToValue(0);
		pingAnimation = new ParallelTransition(scale, fade);
		pingAnimation.setCycleCount(Animation.INDEFINITE);
		pingAnimation.play();
	}

	private void stopPing() {
		ping.setVisible(false);
		if (pingAnimation != null) {
			pingAnimation.stop();
			pingAnimation = null;
		}
	}

	private static void runOnFxThread(Runnable action) {
		if (Platform.isFxApplicationThread()) {
			action.run();
		} else {
			Platform.runLater(action);
		}
	}

	/** Current snapshot the chip rendered from (tests). */
	AgentUiService.Snapshot currentSnapshot() {
		return snapshot;
	}

	Button revertButton() {
		return revert;
	}

	Button disableButton() {
		return disable;
	}

	Label titleLabel() {
		return title;
	}

	/** Stops listening to the agent service. */
	public void dispose() {
		stopPing();
		try {
			subscription.close();
		} catch (Exception ignored) {
			// listener handle is best-effort
		}
	}
}
