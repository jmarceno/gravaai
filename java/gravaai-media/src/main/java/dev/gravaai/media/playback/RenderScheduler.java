package dev.gravaai.media.playback;

/**
 * Drives the playback render tick (plan §5.9: target 60 Hz, no vsync offscreen). The production
 * scheduler is a platform thread; tests use a manual scheduler so every step is deterministic.
 */
public interface RenderScheduler extends AutoCloseable {

	/** Starts ticking while playing; a second call replaces the running tick. */
	void start(Runnable tick);

	/** Stops ticking; the engine pauses. */
	void stop();

	@Override
	default void close() {
		stop();
	}
}
