package dev.gravaai.ui.launch;

import org.jspecify.annotations.Nullable;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.kit.Toggle;
import dev.gravaai.ui.kit.AudioLevelMeter;
import java.util.ArrayList;
import java.util.List;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * HUD menu window (plan §5.4, §7.1 P1.7), port of {@code src/components/launch/HudMenuWindow.tsx}.
 *
 * <p>On Linux the React HUD opens the mic/webcam/countdown menus in this dedicated full-display
 * transparent window instead of an anchored popover ({@code PopoverScaffold.isLinuxHudMenuPanelMode}):
 * a dimmed backdrop with a centred 520 px panel, closed by Escape, a backdrop click or the close
 * button. Mic rows show a live level meter (PulseAudio via {@link MicLevelMonitor}); the webcam
 * menu shows a live camera preview ({@link WebcamPreviewView}).
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class HudMenuWindow {

	/** The HUD menus (HudMenuWindow.tsx {@code HudMenuId}) plus the monitor list. */
	public enum Menu { SCREEN, MIC, WEBCAM, COUNTDOWN }

	private static final int[] COUNTDOWN_OPTIONS = {0, 3, 5, 10};

	private final HudRecordingPort port;
	private final List<MicLevelMonitor> monitors = new ArrayList<>();
	private final EventHandler<KeyEvent> escape = event -> {
		if (event.getCode() == KeyCode.ESCAPE) {
			close();
			event.consume();
		}
	};

	private @Nullable Stage stage;
	private @Nullable StackPane root;
	private @Nullable VBox panel;
	private @Nullable VBox content;
	private @Nullable ScrollPane scroll;
	private @Nullable Menu active;
	private @Nullable WebcamPreviewView menuPreview;

	public HudMenuWindow(HudRecordingPort port) {
		this.port = port;
	}

	/** True while a menu is shown. */
	public boolean isOpen() {
		return active != null;
	}

	/** The visible menu, or null. */
	public @Nullable Menu activeMenu() {
		return active;
	}

	/** Opens (or switches to) one menu. */
	public void open(Menu menu) {
		if (stage == null) {
			createStage();
		}
		active = menu;
		HudRecordingPort.State state = port.state();
		switch (menu) {
			case MIC -> buildMicMenu(state);
			case WEBCAM -> buildWebcamMenu(state);
			case SCREEN -> buildScreenMenu(state);
			case COUNTDOWN -> buildCountdownMenu(state);
		}
		applyDisplayBounds();
		fitPanelHeight();
		System.getLogger("gravaai.hud.menu").log(System.Logger.Level.DEBUG,
				"menu " + menu + " contentChildren=" + (content == null ? -1 : content.getChildren().size())
						+ " contentPref=" + (content == null ? -1 : content.prefHeight(480))
						+ " scrollViewport=" + (scroll == null ? -1 : scroll.getPrefViewportHeight())
						+ " panelPref=" + panel.prefHeight(520));
		stage.show();
		// Re-fit once the scene has a layout pulse: ScrollPane pref sizes are only stable then.
		javafx.application.Platform.runLater(this::fitPanelHeight);
		stage.toFront();
		Scene scene = root.getScene();
		if (scene != null) {
			scene.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
			scene.addEventFilter(KeyEvent.KEY_PRESSED, escape);
		}
	}

	/** Hides the menu, stopping the meters and the camera preview. */
	public void close() {
		stopLiveViews();
		if (stage != null) {
			Scene scene = root.getScene();
			if (scene != null) {
				scene.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
			}
			stage.hide();
		}
		active = null;
	}

	/** Releases the stage; call when the app shuts down. */
	public void dispose() {
		close();
		if (stage != null) {
			stage.close();
			stage = null;
		}
	}

	private void createStage() {
		stage = new Stage(StageStyle.TRANSPARENT);
		stage.setTitle("Gravaai HUD menu");
		stage.setAlwaysOnTop(true);
		stage.setResizable(false);
		root = new StackPane();
		root.getStyleClass().add("hud-menu-root");
		Region backdrop = new Region();
		backdrop.getStyleClass().add("hud-menu-backdrop");
		backdrop.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		backdrop.setOnMousePressed(event -> close());
		panel = new VBox();
		panel.getStyleClass().add("hud-menu-panel");
		panel.setAlignment(Pos.TOP_LEFT);
		// StackPane fills the stage; keep the panel at content height like the React max-h panel.
		panel.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
		root.getChildren().addAll(backdrop, panel);
		StackPane.setAlignment(panel, Pos.CENTER);
		Scene scene = new Scene(root);
		scene.setFill(Color.TRANSPARENT);
		HudOverlayWindow.installStyles(scene);
		stage.setScene(scene);
	}

	/** Sizes the scroll area to the menu content so the panel hugs it (React max-h panel). */
	private void fitPanelHeight() {
		if (content == null || scroll == null) {
			return;
		}
		content.applyCss();
		double contentHeight = Math.max(1, content.prefHeight(480));
		double limit = Math.min(HudOverlayWindow.hudScreen().getVisualBounds().getHeight() * 0.82, 720) - 60;
		scroll.setPrefHeight(Math.min(contentHeight + 16, limit));
		scroll.setMaxHeight(limit);
		panel.applyCss();
		System.getLogger("gravaai.hud").log(System.Logger.Level.DEBUG,
				"menu content height " + contentHeight + " viewport " + scroll.getPrefViewportHeight());
	}

	private void applyDisplayBounds() {
		Screen screen = HudOverlayWindow.hudScreen();
		var bounds = screen.getVisualBounds();
		stage.setX(bounds.getMinX());
		stage.setY(bounds.getMinY());
		stage.setWidth(bounds.getWidth());
		stage.setHeight(bounds.getHeight());
		// Content-height panel (React max-h panel): the scroll area caps its own height.
		panel.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
	}

	// ── menus ─────────────────────────────────────────────────────────────

	private void buildMicMenu(HudRecordingPort.State state) {
		startPanel("Microphone");
		content.getChildren().add(menuItem("System audio",
				state.systemAudioEnabled() ? Icons.SpeakerHighIcon : Icons.SpeakerXIcon,
				state.systemAudioEnabled(),
				state.systemAudioEnabled() ? "Disable system audio" : "Enable system audio",
				() -> port.setSystemAudioEnabled(!state.systemAudioEnabled())));
		if (state.microphoneEnabled()) {
			content.getChildren().add(menuItem("Turn Off Microphone", Icons.MicrophoneSlashIcon, false,
					"Turn off the microphone", () -> {
						port.setMicrophoneEnabled(false);
						close();
					}));
		} else {
			content.getChildren().add(note("Select a microphone to enable"));
		}
		List<HudRecordingPort.MicDevice> devices = port.microphoneDevices();
		for (HudRecordingPort.MicDevice device : devices) {
			boolean selected = state.microphoneEnabled()
					&& (device.id().equals(state.microphoneDeviceId()) || state.microphoneDeviceId() == null);
			AudioLevelMeter meter = new AudioLevelMeter();
			meter.getStyleClass().add("hud-menu-meter");
			HBox graphic = new HBox(12);
			graphic.setAlignment(Pos.CENTER_LEFT);
			Icon icon = Icon.of(selected ? Icons.MicrophoneIcon : Icons.MicrophoneSlashIcon, 16,
					selected ? "fill" : "regular");
			Label label = new Label(device.label());
			label.setMaxWidth(Double.MAX_VALUE);
			HBox.setHgrow(label, Priority.ALWAYS);
			graphic.getChildren().addAll(icon, label, meter);
			Toggle item = menuToggle(graphic, selected, device.label(), () -> {
				port.setMicrophoneEnabled(true);
				port.setMicrophoneDeviceId(device.id());
				close();
			});
			content.getChildren().add(item);
			monitors.add(MicLevelMonitor.start(device.id(), meter::setLevel));
		}
		if (devices.isEmpty()) {
			content.getChildren().add(emptyNote("No microphones found"));
		}
	}

	private void buildWebcamMenu(HudRecordingPort.State state) {
		startPanel("Webcam");
		if (state.webcamEnabled()) {
			content.getChildren().add(menuItem("Turn Off Webcam", Icons.VideoCameraSlashIcon, false,
					"Turn off the webcam overlay", () -> {
						port.setWebcamEnabled(false);
						close();
					}));
			content.getChildren().add(menuItem(
					state.floatingWebcamPreview() ? "Hide Floating Preview" : "Show Floating Preview",
					state.floatingWebcamPreview() ? Icons.EyeSlashIcon : Icons.EyeIcon,
					state.floatingWebcamPreview(),
					"Floating webcam preview during recording",
					() -> port.setFloatingWebcamPreview(!state.floatingWebcamPreview())));
			menuPreview = new WebcamPreviewView();
			StackPane frame = new StackPane(menuPreview);
			frame.getStyleClass().add("hud-menu-webcam-frame");
			frame.setPrefSize(420, 315);
			frame.setMaxSize(420, 315);
			content.getChildren().add(frame);
			List<HudRecordingPort.CameraDevice> cameras = port.cameraDevices();
			if (!cameras.isEmpty()) {
				menuPreview.start(selectCamera(state, cameras).path());
			}
		} else {
			content.getChildren().add(note("Select a webcam to enable"));
		}
		List<HudRecordingPort.CameraDevice> cameras = port.cameraDevices();
		for (HudRecordingPort.CameraDevice camera : cameras) {
			boolean selected = state.webcamEnabled() && cameraMatches(state, camera);
			content.getChildren().add(menuItem(camera.label(),
					selected ? Icons.VideoCameraIcon : Icons.VideoCameraSlashIcon, selected,
					camera.label(), () -> {
						port.setWebcamEnabled(true);
						port.setWebcamDeviceId(camera.path());
						close();
					}));
		}
		if (cameras.isEmpty()) {
			content.getChildren().add(emptyNote("No webcams found"));
		}
	}

	/**
	 * The monitor list. On Wayland the HUD cannot stay above other windows, so clicking the screen
	 * to record hid it behind them; choosing from a list keeps the HUD in front.
	 */
	private void buildScreenMenu(HudRecordingPort.State state) {
		startPanel("Screen to record");
		List<HudRecordingPort.ScreenDevice> screens = port.screenDevices();
		if (screens.isEmpty()) {
			Label empty = new Label("No screens found.");
			empty.getStyleClass().add("hud-menu-item-empty");
			content.getChildren().add(empty);
			return;
		}
		for (HudRecordingPort.ScreenDevice screen : screens) {
			boolean selected = screen.label().equals(state.sourceName());
			content.getChildren().add(menuItem(screen.label(), Icons.MonitorIcon, selected, screen.label(),
					() -> {
						port.selectScreen(screen.id());
						close();
					}));
		}
	}

	private void buildCountdownMenu(HudRecordingPort.State state) {
		startPanel("Countdown delay");
		for (int delay : COUNTDOWN_OPTIONS) {
			String label = delay == 0 ? "No delay" : delay + "s";
			content.getChildren().add(menuItem(label, Icons.TimerIcon, state.countdownDelay() == delay, label,
					() -> {
						port.setCountdownDelay(delay);
						close();
					}));
		}
	}

	private static HudRecordingPort.CameraDevice selectCamera(HudRecordingPort.State state,
			List<HudRecordingPort.CameraDevice> cameras) {
		for (HudRecordingPort.CameraDevice camera : cameras) {
			if (cameraMatches(state, camera)) {
				return camera;
			}
		}
		return cameras.get(0);
	}

	private static boolean cameraMatches(HudRecordingPort.State state, HudRecordingPort.CameraDevice camera) {
		String id = state.webcamDeviceId();
		return id != null && (id.equals(camera.path()) || id.equals(camera.label()));
	}

	private void startPanel(String title) {
		stopLiveViews();
		panel.getChildren().clear();
		Label titleLabel = new Label(title);
		titleLabel.getStyleClass().add("hud-menu-title");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button close = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		close.icon(Icon.of(Icons.XIcon, 20));
		close.setAccessibleText("Close");
		close.setOnAction(event -> close());
		HBox header = new HBox(12, titleLabel, spacer, close);
		header.getStyleClass().add("hud-menu-header");
		header.setAlignment(Pos.CENTER_LEFT);
		content = new VBox(2);
		scroll = new ScrollPane(content);
		scroll.getStyleClass().add("hud-menu-scroll");
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setMaxHeight(Math.min(HudOverlayWindow.hudScreen().getVisualBounds().getHeight() * 0.82, 720) - 60);
		panel.getChildren().addAll(header, scroll);
	}

	private Toggle menuItem(String label, String iconName, boolean selected, String tooltip, Runnable action) {
		Toggle item = new Toggle(label);
		item.getStyleClass().add("hud-menu-item");
		item.setGraphic(Icon.of(iconName, 16, selected ? "fill" : "regular"));
		return finishMenuItem(item, selected, tooltip, action);
	}

	private Toggle menuToggle(HBox graphic, boolean selected, String tooltip, Runnable action) {
		Toggle item = new Toggle();
		item.getStyleClass().add("hud-menu-item");
		item.setGraphic(graphic);
		item.setMaxWidth(Double.MAX_VALUE);
		return finishMenuItem(item, selected, tooltip, action);
	}

	private Toggle finishMenuItem(Toggle item, boolean selected, String tooltip, Runnable action) {
		item.setSelected(selected);
		item.setMaxWidth(Double.MAX_VALUE);
		item.setAlignment(Pos.CENTER_LEFT);
		item.setGraphicTextGap(12);
		item.setAccessibleText(tooltip);
		item.setOnAction(event -> action.run());
		return item;
	}

	private static Label note(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("hud-menu-item-empty");
		label.setWrapText(true);
		return label;
	}

	private static Label emptyNote(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("hud-menu-empty-note");
		label.setMaxWidth(Double.MAX_VALUE);
		label.setAlignment(Pos.CENTER);
		return label;
	}

	private void stopLiveViews() {
		for (MicLevelMonitor monitor : monitors) {
			monitor.close();
		}
		monitors.clear();
		if (menuPreview != null) {
			menuPreview.stop();
			menuPreview = null;
		}
	}

	/** The stage, for the app's window registry and tests. */
	public @Nullable Stage stage() {
		return stage;
	}
}
