package dev.gravaai.ui.editor;

import dev.gravaai.core.event.UiEvents;

/**
 * Callback seam for the playback engine (plan §5.9, being built by the recording teammate).
 *
 * <p>The controls never touch a decoder: they read {@link #state()}, publish commands through the
 * methods and re-render on {@link #changes()}. The real engine will publish state changes from its
 * own thread; the shell hops to the FX thread.
 */
public interface EditorPlaybackPort {

	/** Snapshot of what the playback bar needs. */
	record PlaybackState(
			boolean isPlaying, double currentTimeSeconds, double durationSeconds, double volume) {

		public static final PlaybackState IDLE = new PlaybackState(false, 0, 0, 1);
	}

	PlaybackState state();

	/** State changes published by the engine (audio clock master, seeks, volume). */
	UiEvents<PlaybackState> changes();

	void togglePlayPause();

	void seek(double seconds);

	/** Skip back/forward buttons; the engine decides the step (usually a few seconds). */
	void skip(double deltaSeconds);

	void setVolume(double volume);
}
