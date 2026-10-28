package dev.gravaai.media.playback;

/**
 * Sequential video decoding behind the decode-ahead ring (plan §5.9). The production adapter wraps
 * {@code VideoDecoder.seekPrecise} + {@code FrameConverter}; tests fake it.
 */
public interface VideoFrameDecoder extends AutoCloseable {

	/** Seeks to the previous keyframe and decodes up to the first frame at/after {@code seconds}. */
	void seek(double seconds);

	/** The next frame after the last returned/seeked one, or null at end of file. */
	VideoFrame next();

	/** Media duration in seconds, 0 when unknown. */
	double durationSeconds();

	@Override
	void close();
}
