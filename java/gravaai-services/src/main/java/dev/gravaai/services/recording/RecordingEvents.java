package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Recording push events, ported from {@code electron/ipc/recording/events.ts} (Appendix A:
 * {@code recording-interrupted}, {@code recording-stop-requested}).
 */
public final class RecordingEvents {

	/** {@code { reason, message }} of both events. */
	public record RecordingEvent(String reason, String message) {
	}

	private final List<Consumer<RecordingEvent>> interrupted = new CopyOnWriteArrayList<>();
	private final List<Consumer<RecordingEvent>> stopRequested = new CopyOnWriteArrayList<>();

	/** Subscribes to {@code recording-interrupted}; close the returned handle to unsubscribe. */
	public AutoCloseable onRecordingInterrupted(Consumer<RecordingEvent> listener) {
		interrupted.add(listener);
		return () -> interrupted.remove(listener);
	}

	/** Subscribes to {@code recording-stop-requested}; close the returned handle to unsubscribe. */
	public AutoCloseable onRecordingStopRequested(Consumer<RecordingEvent> listener) {
		stopRequested.add(listener);
		return () -> stopRequested.remove(listener);
	}

	public void emitRecordingInterrupted(String reason, String message) {
		for (Consumer<RecordingEvent> listener : interrupted) {
			listener.accept(new RecordingEvent(reason, message));
		}
	}

	/** Asks the renderer to stop the recording the normal way, so the file is saved. */
	public void emitRecordingStopRequested(String reason, String message) {
		for (Consumer<RecordingEvent> listener : stopRequested) {
			listener.accept(new RecordingEvent(reason, message));
		}
	}

	/** Signals the UI that the recording state changed: {@code { recording, sourceName }}. */
	public record RecordingState(boolean recording, String sourceName) {
	}

	private final List<Consumer<RecordingState>> stateChanged = new CopyOnWriteArrayList<>();

	public AutoCloseable onRecordingStateChanged(Consumer<RecordingState> listener) {
		stateChanged.add(listener);
		return () -> stateChanged.remove(listener);
	}

	public void emitRecordingStateChanged(boolean recording, String sourceName) {
		for (Consumer<RecordingState> listener : stateChanged) {
			listener.accept(new RecordingState(recording, sourceName));
		}
	}

	/** {@code { videoPath, webcamPath, timeOffsetMs, hideOverlayCursorByDefault }} pushed to the UI. */
	public record RecordingSession(String videoPath, @Nullable String webcamPath, long timeOffsetMs, @Nullable Boolean hideOverlayCursorByDefault) {
	}

	private final List<Consumer<RecordingSession>> sessionChanged = new CopyOnWriteArrayList<>();

	public AutoCloseable onRecordingSessionChanged(Consumer<RecordingSession> listener) {
		sessionChanged.add(listener);
		return () -> sessionChanged.remove(listener);
	}

	public void emitRecordingSessionChanged(RecordingSession session) {
		for (Consumer<RecordingSession> listener : sessionChanged) {
			listener.accept(session);
		}
	}
}
