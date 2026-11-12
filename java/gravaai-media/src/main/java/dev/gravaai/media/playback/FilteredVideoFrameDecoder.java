package dev.gravaai.media.playback;

import java.util.function.DoubleUnaryOperator;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.compose.WebcamFrameFilter;

/**
 * Runs a {@link WebcamFrameFilter} on every frame a decoder produces, on the decoder's own thread
 * (the decode-ahead worker), before the frame enters the ring. The preview therefore shows the
 * filtered frame at full frame rate without the render thread doing any of the work, and with the
 * frame-exact result the export computes.
 */
public final class FilteredVideoFrameDecoder implements VideoFrameDecoder {

	private static final System.Logger LOG =
			System.getLogger(FilteredVideoFrameDecoder.class.getName());

	private final VideoFrameDecoder decoder;
	private final WebcamFrameFilter filter;
	private final DoubleUnaryOperator timelineSecondsOf;

	/**
	 * @param timelineSecondsOf maps a frame's media time to the timeline time it shows at
	 *        (per-segment settings); the filter is closed with the decoder
	 */
	public FilteredVideoFrameDecoder(VideoFrameDecoder decoder, WebcamFrameFilter filter,
			DoubleUnaryOperator timelineSecondsOf) {
		this.decoder = decoder;
		this.filter = filter;
		this.timelineSecondsOf = timelineSecondsOf;
	}

	@Override
	public void seek(double seconds) {
		decoder.seek(seconds);
	}

	@Override
	public @Nullable VideoFrame next() {
		VideoFrame frame = decoder.next();
		if (frame != null) {
			double seconds = frame.ptsUs() / 1_000_000.0;
			try {
				filter.apply(new RgbaFrame(frame.width(), frame.height(), frame.rgbaTopFirst()),
						seconds, timelineSecondsOf.applyAsDouble(seconds));
			} catch (RuntimeException error) {
				// A filter failure shows the frame as decoded; it never stops playback.
				LOG.log(System.Logger.Level.WARNING, "webcam frame filter failed: " + error);
			}
		}
		return frame;
	}

	@Override
	public double durationSeconds() {
		return decoder.durationSeconds();
	}

	@Override
	public void close() {
		try {
			filter.close();
		} finally {
			decoder.close();
		}
	}
}
