package dev.gravaai.ui.editor.preview;

import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

import dev.gravaai.core.event.UiEvents;
import dev.gravaai.media.playback.PlaybackController;
import dev.gravaai.media.playback.PlaybackStatus;
import dev.gravaai.ui.editor.EditorPlaybackPort;
import javafx.application.Platform;

/**
 * Adapts the media playback engine to the editor's {@link EditorPlaybackPort} (plan §5.9,
 * P2.5/P2.11). {@code AppContext} constructs the engine and this adapter; the
 * {@code PlaybackControls} bar keeps talking to {@link EditorPlaybackPort} (no shell change).
 *
 * <p>Threading and first-preview: {@link #state()} serves a CACHED snapshot and never takes the
 * engine lock, so a busy {@code setMedia} cannot freeze the FX thread when the transport bar
 * repaints. The cache is refreshed from the engine's {@code onStatus}/{@code onTime} callbacks
 * (which run off-FX, outside the engine lock) and optimistically from the user actions below;
 * the first read happens at startup, before the engine is busy. Actions call the engine, then
 * publish the optimistic state; the next engine event corrects it.
 */
public final class PlaybackControllerPort implements EditorPlaybackPort, AutoCloseable {

	private final PlaybackController controller;
	private final Consumer<Runnable> fxDispatcher;
	private volatile double rate = 1;
	private volatile @Nullable PlaybackState cached;

	private final AutoCloseable statusSubscription;
	private final AutoCloseable timeSubscription;
	private final UiEvents<PlaybackState> changes = new UiEvents<>();

	/** Production constructor: publishes on the FX application thread. */
	public PlaybackControllerPort(PlaybackController controller) {
		this(controller, Platform::runLater);
	}

	/**
	 * @param fxDispatcher runs a publish task on the FX thread; tests pass {@code Runnable::run}
	 */
	public PlaybackControllerPort(PlaybackController controller, Consumer<Runnable> fxDispatcher) {
		if (controller == null || fxDispatcher == null) {
			throw new IllegalArgumentException("controller and fxDispatcher must not be null");
		}
		this.controller = controller;
		this.fxDispatcher = fxDispatcher;
		this.statusSubscription = controller.onStatus(status -> onEngineEvent());
		this.timeSubscription = controller.onTime(time -> onEngineEvent());
	}

	/** The wrapped engine (the preview panel needs it for frames). */
	public PlaybackController controller() {
		return controller;
	}

	@Override
	public PlaybackState state() {
		PlaybackState current = cached;
		if (current == null) {
			current = readEngineStatus();
			cached = current;
		}
		return current;
	}

	@Override
	public UiEvents<PlaybackState> changes() {
		return changes;
	}

	@Override
	public void togglePlayPause() {
		PlaybackState before = state();
		controller.togglePlayPause();
		update(new PlaybackState(!before.isPlaying(), before.currentTimeSeconds(),
				before.durationSeconds(), before.volume()));
	}

	@Override
	public void seek(double seconds) {
		double target = clampToDuration(seconds);
		PlaybackState before = state();
		controller.seek(target);
		update(new PlaybackState(before.isPlaying(), target, before.durationSeconds(),
				before.volume()));
	}

	@Override
	public void skip(double deltaSeconds) {
		seek(state().currentTimeSeconds() + deltaSeconds);
	}

	@Override
	public void setVolume(double nextVolume) {
		double volume = Math.max(0, Math.min(1, nextVolume));
		controller.setVolume(volume);
		PlaybackState before = state();
		update(new PlaybackState(before.isPlaying(), before.currentTimeSeconds(),
				before.durationSeconds(), volume));
	}

	/** Scrubbing: latest-wins at the engine (plan §5.9 {@code scrub}). */
	public void scrub(double seconds) {
		double target = clampToDuration(seconds);
		controller.scrub(target);
		PlaybackState before = state();
		update(new PlaybackState(before.isPlaying(), target, before.durationSeconds(),
				before.volume()));
	}

	/** Playback rate (quarter steps between 0.25x and 30x); pass-through to the engine. */
	public void setRate(double nextRate) {
		rate = nextRate;
		controller.setRate(nextRate);
	}

	public double rate() {
		return rate;
	}

	/** Current playhead in editor-timeline seconds (cached; no engine lock). */
	public double currentTimeSeconds() {
		return state().currentTimeSeconds();
	}

	private double clampToDuration(double seconds) {
		if (!Double.isFinite(seconds)) {
			return 0;
		}
		double duration = state().durationSeconds();
		double upper = duration > 0 ? duration : seconds;
		return Math.max(0, Math.min(upper, seconds));
	}

	private void onEngineEvent() {
		PlaybackState next = readEngineStatus();
		cached = next;
		publish(next);
	}

	private void update(PlaybackState next) {
		cached = next;
		publish(next);
	}

	private void publish(PlaybackState snapshot) {
		fxDispatcher.accept(() -> changes.publish(snapshot));
	}

	private PlaybackState readEngineStatus() {
		try {
			PlaybackStatus status = controller.status();
			return new PlaybackState(
					status.state() == dev.gravaai.media.playback.PlaybackState.PLAYING,
					status.timelineSeconds(), status.durationSeconds(), readVolume());
		} catch (RuntimeException busyEngine) {
			// The engine is starting/stopping; keep a neutral snapshot rather than freezing FX.
			return new PlaybackState(false, 0, 0, 1);
		}
	}

	private double readVolume() {
		try {
			return controller.volume();
		} catch (RuntimeException busyEngine) {
			return state().volume();
		}
	}

	@Override
	public void close() {
		closeQuietly(statusSubscription);
		closeQuietly(timeSubscription);
	}

	private static void closeQuietly(AutoCloseable handle) {
		if (handle == null) {
			return;
		}
		try {
			handle.close();
		} catch (Exception ignored) {
			// closing a listener handle is best-effort
		}
	}
}
