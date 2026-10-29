package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * One opened media file for the playback engine: the frame whose pts is at or before a source time.
 *
 * <p>Implemented in the decoder increment by a wraparound of {@code VideoDecoder.seekPrecise} + the
 * decode-ahead ring; tests provide fake sources. Returned {@link RgbaFrame}s are decoder-owned and
 * remain valid only until the next {@link #frameAt} call.
 */
public interface FrameSource extends AutoCloseable {

	/** The frame at or before {@code sourceSeconds}, or null when the media has no such frame. */
	@Nullable RgbaFrame frameAt(double sourceSeconds);

	/**
	 * Like {@link #frameAt(double)} but with an explicit wait budget, so first-frame/seek paths can
	 * fail fast instead of blocking a caller (PlaybackEngine.setMedia) for the full ring budget.
	 * Sources that cannot bound their work fall back to {@link #frameAt(double)}.
	 */
	default @Nullable RgbaFrame frameAt(double sourceSeconds, long timeoutMs) {
		return frameAt(sourceSeconds);
	}

	/** The media duration in seconds; 0 when unknown. */
	double durationSeconds();

	@Override
	void close();
}
