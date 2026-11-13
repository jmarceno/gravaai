package dev.gravaai.app.recording;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.application.Platform;

import dev.gravaai.services.recording.MicGate;
import dev.gravaai.services.recording.cursor.CursorCaptureSession;
import dev.gravaai.ui.launch.HudRecordingPort.MicTalkState;

/**
 * Push-to-talk during a recording: the mic is muted unless the global shortcut is held, and the
 * HUD's mic icon follows the live/muted state.
 *
 * <p>The muting itself is the capture session's {@link MicGate} (fed by the interaction helper's
 * key telemetry, with the desktop's shortcut press as a toggle fallback); this controller only
 * projects the gate's state for the HUD and routes the HUD's own flip action into the gate.
 */
final class PushToTalkController {

	private static final System.Logger LOG = System.getLogger("gravaai.recording.ptt");

	private final CursorCaptureSession cursorSession;
	private final BooleanSupplier recordingActive;
	private final List<Consumer<MicTalkState>> listeners = new CopyOnWriteArrayList<>();

	PushToTalkController(CursorCaptureSession cursorSession, BooleanSupplier recordingActive) {
		this.cursorSession = cursorSession;
		this.recordingActive = recordingActive;
		cursorSession.micGate().onMutedChanged(muted -> publish());
	}

	MicTalkState state() {
		MicGate gate = cursorSession.micGate();
		boolean available = recordingActive.getAsBoolean() && gate.isActive();
		return new MicTalkState(available, available && !gate.isMuted());
	}

	AutoCloseable onMicTalkStateChanged(Consumer<MicTalkState> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** The desktop shortcut fired a press (the gate's toggle fallback while evdev is silent). */
	void shortcutPress() {
		cursorSession.micGate().onShortcutPress();
		publish();
	}

	/** The HUD mic icon was clicked: always flips the mic. */
	void flip() {
		cursorSession.micGate().flip();
		publish();
	}

	private void publish() {
		Runnable deliver = () -> {
			MicTalkState next = state();
			for (Consumer<MicTalkState> listener : listeners) {
				try {
					listener.accept(next);
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING, "mic talk listener failed: " + error);
				}
			}
		};
		if (Platform.isFxApplicationThread()) {
			deliver.run();
		} else {
			Platform.runLater(deliver);
		}
	}
}
