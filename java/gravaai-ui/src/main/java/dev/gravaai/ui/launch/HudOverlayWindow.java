package dev.gravaai.ui.launch;

import dev.gravaai.services.shortcuts.GlobalShortcutAction;
import dev.gravaai.services.shortcuts.GlobalShortcutHud;
import dev.gravaai.ui.AppFonts;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.windows.X11Windows;
import java.util.List;
import java.util.Objects;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * The persistent HUD overlay window (plan §5.4, §5.6.8, §7.1 P1.7).
 *
 * <p>Opaque frameless floating window showing the launch bar at the bottom centre of the primary
 * display, plus the floating recording webcam preview. The window is sized exactly to its content;
 * a drag moves it until it is shown again. On Wayland it cannot stay above other windows, so the
 * screen to record is chosen from a list ({@link HudMenuWindow.Menu#SCREEN}), not by clicking it. The drag handle hands
 * the move to the window manager through {@link X11Windows#beginMove} (COSMIC reverts client-side
 * moves), falling back to a client-side move when X11 is unavailable.
 *
 * <p><b>Startup budget:</b> {@link #show()} only builds the scene shell and shows the
 * stage (the startup gate measures "window shown"); the bar, the state subscription and the
 * optional webcam preview are built on the next pulse in {@link #finishShow}. The icon SVGs are
 * pre-parsed on a virtual thread from the constructor, the webcam preview is created lazily and
 * the HUD menu window only when a menu is first opened.
 *
 * <h2>AppContext construction contract</h2>
 * <pre>{@code
 * HudOverlayWindow hud = new HudOverlayWindow(hudRecordingPort);
 * hud.show();                                  // calls port.onHudReady() when subscribed
 * dispatcher.setHudProvider(() -> hud);        // GlobalShortcutDispatcher
 * // RecordingService.HudHooks:
 * //   hideForCapture()      -> hud.hideForCapture()
 * //   restoreAfterCapture() -> hud.restoreAfterCapture()
 * // shutdown: hud.dispose()
 * }</pre>
 */
public final class HudOverlayWindow implements GlobalShortcutHud {

	private static final System.Logger LOG = System.getLogger("gravaai.hud");

	private static final String LAUNCH_STYLESHEET = "/launch/launch.css";
		/** The HUD hint bubble: instruction/feedback above the bar, auto-hides (toast.info parity). */
	private static final Duration HINT_TIMEOUT = Duration.seconds(7);
	private static final double HINT_GAP = 8;
	/** Gap between the HUD and the bottom of the primary monitor. */
	private static final double SCREEN_GAP = 12;
	private static final double PREVIEW_GAP = 8;

	/** Icons the bar can show; pre-parsed off the FX thread so bar construction has no XML cost. */
	private static final String[] PREWARM_ICONS = {
			Icons.DotsThreeVerticalIcon, Icons.UsersGroup, Icons.MonitorIcon, Icons.MicrophoneIcon,
			Icons.MicrophoneSlashIcon, Icons.VideoCameraIcon, Icons.VideoCameraSlashIcon,
			Icons.TimerIcon, Icons.House, Icons.MinusIcon, Icons.XIcon, Icons.PlayIcon,
			Icons.PauseIcon, Icons.ArrowClockwiseIcon, Icons.SpeakerHighIcon, Icons.SpeakerXIcon,
			Icons.EyeIcon, Icons.EyeSlashIcon, Icons.TimerIcon};

	private final HudRecordingPort port;
	private final Stage stage = new Stage(StageStyle.UNDECORATED);
	private final Pane root = new HudRoot();
	private final Label hintLabel = new Label();
	private final long createdAtNanos = System.nanoTime();

	private HudBar bar;
	private HudWebcamPreview webcamPreview;
	private HudMenuWindow menuWindow;
	private AutoCloseable subscription;
	private PauseTransition hintTimer;
	/** A content change arrived while the stage was hidden; re-fit it when shown. */
	private boolean resizePending;
	private HudRecordingPort.State lastState;
	private volatile boolean ready;
	private volatile boolean alive;
	private volatile boolean shown;
	private boolean clientDragging;
	private boolean nativeDragging;
	private double dragOffsetX;
	private double dragOffsetY;
	/** The user dragged the HUD away from its bottom-centre spot; kept until it is shown again. */
	private boolean userMoved;
	private String previewDevice;

	public HudOverlayWindow(HudRecordingPort port) {
		this.port = Objects.requireNonNull(port, "port");
		// Everything that can be prepared before the startup gate calls show() lives here: the
		// scene shell is built in the constructor (AppContext spends ~100 ms creating
		// the recording services between construction and show) and the icon SVGs are parsed on a
		// background thread. show() then only maps the window.
		AppFonts.load();
		buildStageShell();
		// Overlaps the ~100 ms AppContext needs before show(); the parse is FX-free.
		Thread.ofVirtual().name("hud-icon-prewarm").start(HudOverlayWindow::prewarmIcons);
	}

	private static void prewarmIcons() {
		try {
			for (String name : PREWARM_ICONS) {
				Icon.prewarm(name, "regular");
				Icon.prewarm(name, "fill");
			}
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.DEBUG, "icon prewarm failed: " + error);
		}
	}

	/**
	 * Shows the HUD. Only the stage shell is created here so the startup gate sees the
	 * window immediately; {@link #finishShow} fills the bar on the next pulse.
	 */
	public void show() {
		if (shown) {
			// The HUD hides its stage during capture (hideForCapture) while staying "shown".
			// A tray/shortcut "Show Overlay" must re-map it instead of no-op-ing.
			if (!stage.isShowing()) {
				userMoved = false;
				stage.show();
				stage.toFront();
				stage.requestFocus();
			}
			return;
		}
		long start = System.nanoTime();
		// The window is mapped with the scene shell only; the stylesheets, the bar and
		// the state subscription are filled in on the next pulse (startup budget, plan §5.1).
		stage.show();
		long stageShown = System.nanoTime();
		shown = true;
		alive = true;
		Platform.runLater(() -> finishShow(start, stageShown));
	}

	private void finishShow(long start, long stageShown) {
		if (!shown) {
			return;
		}
		long buildStart = System.nanoTime();
		installStyles(stage.getScene());
		bar = new HudBar(this, port);
		bar.setManaged(false);
		root.getChildren().add(bar);
		subscription = port.onStateChanged(this::applyState);
		applyState(port.state());
		stage.toFront();
		long built = System.nanoTime();
		// Window-manager extras must not delay the visible window.
		Platform.runLater(() -> {
			try {
				X11Windows.keepAbove(stage);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.DEBUG, "keepAbove failed: " + error);
			}
		});
		ready = true;
		port.onHudReady();
		if (Boolean.getBoolean("gravaai.hud.profile")) {
			LOG.log(System.Logger.Level.INFO,
					"hud.show stageShow=" + ms(stageShown - start) + "ms deferredBar="
							+ ms(built - buildStart) + "ms totalSinceShow=" + ms(built - start) + "ms");
		}
	}

	private static long ms(long nanos) {
		return Math.round(nanos / 1_000_000.0);
	}

	/** Closes the HUD and the menu window (app shutdown). */
	public void dispose() {
		if (subscription != null) {
			try {
				subscription.close();
			} catch (Exception error) {
				LOG.log(System.Logger.Level.DEBUG, "closing the HUD state subscription failed: " + error);
			}
			subscription = null;
		}
		if (menuWindow != null) {
			menuWindow.dispose();
			menuWindow = null;
		}
		if (webcamPreview != null) {
			webcamPreview.stop();
			webcamPreview = null;
		}
		ready = false;
		alive = false;
		shown = false;
		stage.hide();
	}

	// ── GlobalShortcutHud ─────────────────────────────────────────────────

	@Override
	public boolean isAlive() {
		return alive;
	}

	@Override
	public boolean isReady() {
		return ready;
	}

	@Override
	public void deliver(GlobalShortcutAction action) {
		HudShortcuts.dispatch(action, port.state(), new HudShortcuts.Actions() {
			@Override
			public void toggleRecording() {
				port.toggleRecording();
			}

			@Override
			public void pauseRecording() {
				port.pauseRecording();
			}

			@Override
			public void resumeRecording() {
				port.resumeRecording();
			}

			@Override
			public void cancelRecording() {
				port.cancelRecording();
			}

			@Override
			public void requestSourcePick() {
				openMenu(HudMenuWindow.Menu.SCREEN);
			}

			@Override
			public void toggleMeetingMode() {
				port.toggleMeetingMode();
			}
		});
	}

	// ── capture protection (RecordingService.HudHooks) ────────────────────

	/** Hides the HUD before the capture helper grabs the screen, so it is not recorded. */
	public void hideForCapture() {
		if (shown) {
			stage.hide();
		}
	}

	/** Restores the HUD after a failed recording start. */
	public void restoreAfterCapture() {
		if (shown) {
			stage.show();
		}
	}

	// ── scene ─────────────────────────────────────────────────────────────

	private void buildStageShell() {
		root.getStyleClass().add("hud-root");
		hintLabel.getStyleClass().add("hud-hint");
		hintLabel.setManaged(false);
		hintLabel.setVisible(false);
		hintLabel.setWrapText(true);
		hintLabel.setMaxWidth(520);
		root.getChildren().add(hintLabel);
		// HudTooltip (and any other kit overlay) needs a KitRoot above the bar; without it every
		// tooltip silently failed to show.
		KitRoot kitRoot = new KitRoot(root);
		kitRoot.getStyleClass().add("hud-kit-root");
		Scene scene = new Scene(kitRoot);
		scene.setFill(Color.web("#1b1e25"));
		installStyles(scene);
		stage.setTitle("Gravaai HUD");
		stage.setScene(scene);
		stage.setAlwaysOnTop(true);
		stage.setResizable(false);
		stage.showingProperty().addListener((observable, wasShowing, showing) -> {
			if (showing && resizePending) {
				resizeStage();
			}
		});
		stage.setOnCloseRequest(event -> {
			ready = false;
			alive = false;
			shown = false;
		});
	}

	/** Adds the kit stylesheet and the launch/HUD stylesheet to a scene. */
	static void installStyles(Scene scene) {
		Theme.install(scene);
		var url = HudOverlayWindow.class.getResource(LAUNCH_STYLESHEET);
		if (url == null) {
			LOG.log(System.Logger.Level.WARNING, "missing " + LAUNCH_STYLESHEET);
			return;
		}
		String stylesheet = url.toExternalForm();
		if (!scene.getStylesheets().contains(stylesheet)) {
			scene.getStylesheets().add(stylesheet);
		}
	}

	private void applyState(HudRecordingPort.State state) {
		if (state == null || bar == null) {
			return;
		}
		HudRecordingPort.State previous = lastState;
		lastState = state;
		bar.update(state);
		updateHint(previous, state);
		updateWebcamPreview(state);
		resizeStage();
	}

	/**
	 * HUD hint bubble (the React {@code toast.info("Click anywhere on the screen you want to
	 * record.")} the port never surfaced): shown while the capture pick is pending and after a
	 * pick that ended without a source, so "Pick screen" is never a silent no-op.
	 */
	private void updateHint(HudRecordingPort.State previous, HudRecordingPort.State state) {
		boolean wasPicking = previous != null && previous.picking();
		if (state.picking() && !wasPicking) {
			showHint("Click anywhere on the screen you want to record.", false);
		} else if (!state.picking() && wasPicking) {
			if (!state.hasSource() && !state.recording() && !state.countdownActive()) {
				showHint("No screen was selected. Pick screen to try again.", true);
			} else {
				hideHint();
			}
		}
	}

	/** Shows the instruction/feedback bubble above the bar (FX thread). */
	public void showHint(String message, boolean error) {
		if (message == null || message.isBlank()) {
			return;
		}
		hintLabel.setText(message);
		hintLabel.getStyleClass().remove("hud-hint--error");
		if (error) {
			hintLabel.getStyleClass().add("hud-hint--error");
		}
		hintLabel.setVisible(true);
		hintLabel.setManaged(true);
		hintLabel.setOpacity(0);
		FadeTransition fade = new FadeTransition(Duration.millis(180), hintLabel);
		fade.setFromValue(0);
		fade.setToValue(1);
		fade.play();
		if (hintTimer == null) {
			hintTimer = new PauseTransition(HINT_TIMEOUT);
			hintTimer.setOnFinished(event -> hideHint());
		}
		hintTimer.playFromStart();
		resizeStage();
	}

	/** Hides the hint bubble (FX thread). */
	public void hideHint() {
		if (hintTimer != null) {
			hintTimer.stop();
		}
		if (!hintLabel.isVisible()) {
			return;
		}
		hintLabel.setVisible(false);
		hintLabel.setManaged(false);
		resizeStage();
	}

	/** The hint label (tests/screenshots). */
	public Label hintLabel() {
		return hintLabel;
	}

	/** Called by the bar when its content changed; re-fits the stage around it. */
	void contentChanged() {
		if (!shown) {
			return;
		}
		resizeStage();
	}

	private void resizeStage() {
		if (bar == null) {
			return;
		}
		if (!stage.isShowing()) {
			// Hidden for capture: applyCss on a hidden scene cannot resolve the .root tokens
			// (-g-fg, -g-muted, ...) and logs a burst of lookup warnings. Fit once shown again.
			resizePending = true;
			return;
		}
		resizePending = false;
		root.applyCss();
		double barWidth = Math.max(1, bar.prefWidth(-1));
		double barHeight = Math.max(1, bar.prefHeight(barWidth));
		double hintWidth = 0;
		double hintHeight = 0;
		if (hintLabel.isVisible()) {
			hintWidth = Math.min(520, Math.max(1, hintLabel.prefWidth(-1)));
			hintHeight = Math.max(1, hintLabel.prefHeight(hintWidth)) + HINT_GAP;
		}
		// Opaque window sized exactly to its content: [webcam preview] [hint] bar, top to bottom.
		double width = Math.max(barWidth, hintWidth);
		double height = barHeight + hintHeight;
		boolean previewVisible = webcamPreview != null && webcamPreview.isVisible();
		if (previewVisible) {
			width = Math.max(width, webcamPreview.frameWidth());
			height += webcamPreview.frameHeight() + PREVIEW_GAP;
		}
		Rectangle2D bounds = hudScreen().getVisualBounds();
		width = Math.min(width, bounds.getWidth());
		height = Math.min(height, bounds.getHeight());
		double oldBottom = stage.getY() + stage.getHeight();
		stage.setWidth(width);
		stage.setHeight(height);
		if (userMoved) {
			// Keep the dragged spot; grow upwards so the bar stays where the user put it.
			stage.setY(oldBottom - height);
		} else {
			stage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2);
			stage.setY(bounds.getMaxY() - height - SCREEN_GAP);
		}
		root.layout();
	}

	private void updateWebcamPreview(HudRecordingPort.State state) {
		boolean visible = state.recording() && state.webcamEnabled() && state.floatingWebcamPreview();
		if (visible) {
			HudWebcamPreview preview = webcamPreview();
			String device = cameraPath(state);
			if (!preview.isVisible() || !Objects.equals(previewDevice, device)) {
				preview.start(device);
				previewDevice = device;
			}
			preview.setVisible(true);
		} else if (webcamPreview != null && webcamPreview.isVisible()) {
			webcamPreview.stop();
			webcamPreview.setVisible(false);
			previewDevice = null;
		}
	}

	/** Lazy floating preview: only built the first time it is actually shown. */
	private HudWebcamPreview webcamPreview() {
		if (webcamPreview == null) {
			webcamPreview = new HudWebcamPreview();
			webcamPreview.setManaged(false);
			webcamPreview.setVisible(false);
			root.getChildren().add(webcamPreview);
		}
		return webcamPreview;
	}

	private String cameraPath(HudRecordingPort.State state) {
		List<HudRecordingPort.CameraDevice> cameras = port.cameraDevices();
		if (cameras.isEmpty()) {
			return null;
		}
		String id = state.webcamDeviceId();
		for (HudRecordingPort.CameraDevice camera : cameras) {
			if (id != null && (id.equals(camera.path()) || id.equals(camera.label()))) {
				return camera.path();
			}
		}
		return cameras.get(0).path();
	}

	// ── menu + drag (used by HudBar) ──────────────────────────────────────

	/** Opens one of the HUD menus in the dedicated menu window (Linux HUD menu mode). */
	void openMenu(HudMenuWindow.Menu menu) {
		if (menuWindow == null) {
			menuWindow = new HudMenuWindow(port);
		}
		menuWindow.open(menu);
	}

	/** Starts a HUD drag: native window-manager move on X11, client move otherwise. */
	void beginHudMove(MouseEvent event) {
		userMoved = true;
		if (X11Windows.isAvailable()) {
			nativeDragging = true;
			X11Windows.beginMove(stage, event.getScreenX(), event.getScreenY());
		} else {
			clientDragging = true;
			dragOffsetX = stage.getX() - event.getScreenX();
			dragOffsetY = stage.getY() - event.getScreenY();
		}
	}

	/** Continues a client-side drag (no-op for the native X11 drag). */
	void continueHudMove(MouseEvent event) {
		if (clientDragging) {
			stage.setX(event.getScreenX() + dragOffsetX);
			stage.setY(event.getScreenY() + dragOffsetY);
		}
	}

	/** Ends the drag. */
	void endHudMove(MouseEvent event) {
		clientDragging = false;
		nativeDragging = false;
	}

	/**
	 * The screen the HUD lives on. Default: the primary display (same as electron/windows.ts).
	 * {@code -Dgravaai.hud.screen=widest} is the development/screenshot variant for this machine,
	 * whose primary display is portrait.
	 */
	static Screen hudScreen() {
		String setting = System.getProperty("gravaai.hud.screen", "");
		if (setting.equals("widest")) {
			return dev.gravaai.ui.kit.ShowcaseStage.showcaseScreen();
		}
		return Screen.getPrimary();
	}

	/** True while the window manager owns the drag (diagnostics). */
	public boolean isNativeDragging() {
		return nativeDragging;
	}

	/** The HUD stage (window registry, tests). */
	public Stage stage() {
		return stage;
	}

	/** The bar view; null until the first deferred build pass. */
	public HudBar bar() {
		return bar;
	}

	/** Nanos since construction (startup profiling). */
	public long ageNanos() {
		return System.nanoTime() - createdAtNanos;
	}

	private final class HudRoot extends Pane {
		@Override
		protected void layoutChildren() {
			double width = getWidth();
			double height = getHeight();
			double barHeight = 0;
			if (bar != null) {
				double barWidth = Math.max(1, bar.prefWidth(-1));
				barHeight = Math.max(1, bar.prefHeight(barWidth));
				bar.resize(barWidth, barHeight);
				bar.relocate(Math.max(0, (width - barWidth) / 2), Math.max(0, height - barHeight));
			}
			if (hintLabel.isVisible()) {
				double hintWidth = Math.min(520, Math.max(1, hintLabel.prefWidth(-1)));
				double hintHeight = Math.max(1, hintLabel.prefHeight(hintWidth));
				hintLabel.resize(hintWidth, hintHeight);
				hintLabel.relocate(Math.max(0, (width - hintWidth) / 2),
						Math.max(0, height - barHeight - HINT_GAP - hintHeight));
			}
			if (webcamPreview != null && webcamPreview.isVisible()) {
				double previewWidth = webcamPreview.frameWidth();
				double previewHeight = webcamPreview.frameHeight();
				webcamPreview.resizeRelocate(Math.max(0, width - previewWidth),
						0, previewWidth, previewHeight);
			}
		}
	}
}
