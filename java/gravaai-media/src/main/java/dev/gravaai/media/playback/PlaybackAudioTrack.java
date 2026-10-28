package dev.gravaai.media.playback;

/**
 * The audio side of the preview engine (plan §5.9): decodes/filters (atempo for clip speed regions)
 * and writes stereo float32 48 kHz PCM to the output, exposing the counters the audio-master clock
 * needs. The engine pumps it from the render tick; the production implementation lands with the
 * audio decode increment.
 */
public interface PlaybackAudioTrack extends AutoCloseable {

	/** Rebases the audio pipeline to a timeline position (also flushes the output). */
	void seek(double timelineSeconds);

	/** Decodes/filters/writes ahead; called on the render tick while playing. */
	void pump();

	/** Frames written since the last seek/pause (the clock's sample counter). */
	long samplesWritten();

	/** Output latency in microseconds ({@code pa_simple_get_latency}). */
	int latencyMicros();

	/** Stops writing (pauses the output) without closing. */
	void pause();

	/** Lets {@link #pump()} write again after {@link #pause()}; the engine calls it on play. */
	default void resume() {
	}

	/** Playback rate; the track applies atempo so pitch is preserved. */
	default void setRate(double rate) {
	}

	default void setVolume(double volume) {
	}

	@Override
	void close();
}
