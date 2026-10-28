package dev.gravaai.ui.launch;

import dev.gravaai.ui.AppFonts;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/**
 * Development harness for the HUD (P1.7 screenshots / manual checks), like {@code UiKitShowcase}.
 * Not wired by the app: {@code AppContext} builds the real {@link HudOverlayWindow} with the
 * recording port. The in-memory port lets the harness toggle idle/recording/finalizing and open
 * the HUD menu without a recorder.
 *
 * <pre>{@code
 * java -Dgravaai.hud.demo=recording HUDShowcase
 * java -Dgravaai.hud.demo=menu HUDShowcase      # opens the microphone menu
 * }</pre>
 */
public final class HudShowcase extends Application {

	@Override
	public void start(Stage ignored) {
		AppFonts.load();
		HudHarnessPort port = new HudHarnessPort();
		HudOverlayWindow hud = new HudOverlayWindow(port);

		// The app spends ~100 ms building the recording services between the HUD constructor
		// and show(); mirror that so the icon-prewarm thread has the same head start.
		try {
			Thread.sleep(120);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		hud.show();
		String demo = System.getProperty("gravaai.hud.demo", "");
		if (demo.contains("recording")) {
			port.phase = HudRecordingPort.Phase.RECORDING;
			port.notifyListeners();
		}
		if (demo.contains("menu")) {
			Platform.runLater(() -> hud.openMenu(HudMenuWindow.Menu.MIC));
		}
		if (demo.contains("webcam")) {
			Platform.runLater(() -> hud.openMenu(HudMenuWindow.Menu.WEBCAM));
		}
		if (demo.contains("countdown")) {
			Platform.runLater(() -> hud.openMenu(HudMenuWindow.Menu.COUNTDOWN));
		}
		if (demo.contains("preview")) {
			port.enableWebcam();
			Platform.runLater(() -> hud.openMenu(HudMenuWindow.Menu.WEBCAM));
		}
		if (demo.contains("floating")) {
			port.startWebcamRecording();
		}
		if (demo.contains("preview")) {
			port.enableWebcam();
			Platform.runLater(() -> hud.openMenu(HudMenuWindow.Menu.WEBCAM));
		}
		if (demo.contains("floating")) {
			port.startWebcamRecording();
		}
		if (demo.contains("finalizing")) {
			port.phase = HudRecordingPort.Phase.FINALIZING;
			port.notifyListeners();
		}
		hud.stage().setOnHidden(event -> Platform.exit());
		if (System.getProperty("gravaai.hud.debug") != null) {
			Platform.runLater(() -> {
				System.out.println("bar pref=" + hud.bar().prefWidth(-1) + "x" + hud.bar().prefHeight(-1));
				for (javafx.scene.Node node : hud.bar().getChildrenUnmodifiable()) {
					System.out.println(" child " + node.getClass().getSimpleName() + " pref="
							+ node.prefWidth(-1) + " style=" + node.getStyleClass());
				}
			});
		}
	}

	/** In-memory recording port for the harness: every action mutates the state fields. */
	static final class HudHarnessPort implements HudRecordingPort {

		private final List<Consumer<State>> listeners = new ArrayList<>();
		private State state = State.idle();
		private Phase phase = Phase.IDLE;

		/** Harness: webcam enabled with the first real camera, for the live-preview screenshot. */
		void enableWebcam() {
			List<CameraDevice> cameras = HudDevices.cameras();
			String path = cameras.isEmpty() ? null : cameras.get(0).path();
			state = new State(state.phase(), state.countdownActive(), state.sourceName(),
					state.microphoneEnabled(), state.systemAudioEnabled(), true, state.microphoneDeviceId(),
					path, state.countdownDelay(), state.meetingMode(), state.recordScreen(), false,
					state.picking());
			notifyCurrent();
		}

		/** Harness: recording with the floating webcam preview, for the HUD bar screenshot. */
		void startWebcamRecording() {
			List<CameraDevice> cameras = HudDevices.cameras();
			String path = cameras.isEmpty() ? null : cameras.get(0).path();
			phase = Phase.RECORDING;
			state = new State(Phase.RECORDING, false, "Built-in Display", true, false, true, null, path, 3,
					false, true, true, false);
			notifyCurrent();
		}

		/** Harness: switches the bar to {@code next} (for example FINALIZING after stop). */
		void setPhase(Phase next) {
			phase = next;
			notifyListeners();
		}

		/** Harness: the capture pick is pending, like the real port while the picker waits. */
		void setPicking(boolean picking) {
			state = new State(state.phase(), state.countdownActive(), state.sourceName(),
					state.microphoneEnabled(), state.systemAudioEnabled(), state.webcamEnabled(),
					state.microphoneDeviceId(), state.webcamDeviceId(), state.countdownDelay(),
					state.meetingMode(), state.recordScreen(), state.floatingWebcamPreview(), picking);
			notifyCurrent();
		}

		/** Harness: a selected source name for the source button/hint assertions. */
		void setSourceName(String sourceName) {
			state = new State(state.phase(), state.countdownActive(), sourceName,
					state.microphoneEnabled(), state.systemAudioEnabled(), state.webcamEnabled(),
					state.microphoneDeviceId(), state.webcamDeviceId(), state.countdownDelay(),
					state.meetingMode(), state.recordScreen(), state.floatingWebcamPreview(),
					state.picking());
			notifyCurrent();
		}

		private void notifyCurrent() {
			for (Consumer<State> listener : listeners) {
				listener.accept(state);
			}
		}

		void notifyListeners() {
			state = new State(phase, state.countdownActive(), state.sourceName(), state.microphoneEnabled(),
					state.systemAudioEnabled(), state.webcamEnabled(), state.microphoneDeviceId(),
					state.webcamDeviceId(), state.countdownDelay(), state.meetingMode(), state.recordScreen(),
					state.floatingWebcamPreview(), state.picking());
			for (Consumer<State> listener : listeners) {
				listener.accept(state);
			}
		}

		@Override
		public State state() {
			return state;
		}

		@Override
		public AutoCloseable onStateChanged(Consumer<State> listener) {
			listeners.add(listener);
			return () -> listeners.remove(listener);
		}

		@Override
		public void onHudReady() {
		}

		@Override
		public void toggleRecording() {
			phase = switch (phase) {
				case IDLE, FINALIZING -> Phase.RECORDING;
				case RECORDING, PAUSED -> Phase.IDLE;
			};
			notifyListeners();
		}

		@Override
		public void pauseRecording() {
			phase = Phase.PAUSED;
			notifyListeners();
		}

		@Override
		public void resumeRecording() {
			phase = Phase.RECORDING;
			notifyListeners();
		}

		@Override
		public void cancelRecording() {
			phase = Phase.IDLE;
			notifyListeners();
		}

		@Override
		public void toggleMeetingMode() {
		}

		@Override
		public void setRecordScreen(boolean recordScreen) {
		}

		@Override
		public void setMicrophoneEnabled(boolean enabled) {
		}

		@Override
		public void setSystemAudioEnabled(boolean enabled) {
		}

		@Override
		public void setWebcamEnabled(boolean enabled) {
		}

		@Override
		public void setMicrophoneDeviceId(String deviceId) {
		}

		@Override
		public void setWebcamDeviceId(String deviceId) {
		}

		@Override
		public void setCountdownDelay(int seconds) {
		}

		@Override
		public void setFloatingWebcamPreview(boolean visible) {
		}

		@Override
		public void requestSourcePick() {
		}

		@Override
		public void openDashboard() {
		}

		@Override
		public void hideHud() {
		}

		@Override
		public List<MicDevice> microphoneDevices() {
			// Real PulseAudio sources, so the harness exercises the live level meters.
			return HudDevices.microphones();
		}

		@Override
		public List<CameraDevice> cameraDevices() {
			// Real V4L2 cameras, so the harness exercises the live preview.
			return HudDevices.cameras();
		}
	}

	public static void main(String[] args) {
		launch(args);
	}
}
