package dev.gravaai.media.audio;

/**
 * A blocking PulseAudio record stream delivering mono float32 at 48 kHz (plan §5.6.9, §5.9).
 *
 * <p>The recorder's mic level meter and the (future) preview audio path consume this; the level
 * maths itself lives in the recording service. Frames arrive as float32 samples in [-1, 1] at
 * the rate of the PulseAudio server clock.</p>
 */
public interface PulseRecordStream extends AutoCloseable {

	/**
	 * Blocks until {@code mono48k.length} samples have been captured.
	 *
	 * @param mono48k the destination, 48 kHz mono float32
	 * @return the number of samples written (the full array length), or -1 when the stream ended
	 * @throws IllegalStateException when PulseAudio reports an error
	 */
	int read(float[] mono48k);

	@Override
	void close();
}
