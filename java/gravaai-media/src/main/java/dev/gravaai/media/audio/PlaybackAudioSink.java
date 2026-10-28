package dev.gravaai.media.audio;

/**
 * A stereo float32 48 kHz playback sink (plan §5.9: libpulse-simple output for the preview audio
 * clock). Implemented by {@link PulsePlaybackStream}; tests use fakes.
 */
public interface PlaybackAudioSink extends AutoCloseable {

	/** Output sample rate. */
	int SAMPLE_RATE = 48_000;
	/** Output channels (interleaved). */
	int CHANNELS = 2;
	/** Interleaved stereo frames in one 40 ms write (the preview pump chunk). */
	int DEFAULT_CHUNK_FRAMES = SAMPLE_RATE / 25;
	/** Target server buffer length in bytes: 40 ms of float32 stereo (plan §5.9). */
	int TLENGTH_BYTES = DEFAULT_CHUNK_FRAMES * CHANNELS * Float.BYTES;

	/** Writes 48 kHz interleaved stereo float frames; blocks while the server buffer drains. */
	void write(float[] interleavedStereo, int frameCount);

	/** Server-side latency in microseconds ({@code pa_simple_get_latency}). */
	int latencyMicros();

	/** Drops buffered audio (seek/pause; {@code pa_simple_flush}). */
	void flush();

	@Override
	void close();
}
