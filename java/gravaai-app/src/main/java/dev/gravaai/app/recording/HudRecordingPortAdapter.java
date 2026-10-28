package dev.gravaai.app.recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;

import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.core.settings.RecordingPreferencesPatch;
import dev.gravaai.core.settings.RecordingPreferencesStore;
import dev.gravaai.core.storage.UserData;
import dev.gravaai.media.audio.PulseAudio;
import dev.gravaai.media.capture.WebcamDevice;
import dev.gravaai.media.capture.WebcamDevices;
import dev.gravaai.services.recording.CosmicRecorder.RecordingOptions;
import dev.gravaai.services.recording.CosmicRecorder.StartResult;
import dev.gravaai.services.recording.CosmicRecorder.StopResult;
import dev.gravaai.services.recording.CountdownDelayStore;
import dev.gravaai.services.recording.CountdownService;
import dev.gravaai.services.recording.RecordingEvents;
import dev.gravaai.services.recording.RecordingService;
import dev.gravaai.services.recording.RecordingSessionManifest;
import dev.gravaai.services.recording.WebcamRecordingService;
import dev.gravaai.services.recording.ports.PlatformRecordingEnvironment;
import dev.gravaai.services.recording.ports.PlatformRecordingProcesses;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.sources.SourceService;
import dev.gravaai.ui.launch.HudRecordingPort;
import tools.jackson.databind.JsonNode;

/**
 * The HUD's recording seam over the real services (plan §5.6.8, §7.1 P1.7), wired by
 * {@code AppContext}.
 *
 * <p>State is a projection of {@code RecordingService} (active/paused/finalizing), the
 * {@code CountdownService}, the selected {@code SourceService} source, the recording preferences
 * ({@code recordings-settings.json}), the countdown delay store and the HUD launch state.
 * Preferences persisted by the React app stay in {@code recordings-settings.json}; meeting mode,
 * "record screen" and the floating webcam preview were renderer-localStorage launch state in the
 * React app, so they stay in memory here (documented launch state).
 *
 * <p>Threading: the HUD calls on the FX thread; service listeners are hopped to the FX thread.
 * Long operations (countdown wait, recording start/stop, preference writes) run on virtual threads.
 */
public final class HudRecordingPortAdapter implements HudRecordingPort, AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.hud.recording");

	/** App navigation the HUD buttons trigger; implemented by {@code AppContext}. */
	public interface Navigation {
		void openDashboard();

		void requestSourcePick();

		void hideHud();

		/** {@code switchToEditor} after a finished recording: opens it in the main window. */
		void openRecordingInEditor(String videoPath);
	}

	/** The values persisted in {@code recordings-settings.json}. */
	private record Preferences(
			boolean microphoneEnabled,
			String microphoneDeviceId,
			boolean systemAudioEnabled,
			boolean webcamEnabled,
			String webcamDeviceId) {

		static Preferences defaults() {
			return new Preferences(false, null, false, false, null);
		}
	}

	private final RecordingWiring wiring;
	private final UserData userData;
	private final AppSettingsStore settings;
	private final Navigation navigation;
	private final RecordingPreferencesStore preferencesStore;
	private final CountdownDelayStore countdownDelayStore;
	/** Devices are enumerated lazily and cached briefly: the HUD menu is the only consumer. */
	private record CachedDevices(long atMs, List<MicDevice> microphones, List<CameraDevice> cameras) {
	}

	private static final long DEVICE_CACHE_TTL_MS = 2_000;

	private final PlatformRecordingProcesses webcamProcesses = new PlatformRecordingProcesses();
	private final PlatformRecordingEnvironment webcamEnvironment = new PlatformRecordingEnvironment();
	private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
	private final List<AutoCloseable> subscriptions = new ArrayList<>();
	private final AtomicReference<Runnable> hudReadyListener = new AtomicReference<>();
	private final AtomicBoolean hudReadyCalled = new AtomicBoolean();

	private volatile Preferences preferences = Preferences.defaults();
	private volatile int countdownDelay = 3;
	private volatile boolean paused;
	private volatile boolean finalizing;
	private volatile boolean meetingMode;
	private volatile boolean recordScreen = true;
	private volatile boolean floatingWebcamPreview;
	private volatile boolean picking;
	private volatile WebcamRecordingService.WebcamRecording webcamRecording;
	private volatile WebcamRecordingService webcamService;
	private volatile CachedDevices cachedDevices;

	private HudRecordingPortAdapter(
			RecordingWiring wiring,
			UserData userData,
			AppSettingsStore settings,
			Navigation navigation) {
		this.wiring = wiring;
		this.userData = userData;
		this.settings = settings;
		this.navigation = navigation;
		this.preferencesStore = new RecordingPreferencesStore(userData.recordingsSettingsFile());
		this.countdownDelayStore = wiring.countdownDelay();
		this.countdownDelay = (int) Math.round(countdownDelayStore.getCountdownDelay());
	}

	/** Builds the adapter on the FX thread; loads preferences and subscriptions asynchronously. */
	public static HudRecordingPortAdapter create(
			RecordingWiring wiring,
			UserData userData,
			AppSettingsStore settings,
			Navigation navigation) {
		HudRecordingPortAdapter adapter = new HudRecordingPortAdapter(wiring, userData, settings, navigation);
		adapter.subscribe();
		adapter.loadPreferences();
		return adapter;
	}

	/** The dispatcher-ready callback; Lead wires {@code dispatcher.hudReady()}. */
	public void setOnHudReady(Runnable listener) {
		hudReadyListener.set(listener);
	}

	// ── HudRecordingPort ──────────────────────────────────────────────────

	@Override
	public State state() {
		RecordingService service = wiring.recording();
		Phase phase = finalizing
				? Phase.FINALIZING
				: (service.isRecordingActive() ? (paused ? Phase.PAUSED : Phase.RECORDING) : Phase.IDLE);
		SelectedSource source = wiring.sources().getSelectedSource();
		Preferences current = preferences;
		return new State(
				phase,
				wiring.countdown().getActiveCountdown() != null,
				source == null ? null : source.name(),
				current.microphoneEnabled(),
				current.systemAudioEnabled(),
				current.webcamEnabled(),
				current.microphoneDeviceId(),
				current.webcamDeviceId(),
				countdownDelay,
				meetingMode,
				recordScreen,
				floatingWebcamPreview,
				picking);
	}

	@Override
	public AutoCloseable onStateChanged(Consumer<State> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	@Override
	public void onHudReady() {
		if (!hudReadyCalled.compareAndSet(false, true)) {
			return;
		}
		Runnable listener = hudReadyListener.get();
		if (listener != null) {
			listener.run();
		}
	}

	@Override
	public void toggleRecording() {
		if (finalizing) {
			return;
		}
		if (wiring.recording().isRecordingActive()) {
			stopRecording();
			return;
		}
		if (wiring.countdown().getActiveCountdown() != null) {
			return;
		}
		State current = state();
		boolean audioOnly = meetingMode && !recordScreen;
		if (!audioOnly && !current.hasSource()) {
			// Same state machine as LaunchWindow.tsx: without a source the record button
			// first asks for one, then the next press starts the recording.
			navigation.requestSourcePick();
			return;
		}
		Thread.ofVirtual().name("gravaai-hud-start").start(this::startRecording);
	}

	private void startRecording() {
		try {
			if (countdownDelay > 0) {
				CountdownService.CountdownResult countdownResult =
						wiring.countdown().start(countdownDelay).join();
				if (!countdownResult.success() || countdownResult.cancelled()) {
					// The countdown window could not be created (for example a JavaFX thread
					// violation) or the user cancelled it: without a log line this looked like the
					// record button doing nothing.
					if (countdownResult.cancelled()) {
						LOG.log(System.Logger.Level.INFO,
								"recording start cancelled during the countdown");
					} else {
						LOG.log(System.Logger.Level.WARNING,
								"recording start failed: countdown unavailable: "
										+ countdownResult.error());
					}
					publish();
					return;
				}
			}
			Preferences current = preferences;
			boolean audioOnly = meetingMode && !recordScreen;
			RecordingOptions options = new RecordingOptions(
					current.systemAudioEnabled(),
					current.microphoneEnabled(),
					current.microphoneDeviceId(),
					microphoneLabel(current.microphoneDeviceId()),
					meetingMode);
			StartResult result;
			if (audioOnly) {
				result = wiring.recording().startAudioRecording(options);
			} else {
				SelectedSource source = wiring.sources().getSelectedSource();
				if (source == null) {
					LOG.log(System.Logger.Level.INFO, "recording start skipped: no source selected");
					publish();
					return;
				}
				result = wiring.recording().startNativeScreenRecording(source, options);
			}
			if (!result.success()) {
				LOG.log(System.Logger.Level.WARNING, "recording start failed: " + result.message());
				publish();
				return;
			}
			paused = false;
			finalizing = false;
			wiring.recording().setRecordingState(true);
			if (!audioOnly && current.webcamEnabled()) {
				startWebcamRecording(current.webcamDeviceId());
			}
			LOG.log(System.Logger.Level.INFO, "recording started (" + (audioOnly ? "audio only" : "screen")
					+ ", countdown " + countdownDelay + "s)");
			publish();
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "could not start the recording: " + error);
			publish();
		}
	}

	/** Built on the first webcam recording, never on the startup path. */
	private WebcamRecordingService webcamService() {
		WebcamRecordingService service = webcamService;
		if (service == null) {
			synchronized (this) {
				service = webcamService;
				if (service == null) {
					service = new WebcamRecordingService(
							wiring.recordingsDir(),
							webcamProcesses,
							webcamEnvironment,
							WebcamRecordingService.ffmpegCliEncoder(webcamProcesses, webcamEnvironment),
							WebcamDevices::list);
					webcamService = service;
				}
			}
		}
		return service;
	}

	private void startWebcamRecording(String devicePath) {
		try {
			long now = System.currentTimeMillis();
			WebcamRecordingService.StartResult result =
					webcamService().start(now, now, devicePath, null);
			if (!result.success()) {
				LOG.log(System.Logger.Level.WARNING, "webcam recording unavailable: " + result.message());
				return;
			}
			webcamRecording = result.recording();
			LOG.log(System.Logger.Level.INFO, "webcam recording started: " + result.recording().path());
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "webcam recording failed: " + error);
		}
	}

	public void stopRecordingFromTray() {
		// Tray Activate / Stop Recording must run the full stop flow (webcam finalise,
		// setRecordingState(false), open-in-editor). Calling the service directly would skip
		// all of that and leave the tray icon stuck + the webcam running.
		if (!wiring.recording().isRecordingActive() && !finalizing) {
			return;
		}
		stopRecording();
	}

	private void stopRecording() {
		finalizing = true;
		paused = false;
		publish();
		Thread.ofVirtual().name("gravaai-hud-stop").start(() -> {
			try {
				finishStop();
			} catch (RuntimeException error) {
				// Without this the HUD stayed on "Preparing recording" forever and every later
				// record press was ignored (toggleRecording returns while finalizing).
				LOG.log(System.Logger.Level.WARNING, "recording stop failed: " + error);
				wiring.recording().setRecordingState(false);
				finalizing = false;
				publish();
			}
		});
	}

	private void finishStop() {
		WebcamRecordingService.WebcamRecording webcam = webcamRecording;
		webcamRecording = null;
		String webcamPath = null;
		long webcamOffsetMs = 0;
		if (webcam != null) {
			String webcamError = webcam.stop();
			webcamOffsetMs = webcam.timeOffsetMs();
			webcamPath = webcam.path().toString();
			if (webcamError != null) {
				LOG.log(System.Logger.Level.WARNING, "webcam finalise failed: " + webcamError);
			}
		}
		StopResult result = wiring.recording().stopNativeScreenRecording();
		wiring.recording().setRecordingState(false);
		finalizing = false;
		if (result.success() && result.path() != null) {
			wiring.recording().setCurrentVideoPath(result.path());
			if (webcamPath != null) {
				try {
					RecordingSessionManifest.persistRecordingSessionManifest(
							new RecordingSessionManifest.RecordingSessionData(
									result.path(), webcamPath, webcamOffsetMs));
				} catch (IOException error) {
					LOG.log(System.Logger.Level.WARNING,
							"could not write the recording session manifest: " + error);
				}
			}
			wiring.recording().setCurrentRecordingSession(new RecordingEvents.RecordingSession(
					result.path(), webcamPath, webcamOffsetMs, null));
			LOG.log(System.Logger.Level.INFO, "recording saved: " + result.path());
			if (result.meeting() == null) {
				// finalizeRecordingSession → switchToEditor. Meeting recordings go to the
				// meeting library instead (registerRecording in AppContext).
				navigation.openRecordingInEditor(result.path());
			}
		} else {
			LOG.log(System.Logger.Level.WARNING, "recording stop failed: " + result.error());
		}
		publish();
	}

	@Override
	public void pauseRecording() {
		if (!wiring.recording().isRecordingActive() || paused) {
			return;
		}
		long now = System.currentTimeMillis();
		wiring.recording().pauseCursorCapture(now);
		StartResult result = wiring.recording().pauseNativeScreenRecording();
		if (result.success()) {
			paused = true;
			publish();
		}
	}

	@Override
	public void resumeRecording() {
		if (!wiring.recording().isRecordingActive() || !paused) {
			return;
		}
		wiring.recording().resumeCursorCapture(System.currentTimeMillis());
		StartResult result = wiring.recording().resumeNativeScreenRecording();
		if (result.success()) {
			paused = false;
			publish();
		}
	}

	/**
	 * Cancels the countdown, or discards the active recording. The recording service has no
	 * discard channel of its own, so this uses the recorder's {@code discardCosmicRecording} (stops
	 * the helper/encoder, deletes the temp files, produces no video) and then stops the cursor
	 * session through {@code setRecordingState(false)}; a webcam file from the cancelled take is
	 * deleted. Documented in the task report.
	 */
	@Override
	public void cancelRecording() {
		if (wiring.countdown().getActiveCountdown() != null) {
			wiring.countdown().cancel();
			publish();
			return;
		}
		if (finalizing || !wiring.recording().isRecordingActive()) {
			return;
		}
		WebcamRecordingService.WebcamRecording webcam = webcamRecording;
		webcamRecording = null;
		if (webcam != null) {
			webcam.stop();
			try {
				Files.deleteIfExists(webcam.path());
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "could not delete the cancelled webcam file: " + error);
			}
		}
		wiring.cosmicRecorder().discardCosmicRecording();
		wiring.recording().setRecordingState(false);
		paused = false;
		publish();
	}

	// ── launch options ────────────────────────────────────────────────────

	@Override
	public void toggleMeetingMode() {
		meetingMode = !meetingMode;
		publish();
	}

	@Override
	public void setRecordScreen(boolean recordScreen) {
		this.recordScreen = recordScreen;
		publish();
	}

	@Override
	public void setMicrophoneEnabled(boolean enabled) {
		updatePreferences(new RecordingPreferencesPatch(enabled, null, null, null, null),
				new Preferences(enabled, preferences.microphoneDeviceId(),
						preferences.systemAudioEnabled(), preferences.webcamEnabled(),
						preferences.webcamDeviceId()));
	}

	@Override
	public void setSystemAudioEnabled(boolean enabled) {
		updatePreferences(new RecordingPreferencesPatch(null, null, enabled, null, null),
				new Preferences(preferences.microphoneEnabled(), preferences.microphoneDeviceId(),
						enabled, preferences.webcamEnabled(), preferences.webcamDeviceId()));
	}

	@Override
	public void setWebcamEnabled(boolean enabled) {
		updatePreferences(new RecordingPreferencesPatch(null, null, null, enabled, null),
				new Preferences(preferences.microphoneEnabled(), preferences.microphoneDeviceId(),
						preferences.systemAudioEnabled(), enabled, preferences.webcamDeviceId()));
	}

	@Override
	public void setMicrophoneDeviceId(String deviceId) {
		updatePreferences(new RecordingPreferencesPatch(null, deviceId, null, null, null),
				new Preferences(preferences.microphoneEnabled(), deviceId,
						preferences.systemAudioEnabled(), preferences.webcamEnabled(),
						preferences.webcamDeviceId()));
	}

	@Override
	public void setWebcamDeviceId(String deviceId) {
		updatePreferences(new RecordingPreferencesPatch(null, null, null, null, deviceId),
				new Preferences(preferences.microphoneEnabled(), preferences.microphoneDeviceId(),
						preferences.systemAudioEnabled(), preferences.webcamEnabled(), deviceId));
	}

	@Override
	public void setCountdownDelay(int seconds) {
		if (seconds < 0) {
			return;
		}
		countdownDelay = seconds;
		publish();
		Thread.ofVirtual().name("gravaai-countdown-delay").start(() -> {
			try {
				countdownDelayStore.setCountdownDelay(seconds);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "could not save the countdown delay: " + error);
			}
		});
	}

	@Override
	public void setFloatingWebcamPreview(boolean visible) {
		floatingWebcamPreview = visible;
		publish();
	}

	private void updatePreferences(RecordingPreferencesPatch patch, Preferences next) {
		preferences = next;
		publish();
		Thread.ofVirtual().name("gravaai-recording-prefs").start(() -> {
			try {
				preferencesStore.update(patch).join();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "could not save recording preferences: " + error);
			}
		});
	}

	// ── navigation / devices ──────────────────────────────────────────────

	/**
	 * The HUD "pick screen" action: on COSMIC the capture picker records the output the user
	 * clicks ({@code CapturePickerService} click path); on X11 the source list opens through the
	 * same service. Runs on a virtual thread because the COSMIC pick waits for the click.
	 */
	@Override
	public void requestSourcePick() {
		if (picking) {
			return;
		}
		picking = true;
		publish();
		LOG.log(System.Logger.Level.INFO, "source pick requested");
		Thread.ofVirtual().name("gravaai-hud-source-pick").start(() -> {
			String outcome = "no selection";
			try {
				SourceService.PickResult result = wiring.capturePicker().pickCaptureTarget().join();
				if (result.success()) {
					SelectedSource picked = result.source() == null
							? wiring.sources().getSelectedSource() : result.source();
					outcome = "picked " + (picked == null ? "<unknown>" : picked.name());
				} else if (result.canceled()) {
					outcome = "cancelled (no screen was clicked before the picker timed out)";
				} else if (result.message() != null) {
					outcome = "failed: " + result.message();
					LOG.log(System.Logger.Level.WARNING, "source pick failed: " + result.message());
				}
			} catch (RuntimeException error) {
				outcome = "failed: " + error;
				LOG.log(System.Logger.Level.WARNING, "source pick failed: " + error);
			}
			LOG.log(System.Logger.Level.INFO, "source pick finished: " + outcome);
			picking = false;
			publish();
		});
	}

	@Override
	public void openDashboard() {
		navigation.openDashboard();
	}

	@Override
	public void hideHud() {
		navigation.hideHud();
	}

	@Override
	public List<MicDevice> microphoneDevices() {
		return devices().microphones();
	}

	@Override
	public List<CameraDevice> cameraDevices() {
		return devices().cameras();
	}

	/** Enumerates devices on first use (HUD menu) and caches them briefly. */
	private CachedDevices devices() {
		CachedDevices cached = cachedDevices;
		long now = System.currentTimeMillis();
		if (cached != null && now - cached.atMs() < DEVICE_CACHE_TTL_MS) {
			return cached;
		}
		List<MicDevice> microphones = new ArrayList<>();
		for (var source : PulseAudio.listSources()) {
			microphones.add(new MicDevice(source.name(), source.description()));
		}
		List<CameraDevice> cameras = new ArrayList<>();
		for (WebcamDevice camera : WebcamDevices.list()) {
			cameras.add(new CameraDevice(camera.path(), camera.name()));
		}
		CachedDevices loaded = new CachedDevices(now, List.copyOf(microphones), List.copyOf(cameras));
		cachedDevices = loaded;
		LOG.log(System.Logger.Level.DEBUG, "HUD device enumeration: " + microphones.size()
				+ " microphones, " + cameras.size() + " cameras");
		return loaded;
	}

	// ── lifecycle ─────────────────────────────────────────────────────────

	private void subscribe() {
		subscriptions.add(wiring.events().onRecordingStateChanged(state -> {
			if (!state.recording()) {
				paused = false;
				if (!finalizing) {
					finalizing = false;
				}
			}
			publish();
		}));
		subscriptions.add(wiring.countdown().onRemainingChanged(remaining -> publish()));
		subscriptions.add(wiring.sources().onSelectedSourceChanged(selected -> publish()));
		subscriptions.add(countdownDelayStore.onCountdownDelayChanged(delay -> {
			countdownDelay = delay == null ? countdownDelay : delay.intValue();
			publish();
		}));
	}

	private void loadPreferences() {
		Thread.ofVirtual().name("gravaai-recording-prefs-load").start(() -> {
			try {
				Map<String, JsonNode> store = preferencesStore.read().join();
				Preferences loaded = new Preferences(
						booleanValue(store.get("microphoneEnabled"), false),
						textValue(store.get("microphoneDeviceId")),
						booleanValue(store.get("systemAudioEnabled"), false),
						booleanValue(store.get("webcamEnabled"), false),
						textValue(store.get("webcamDeviceId")));
				Platform.runLater(() -> {
					preferences = loaded;
					publish();
				});
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "could not load recording preferences: " + error);
			}
		});
	}

	private void publish() {
		Runnable deliver = () -> {
			State next;
			try {
				next = state();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "could not compute the HUD state: " + error);
				return;
			}
			for (Consumer<State> listener : listeners) {
				try {
					listener.accept(next);
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING, "HUD state listener failed: " + error);
				}
			}
		};
		if (Platform.isFxApplicationThread()) {
			deliver.run();
		} else {
			Platform.runLater(deliver);
		}
	}

	private String microphoneLabel(String deviceId) {
		if (deviceId == null || deviceId.isBlank()) {
			return null;
		}
		for (var source : PulseAudio.listSources()) {
			if (source.name().equals(deviceId)) {
				return source.description();
			}
		}
		return null;
	}

	private static boolean booleanValue(JsonNode node, boolean fallback) {
		return node != null && node.isBoolean() ? node.booleanValue() : fallback;
	}

	private static String textValue(JsonNode node) {
		return node != null && node.isString() && !node.asString().isBlank() ? node.asString() : null;
	}

	@Override
	public void close() {
		for (AutoCloseable subscription : List.copyOf(subscriptions)) {
			try {
				subscription.close();
			} catch (Exception error) {
				LOG.log(System.Logger.Level.WARNING, "could not close a HUD subscription: " + error);
			}
		}
		subscriptions.clear();
		listeners.clear();
		WebcamRecordingService.WebcamRecording webcam = webcamRecording;
		webcamRecording = null;
		if (webcam != null) {
			webcam.stop();
		}
	}
}
