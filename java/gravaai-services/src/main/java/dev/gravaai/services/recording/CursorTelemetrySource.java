package dev.gravaai.services.recording;

import java.util.List;

import org.jspecify.annotations.Nullable;

import dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint;

/**
 * The cursor-capture session the recorder talks to: it reads the session state when it saves
 * telemetry, and feeds back the pointer positions the capture helper measured.
 */
public interface CursorTelemetrySource {

	/** Wall-clock time the renderer reported the recording as started. */
	long cursorCaptureStartTimeMs();

	/**
	 * Snapshots the active samples into the pending set (port of
	 * {@code snapshotCursorTelemetryForPersistence}) and returns them, clearing the pending set.
	 */
	List<CursorTelemetryPoint> snapshotAndTakePendingSamples();

	/**
	 * One pointer position the compositor measured inside the surface being recorded, normalized
	 * to the recorded frame (0..1).
	 *
	 * <p>This is the most accurate reading available: it comes from the compositor that produces
	 * the frames, so it is already in the video's own coordinates. It is also the only
	 * window-relative reading there is, because a Wayland window's global bounds are not visible to
	 * the app: the portal reports a window's size but never its position.
	 *
	 * <p>Called from the capture helper's stderr thread, so implementations must be thread-safe.
	 * A backend that cannot measure the pointer this way simply never calls it.
	 */
	default void onCaptureCursorPoint(double cx, double cy) {
	}

	/**
	 * What the capture stream ended up recording ({@code SOURCE monitor|window}), reported once
	 * before the first frame. On a portal desktop the user picks in the desktop's dialog, so a
	 * "screen" selection can become a window: this is how the cursor mapper learns that the global
	 * pointer no longer maps onto the recorded frame.
	 *
	 * <p>Called from the capture helper's stderr thread; implementations must be thread-safe.
	 */
	default void onCaptureSurface(WindowSources.CaptureSurface surface) {
	}

	/**
	 * Takes the ink drawn on screen during the recording (the pen is pointer input too), clearing
	 * it. A source without a live pen has none.
	 */
	default InkTelemetry.InkRecording takeRecordedInk() {
		return InkTelemetry.InkRecording.EMPTY;
	}

	/**
	 * The mic gate of the recording in progress: the recorder configures it at start from the
	 * push-to-talk option and the audio drain reads it. A source without a gate reports null.
	 */
	default @Nullable MicGate micGate() {
		return null;
	}

	/**
	 * Takes the mic's muted stretches of the recording (push-to-talk), clearing them. A source
	 * without a gate has none.
	 */
	default PushToTalkTelemetry.Recording takeMicMuteSpans() {
		return PushToTalkTelemetry.Recording.EMPTY;
	}
}
