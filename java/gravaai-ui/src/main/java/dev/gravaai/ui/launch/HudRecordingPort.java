package dev.gravaai.ui.launch;

import java.util.List;
import java.util.function.Consumer;

/**
 * The recording seam the HUD talks to (plan §5.6.8, §7.1 P1.7). The app wires this to
 * {@code RecordingService}, {@code SourceService}, {@code CountdownService} and the recording
 * preferences store in {@code AppContext}; the UI module stays free of the recorder's internals.
 *
 * <p>Threading: every method is called on the FX application thread and every listener is called on
 * the FX application thread. Implementations hop with {@code Platform.runLater} where the service
 * publishes from another thread.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public interface HudRecordingPort {

	/** HUD state machine (record button, timer, pause, finalizing copy). */
	enum Phase { IDLE, RECORDING, PAUSED, FINALIZING }

	/**
	 * Everything the HUD renders. Blank device ids mean "system default".
	 */
	record State(
			Phase phase,
			boolean countdownActive,
			String sourceName,
			boolean microphoneEnabled,
			boolean systemAudioEnabled,
			boolean webcamEnabled,
			String microphoneDeviceId,
			String webcamDeviceId,
			int countdownDelay,
			boolean meetingMode,
			boolean recordScreen,
			boolean floatingWebcamPreview,
			boolean picking) {

		/** An idle HUD before any service event arrived. */
		public static State idle() {
			return new State(Phase.IDLE, false, null, false, false, false, null, null, 3, false, true,
					false, false);
		}

		public boolean recording() {
			return phase == Phase.RECORDING || phase == Phase.PAUSED;
		}

		public boolean paused() {
			return phase == Phase.PAUSED;
		}

		public boolean finalizing() {
			return phase == Phase.FINALIZING;
		}

		public boolean hasSource() {
			return sourceName != null && !sourceName.isBlank();
		}

		/** A meeting without the screen records audio only: no source, no webcam controls. */
		public boolean audioOnly() {
			return meetingMode && !recordScreen;
		}
	}

	/** The current state; never null. */
	State state();

	/** Pushes state changes to the HUD (FX thread). Close the handle to unsubscribe. */
	AutoCloseable onStateChanged(Consumer<State> listener);

	/** Runs on the FX thread once the HUD subscribed; AppContext maps it to dispatcher.hudReady(). */
	void onHudReady();

	// ── recording actions ──────────────────────────────────────────────────
	void toggleRecording();

	void pauseRecording();

	void resumeRecording();

	void cancelRecording();

	// ── launch options (persisted by the app) ──────────────────────────────
	void toggleMeetingMode();

	void setRecordScreen(boolean recordScreen);

	void setMicrophoneEnabled(boolean enabled);

	void setSystemAudioEnabled(boolean enabled);

	void setWebcamEnabled(boolean enabled);

	void setMicrophoneDeviceId(String deviceId);

	void setWebcamDeviceId(String deviceId);

	void setCountdownDelay(int seconds);

	void setFloatingWebcamPreview(boolean visible);

	// ── navigation ─────────────────────────────────────────────────────────
	/** Opens the capture picker / source selector (the HUD "Pick screen" action). */
	void requestSourcePick();

	/** Opens the dashboard ("Home" button). */
	void openDashboard();

	/** Hides the HUD window ("Hide HUD" / minimize to tray). */
	void hideHud();

	/** The microphone devices, for the HUD menu (empty when PulseAudio has no sources). */
	List<MicDevice> microphoneDevices();

	/** The camera devices, for the HUD menu (empty when there is no V4L2 camera). */
	List<CameraDevice> cameraDevices();

	/** The monitors that can be recorded (empty when the output list is unavailable). */
	List<ScreenDevice> screenDevices();

	/** Records the given monitor ({@link ScreenDevice#id()}) from now on. */
	void selectScreen(String screenId);

	/** A monitor the HUD lists: {@code id} is the capture source id, {@code label} its name. */
	record ScreenDevice(String id, String label) {}

	/** A PulseAudio source used as a microphone. */
	record MicDevice(String id, String label) {}

	/** A V4L2 camera: {@code path} is the device node, {@code label} the sysfs name. */
	record CameraDevice(String path, String label) {}
}
