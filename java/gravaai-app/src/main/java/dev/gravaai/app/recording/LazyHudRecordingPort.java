package dev.gravaai.app.recording;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dev.gravaai.ui.launch.HudRecordingPort;

/**
 * The HUD's recording port during startup (P1.14 startup budget): the HUD window is built and shown
 * with this idle delegate, then the real {@link HudRecordingPortAdapter} is attached on the
 * post-window thread, its state is pushed to the subscribed HUD and any action the user triggered
 * in the gap is replayed in order. This keeps the ~120 ms recording-wiring construction off the
 * window-visible path while the HUD stays fully functional.
 *
 * <p>Threading: the HUD calls on the FX thread; the delegate is attached from the startup thread and
 * state forwarding hops to the FX thread.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class LazyHudRecordingPort implements HudRecordingPort, AutoCloseable {

	private static final System.Logger LOG = System.getLogger("gravaai.hud.lazy");

	private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();
	private final List<Runnable> queuedActions = new ArrayList<>();
	private final AtomicReference<Runnable> onHudReady = new AtomicReference<>();
	private final AtomicBoolean hudReadyCalled = new AtomicBoolean();
	private final AtomicReference<HudRecordingPort> delegate = new AtomicReference<>();
	private final AtomicReference<AutoCloseable> delegateSubscription = new AtomicReference<>();
	private volatile State idleState = State.idle();

	/** The dispatcher-ready callback, fired once the real port is attached. */
	public void setOnHudReady(Runnable listener) {
		onHudReady.set(listener);
	}

	/**
	 * Switches to the real port: replays queued actions, subscribes and pushes the real state. The
	 * delegate is published immediately (so later HUD calls go straight to it) while the replay and
	 * the state push are marshalled to the JavaFX thread, where the HUD nodes live.
	 */
	public void attach(HudRecordingPort realPort) {
		if (realPort == null) {
			return;
		}
		delegate.set(realPort);
		Runnable finish = () -> {
			List<Runnable> replay;
			synchronized (queuedActions) {
				replay = List.copyOf(queuedActions);
				queuedActions.clear();
			}
			for (Runnable action : replay) {
				invoke(action);
			}
			AutoCloseable subscription = realPort.onStateChanged(this::deliver);
			delegateSubscription.set(subscription);
			deliver(realPort.state());
			if (hudReadyCalled.get()) {
				fireHudReady();
			}
			LOG.log(System.Logger.Level.INFO,
					"HUD recording port attached (" + replay.size() + " queued actions replayed)");
		};
		if (javafx.application.Platform.isFxApplicationThread()) {
			finish.run();
		} else {
			javafx.application.Platform.runLater(finish);
		}
	}

	/** Runs an action on the real port, or queues it until the port is attached. */
	private void invoke(Runnable action) {
		HudRecordingPort real = delegate.get();
		if (real != null) {
			action.run();
			return;
		}
		synchronized (queuedActions) {
			if (delegate.get() == null) {
				queuedActions.add(action);
				return;
			}
		}
		action.run();
	}

	private void deliver(State state) {
		if (state == null) {
			return;
		}
		for (Consumer<State> listener : listeners) {
			try {
				listener.accept(state);
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "HUD state listener failed: " + error);
			}
		}
	}

	// ── HudRecordingPort ──────────────────────────────────────────────────

	@Override
	public State state() {
		HudRecordingPort real = delegate.get();
		return real == null ? idleState : real.state();
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
		if (delegate.get() != null) {
			fireHudReady();
		}
	}

	private void fireHudReady() {
		Runnable listener = onHudReady.get();
		if (listener != null) {
			listener.run();
		}
	}

	@Override
	public void toggleRecording() {
		invoke(() -> delegate("toggleRecording").toggleRecording());
	}

	@Override
	public void pauseRecording() {
		invoke(() -> delegate("pauseRecording").pauseRecording());
	}

	@Override
	public void resumeRecording() {
		invoke(() -> delegate("resumeRecording").resumeRecording());
	}

	@Override
	public void cancelRecording() {
		invoke(() -> delegate("cancelRecording").cancelRecording());
	}

	@Override
	public void toggleMeetingMode() {
		invoke(() -> delegate("toggleMeetingMode").toggleMeetingMode());
	}

	@Override
	public void setRecordScreen(boolean recordScreen) {
		invoke(() -> delegate("setRecordScreen").setRecordScreen(recordScreen));
	}

	@Override
	public void setMicrophoneEnabled(boolean enabled) {
		invoke(() -> delegate("setMicrophoneEnabled").setMicrophoneEnabled(enabled));
	}

	@Override
	public void setSystemAudioEnabled(boolean enabled) {
		invoke(() -> delegate("setSystemAudioEnabled").setSystemAudioEnabled(enabled));
	}

	@Override
	public void setWebcamEnabled(boolean enabled) {
		invoke(() -> delegate("setWebcamEnabled").setWebcamEnabled(enabled));
	}

	@Override
	public void setMicrophoneDeviceId(String deviceId) {
		invoke(() -> delegate("setMicrophoneDeviceId").setMicrophoneDeviceId(deviceId));
	}

	@Override
	public void setWebcamDeviceId(String deviceId) {
		invoke(() -> delegate("setWebcamDeviceId").setWebcamDeviceId(deviceId));
	}

	@Override
	public void setCountdownDelay(int seconds) {
		invoke(() -> delegate("setCountdownDelay").setCountdownDelay(seconds));
	}

	@Override
	public void setFloatingWebcamPreview(boolean visible) {
		invoke(() -> delegate("setFloatingWebcamPreview").setFloatingWebcamPreview(visible));
	}

	@Override
	public void requestSourcePick() {
		invoke(() -> delegate("requestSourcePick").requestSourcePick());
	}

	@Override
	public void openDashboard() {
		invoke(() -> delegate("openDashboard").openDashboard());
	}

	@Override
	public void hideHud() {
		invoke(() -> delegate("hideHud").hideHud());
	}

	@Override
	public List<MicDevice> microphoneDevices() {
		HudRecordingPort real = delegate.get();
		return real == null ? List.of() : real.microphoneDevices();
	}

	@Override
	public List<CameraDevice> cameraDevices() {
		HudRecordingPort real = delegate.get();
		return real == null ? List.of() : real.cameraDevices();
	}

	@Override
	public List<ScreenDevice> screenDevices() {
		HudRecordingPort real = delegate.get();
		return real == null ? List.of() : real.screenDevices();
	}

	@Override
	public void selectScreen(String screenId) {
		invoke(() -> delegate("selectScreen").selectScreen(screenId));
	}

	private HudRecordingPort delegate(String action) {
		HudRecordingPort real = delegate.get();
		if (real == null) {
			throw new IllegalStateException("HUD recording port is not attached yet (" + action + ")");
		}
		return real;
	}

	@Override
	public void close() {
		AutoCloseable subscription = delegateSubscription.getAndSet(null);
		if (subscription != null) {
			try {
				subscription.close();
			} catch (Exception error) {
				LOG.log(System.Logger.Level.WARNING, "could not close the HUD state subscription: " + error);
			}
		}
		HudRecordingPort real = delegate.getAndSet(null);
		if (real instanceof AutoCloseable closeable) {
			try {
				closeable.close();
			} catch (Exception error) {
				LOG.log(System.Logger.Level.WARNING, "could not close the HUD recording port: " + error);
			}
		}
		listeners.clear();
	}
}
