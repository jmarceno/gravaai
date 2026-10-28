package dev.gravaai.media.playback;

/**
 * The preview playback clock (plan §5.9): the audio clock is master; with no audio track the engine
 * uses a monotonic clock. Positions are timeline seconds.
 */
public interface PlaybackClock {

	/** The current timeline position in seconds. */
	double positionSeconds();

	/** Rebases the clock so the next read is {@code seconds} (seek, flush, restart). */
	void resetTo(double seconds);

	/** Starts/stops advancing; a paused clock keeps its last position. */
	default void setRunning(boolean running) {
	}
}
