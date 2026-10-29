package dev.gravaai.ui.launch;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.MarqueeText;
import dev.gravaai.ui.kit.Motion;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The launch/HUD bar (plan §5.12, §7.1 P1.7). Port of {@code LaunchWindow.tsx} +
 * {@code LaunchWindow.module.css}:
 *
 * <ul>
 *   <li>idle: meeting mode, source picker (MarqueeText), microphone, webcam, countdown, record,
 *       home, hide, close;
 *   <li>recording: home, blinking REC dot + timer, microphone (disabled), pause/resume, stop,
 *       hide, cancel;
 *   <li>finalizing: spinner + "Preparing recording" copy.
 * </ul>
 *
 * State transitions use the motion/react values from LaunchWindow.tsx (240 ms,
 * ease [0.22, 1, 0.36, 1]) via {@link Motion#EASE_MOTION_OUT}.
 */
public final class HudBar extends HBox {

	private static final String RECORD_DOT = "hud-rec-dot";
	private static final String STOP_SQUARE = "hud-stop-square";

	private final HudOverlayWindow window;
	private final HudRecordingPort port;
	private final StackPane stateViewport = new StackPane();
	private final RecordingTimer recordingTimer = new RecordingTimer();
	private final Label timerLabel = new Label("00:00");
	private final Label pausedLabel = new Label("PAUSED");
	private Timeline timerTick;
	private FadeTransition blink;
	private RotateTransition spinner;
	private Node currentState;
	private HudRecordingPort.State state = HudRecordingPort.State.idle();

	public HudBar(HudOverlayWindow window, HudRecordingPort port) {
		this.window = window;
		this.port = port;
		getStyleClass().addAll("hud-bar", "launch-theme");
		setSpacing(10);
		setAlignment(Pos.CENTER_LEFT);

		HBox dragHandle = new HBox(Icon.of(Icons.DotsThreeVerticalIcon, 18, "fill"));
		dragHandle.getStyleClass().add("hud-drag-handle");
		dragHandle.setAlignment(Pos.CENTER);
		dragHandle.setOnMousePressed(window::beginHudMove);
		dragHandle.setOnMouseDragged(window::continueHudMove);
		dragHandle.setOnMouseReleased(window::endHudMove);

		stateViewport.getStyleClass().add("hud-bar-state-viewport");
		stateViewport.setAlignment(Pos.CENTER_LEFT);
		stateViewport.setMinHeight(46);
		getChildren().addAll(dragHandle, stateViewport);
		render(HudRecordingPort.State.idle());
	}

	/** Applies a new HUD state, rebuilding the bar state row when something changed. */
	public void update(HudRecordingPort.State next) {
		if (next == null || next.equals(state)) {
			return;
		}
		HudRecordingPort.State previous = state;
		state = next;
		if (previous.recording() != next.recording()) {
			recordingTimer.setRecording(next.recording());
		}
		recordingTimer.setPaused(next.paused());
		render(next);
	}

	public HudRecordingPort.State state() {
		return state;
	}

	private void render(HudRecordingPort.State next) {
		stopAnimations();
		Node content = switch (next.phase()) {
			case FINALIZING -> finalizingControls();
			case RECORDING, PAUSED -> recordingControls(next);
			case IDLE -> idleControls(next);
		};
		stateViewport.getChildren().setAll(content);
		currentState = content;
		content.setOpacity(0);
		content.setTranslateY(10);
		content.setScaleX(0.985);
		content.setScaleY(0.985);
		FadeTransition fade = Motion.fadeIn(content, Motion.MENU_CARD_IN, Motion.EASE_MOTION_OUT);
		TranslateTransition slide = Motion.translateTo(content, 0, 0, Motion.MENU_CARD_IN, Motion.EASE_MOTION_OUT);
		ScaleTransition scale = Motion.scaleTo(content, 1, Motion.MENU_CARD_IN, Motion.EASE_MOTION_OUT);
		new ParallelTransition(fade, slide, scale).play();
		window.contentChanged();
	}

	private void stopAnimations() {
		if (timerTick != null) {
			timerTick.stop();
			timerTick = null;
		}
		if (blink != null) {
			blink.stop();
			blink = null;
		}
		if (spinner != null) {
			spinner.stop();
			spinner = null;
		}
	}

	// ── idle ──────────────────────────────────────────────────────────────

	private Node idleControls(HudRecordingPort.State next) {
		HBox row = new HBox(8);
		row.setAlignment(Pos.CENTER_LEFT);
		boolean disabledDuringRecording = next.recording() || next.finalizing();

		Button meeting = iconButton(Icons.UsersGroup, next.meetingMode() ? "fill" : "regular", 20,
				next.meetingMode() ? "Meeting mode on: transcribe and write notes after you stop"
						: "Meeting mode: transcribe and write notes after you stop",
				next.meetingMode() ? "hud-accent" : null, port::toggleMeetingMode);
		meeting.setDisable(disabledDuringRecording);
		row.getChildren().add(meeting);
		if (next.meetingMode()) {
			Button recordScreen = iconButton(Icons.MonitorIcon, next.recordScreen() ? "fill" : "regular", 20,
					next.recordScreen() ? "Recording the screen too (click for audio only)"
							: "Audio only, screen not recorded (click to record the screen too)",
					next.recordScreen() ? "hud-accent" : null,
					() -> port.setRecordScreen(!state.recordScreen()));
			recordScreen.setDisable(disabledDuringRecording);
			row.getChildren().add(recordScreen);
		}
		row.getChildren().add(separator());

		if (next.audioOnly()) {
			HBox note = new HBox(8);
			note.getStyleClass().add("hud-audio-only-note");
			note.setAlignment(Pos.CENTER_LEFT);
			note.getChildren().addAll(Icon.of(Icons.UsersGroup, 20), new Label("Audio only"));
			row.getChildren().add(note);
		} else {
			row.getChildren().add(sourceButton(next));
		}
		row.getChildren().add(separator());

		Button mic = iconButton(Icons.MicrophoneIcon, next.microphoneEnabled() ? "fill" : "regular", 20,
				next.microphoneEnabled() ? "Disable microphone" : "Enable microphone",
				next.microphoneEnabled() ? "hud-accent" : null,
				() -> window.openMenu(HudMenuWindow.Menu.MIC));
		mic.setDisable(next.recording() || next.finalizing());
		row.getChildren().add(mic);
		if (!next.audioOnly()) {
			Button webcam = iconButton(Icons.VideoCameraIcon, next.webcamEnabled() ? "fill" : "regular", 20,
					next.webcamEnabled() ? "Disable webcam overlay" : "Enable webcam overlay",
					next.webcamEnabled() ? "hud-accent" : null,
					() -> window.openMenu(HudMenuWindow.Menu.WEBCAM));
			webcam.setDisable(next.recording() || next.finalizing());
			row.getChildren().add(webcam);
		}
		Button countdown = iconButton(Icons.TimerIcon, "regular", 20, "Countdown delay",
				next.countdownDelay() > 0 ? "hud-accent" : null,
				() -> window.openMenu(HudMenuWindow.Menu.COUNTDOWN));
		countdown.setDisable(next.picking());
		row.getChildren().add(countdown);

		Button record = new Button("", Button.Variant.DESTRUCTIVE, Button.Size.ICON);
		record.setGraphic(region(RECORD_DOT));
		record.setOnAction(event -> {
			if (next.audioOnly() || next.hasSource()) {
				port.toggleRecording();
			} else {
				window.openMenu(HudMenuWindow.Menu.SCREEN);
			}
		});
		record.setDisable(next.countdownActive() || next.picking());
		tooltip(record, next.audioOnly() || next.hasSource() ? "Record"
				: "Pick a screen, then record");
		row.getChildren().add(record);
		row.getChildren().add(separator());
		row.getChildren().addAll(homeButton(), hideButton(), closeButton());
		return row;
	}

	private Button sourceButton(HudRecordingPort.State next) {
		Button source = new Button("", Button.Variant.GHOST, Button.Size.LG);
		source.getStyleClass().add("hud-source-button");
		HBox graphic = new HBox(8);
		graphic.setAlignment(Pos.CENTER_LEFT);
		String label = next.picking() ? "Click a screen…"
				: (next.hasSource() ? next.sourceName() : "Pick screen");
		MarqueeText marquee = new MarqueeText(label);
		marquee.setPrefWidth(120);
		marquee.setMaxWidth(120);
		graphic.getChildren().addAll(Icon.of(Icons.MonitorIcon, 20), marquee);
		source.setGraphic(graphic);
		source.setOnAction(event -> window.openMenu(HudMenuWindow.Menu.SCREEN));
		source.setDisable(next.finalizing() || next.picking());
		tooltip(source, next.picking()
				? "Click anywhere on the screen you want to record"
				: next.hasSource()
						? "Recording source: " + next.sourceName() + " (click to change)"
						: "Pick the screen to record");
		return source;
	}

	// ── recording ─────────────────────────────────────────────────────────

	private Node recordingControls(HudRecordingPort.State next) {
		HBox row = new HBox(8);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getChildren().add(iconButton(Icons.House, "fill", 16, "Home", null, port::openDashboard));

		HBox status = new HBox(12);
		status.setAlignment(Pos.CENTER_LEFT);
		status.setPadding(new javafx.geometry.Insets(0, 8, 0, 8));
		Region dot = region("hud-status-dot");
		if (next.paused()) {
			dot.getStyleClass().add("hud-status-dot--paused");
		} else {
			blink = new FadeTransition(Duration.millis(600), dot);
			blink.setFromValue(1);
			blink.setToValue(0.2);
			blink.setAutoReverse(true);
			blink.setCycleCount(Animation.INDEFINITE);
			blink.play();
		}
		timerLabel.getStyleClass().add("hud-timer");
		pausedLabel.getStyleClass().add("hud-paused-label");
		pausedLabel.setVisible(next.paused());
		pausedLabel.setManaged(next.paused());
		timerTick = new Timeline(new javafx.animation.KeyFrame(Duration.seconds(1),
				event -> timerLabel.setText(RecordingTimer.format(recordingTimer.elapsedSeconds()))));
		timerTick.setCycleCount(Animation.INDEFINITE);
		timerTick.play();
		timerLabel.setText(RecordingTimer.format(recordingTimer.elapsedSeconds()));
		status.getChildren().addAll(dot, timerLabel, pausedLabel);
		row.getChildren().add(status);

		Button micState = iconButton(next.microphoneEnabled() ? Icons.MicrophoneIcon : Icons.MicrophoneSlashIcon,
				next.microphoneEnabled() ? "fill" : "regular", 16,
				"Microphone cannot be toggled during recording", null, () -> {
				});
		micState.setDisable(true);
		row.getChildren().add(micState);
		row.getChildren().add(separator());

		Button pauseResume = iconButton(next.paused() ? Icons.PlayIcon : Icons.PauseIcon, "fill", 16,
				next.paused() ? "Resume" : "Pause", null,
				() -> {
					if (state.paused()) {
						port.resumeRecording();
					} else {
						port.pauseRecording();
					}
				});
		row.getChildren().add(pauseResume);

		Button stop = new Button("", Button.Variant.DESTRUCTIVE, Button.Size.ICON);
		stop.setGraphic(region(STOP_SQUARE));
		stop.setOnAction(event -> port.toggleRecording());
		tooltip(stop, "Stop");
		row.getChildren().add(stop);

		row.getChildren().add(iconButton(Icons.MinusIcon, "regular", 16, "Hide HUD", null, port::hideHud));
		row.getChildren().add(iconButton(Icons.XIcon, "regular", 16, "Cancel", null, port::cancelRecording));
		return row;
	}

	// ── finalizing ────────────────────────────────────────────────────────

	private Node finalizingControls() {
		Icon spinnerIcon = Icon.of(Icons.ArrowClockwiseIcon, 15);
		spinnerIcon.getStyleClass().add("hud-finalizing-spin");
		spinner = new RotateTransition(Duration.millis(900), spinnerIcon);
		spinner.setByAngle(360);
		spinner.setCycleCount(Animation.INDEFINITE);
		spinner.setInterpolator(javafx.animation.Interpolator.ofLinear(
				new javafx.geometry.Point2D(0, 0), new javafx.geometry.Point2D(1, 1)));
		spinner.play();
		Label title = new Label("Preparing recording");
		title.getStyleClass().add("hud-finalizing-title");
		Label subtitle = new Label("Opening the editor in a moment");
		subtitle.getStyleClass().add("hud-finalizing-subtitle");
		VBox copy = new VBox(2, title, subtitle);
		copy.getStyleClass().add("hud-finalizing-copy");
		HBox box = new HBox(11, spinnerIcon, copy);
		box.getStyleClass().add("hud-finalizing");
		box.setAlignment(Pos.CENTER_LEFT);
		return box;
	}

	// ── helpers ───────────────────────────────────────────────────────────

	private Button iconButton(String iconName, String weight, double iconSize, String tooltip,
			String accentClass, Runnable action) {
		Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		button.icon(Icon.of(iconName, iconSize, weight));
		button.setAccessibleText(tooltip);
		if (accentClass != null) {
			button.getStyleClass().add(accentClass);
		}
		button.setOnAction(event -> action.run());
		if (tooltip != null) {
			tooltip(button, tooltip);
		}
		return button;
	}

	private Button homeButton() {
		return iconButton(Icons.House, "fill", 20, "Home", null, port::openDashboard);
	}

	private Button hideButton() {
		return iconButton(Icons.MinusIcon, "regular", 20, "Hide HUD", null, port::hideHud);
	}

	private Button closeButton() {
		return iconButton(Icons.XIcon, "regular", 20, "Minimize to tray", null, port::hideHud);
	}

	/**
	 * Native tooltip: the HUD is an opaque window sized to the bar, so the kit tooltip (drawn
	 * inside the window) has no room above it; a native tooltip opens its own popup.
	 */
	static void tooltip(Node trigger, String label) {
		javafx.scene.control.Tooltip tooltip = new javafx.scene.control.Tooltip(label);
		tooltip.getStyleClass().add("hud-tooltip");
		tooltip.setShowDelay(Duration.millis(300));
		tooltip.setHideDelay(Duration.ZERO);
		if (trigger instanceof javafx.scene.control.Control control) {
			control.setTooltip(tooltip);
		} else {
			javafx.scene.control.Tooltip.install(trigger, tooltip);
		}
	}

	private static Region separator() {
		Region separator = new Region();
		separator.getStyleClass().add("hud-sep");
		return separator;
	}

	private static Region region(String styleClass) {
		Region region = new Region();
		region.getStyleClass().add(styleClass);
		return region;
	}

	/** Starts the drag from the bar handle; the window adapter decides native vs client drag. */
	public void beginMove(MouseEvent event) {
		window.beginHudMove(event);
	}

	/** Exposed for the window's size calculation. */
	public HBox stateRow() {
		return (HBox) currentState;
	}
}
