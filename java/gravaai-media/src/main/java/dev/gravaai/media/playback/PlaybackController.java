package dev.gravaai.media.playback;

import java.util.function.Consumer;

/**
 * The preview playback seam the editor preview panel consumes (plan §5.9, P2.5).
 *
 * <p>Threading: {@code play/pause/seek/scrub/setRate/status} may be called from the JavaFX thread;
 * frame and status listeners are invoked on the engine's render/audio threads, so the UI adapter
 * hops to the FX thread. {@link FrameConsumer} frames must be consumed before the next decode
 * ({@link PlaybackFrame} documents the buffer lifetime).
 *
 * <p>Wiring contract for {@code ui.editor.preview}:
 * <pre>{@code
 * PlaybackController playback = new PlaybackEngine(new PlaybackEngine.Dependencies(...));
 * playback.setMedia(new PlaybackMedia(videoPath, webcamPath, webcamOffsetMs, clips, durationMs, null));
 * AutoCloseable frameSubscription = playback.onFrame(frame -> compositor.render(inputFor(frame)));
 * playback.onStatus(status -> Platform.runLater(() -> controls.update(status)));
 * playback.play();
 * }</pre>
 */
public interface PlaybackController extends AutoCloseable {

	/** Loads (or replaces) the media and resets the playhead to the timeline start. */
	void setMedia(PlaybackMedia media);

	void play();

	void pause();

	void togglePlayPause();

	/** Precise seek: cancels decode-ahead work and renders the frame at the target. */
	void seek(double timelineSeconds);

	/** Scrubbing: coalesced latest-wins (see {@link ScrubCoalescer}). */
	void scrub(double timelineSeconds);

	/** Sets the playback rate (timeline advances at this rate; source media keeps clip speeds). */
	void setRate(double rate);

	double rate();

	/** Loops back to the timeline start when the playhead reaches the end. */
	default void setLoop(boolean loop) {
	}

	default boolean isLooping() {
		return false;
	}

	/** Preview output volume, 0..1 (applies once the audio output is wired). */
	default void setVolume(double volume) {
	}

	default double volume() {
		return 1;
	}

	boolean isPlaying();

	/** The current snapshot for the preview controls; never null. */
	PlaybackStatus status();

	/** Subscribes to decoded frames. Close the handle to unsubscribe. */
	AutoCloseable onFrame(FrameConsumer consumer);

	/** Subscribes to status changes (play/pause/seek/rate/end). */
	AutoCloseable onStatus(Consumer<PlaybackStatus> listener);

	/** Subscribes to playhead updates. */
	AutoCloseable onTime(Consumer<PlaybackTime> listener);

	@Override
	void close();
}
