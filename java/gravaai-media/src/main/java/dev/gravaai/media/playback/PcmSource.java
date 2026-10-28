package dev.gravaai.media.playback;

/**
 * Decoded 48 kHz interleaved stereo float PCM for one audio segment (plan §5.9). Implemented by
 * {@link FfmpegCliPcmSource}; tests fake it.
 */
public interface PcmSource extends AutoCloseable {

	/** Reads up to {@code frameCount} stereo frames; returns frames written, or -1 at end. */
	int read(float[] interleavedStereo, int frameCount);

	@Override
	void close();
}
