package dev.gravaai.media.playback;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import dev.gravaai.media.ffmpeg.MediaProbe;

/**
 * The decoder-backed {@link FrameSourceProvider} (plan §5.9): opens one
 * {@link FfmpegVideoFrameDecoder} + decode-ahead ring per media path. The opener is injectable so
 * the ring is testable with fake decoders.
 */
public final class DecoderFrameSourceProvider implements FrameSourceProvider {

	private final Function<String, VideoFrameDecoder> opener;
	private final int ringCapacity;
	/** Wraps each opened decoder (per media path); identity unless {@link #withDecoratedDecoders}. */
	private volatile BiFunction<String, VideoFrameDecoder, VideoFrameDecoder> decorator =
			(path, decoder) -> decoder;

	public DecoderFrameSourceProvider(Function<String, VideoFrameDecoder> opener) {
		this(opener, DecoderFrameSource.DEFAULT_RING_CAPACITY);
	}

	public DecoderFrameSourceProvider(Function<String, VideoFrameDecoder> opener, int ringCapacity) {
		this.opener = Objects.requireNonNull(opener, "opener");
		this.ringCapacity = Math.max(1, ringCapacity);
	}

	/** Production provider: FFmpeg software decoder + swscale, 4-frame ring. */
	public static DecoderFrameSourceProvider ffmpegBacked() {
		return new DecoderFrameSourceProvider(
				file -> new FfmpegVideoFrameDecoder(Path.of(file)), DecoderFrameSource.DEFAULT_RING_CAPACITY);
	}

	/**
	 * Wraps every decoder opened from now on (the preview corrects the webcam's eyes this way).
	 * The decorator runs on the decode worker that will own the decoder.
	 */
	public DecoderFrameSourceProvider withDecoratedDecoders(
			BiFunction<String, VideoFrameDecoder, VideoFrameDecoder> decorator) {
		this.decorator = Objects.requireNonNull(decorator, "decorator");
		return this;
	}

	@Override
	public FrameSource open(String mediaPath) {
		// The decoder itself is opened inside DecoderFrameSource's worker: the FFmpeg FFM arena is
		// thread-confined and setMedia runs on the FX thread.
		return DecoderFrameSource.open(
				() -> decorator.apply(mediaPath, opener.apply(mediaPath)), probeDurationSeconds(mediaPath), ringCapacity);
	}

	/** Probes the media duration on the caller thread (MediaProbe owns and closes its own arena). */
	private static double probeDurationSeconds(String mediaPath) {
		try {
			return MediaProbe.probe(Path.of(mediaPath)).durationSeconds();
		} catch (RuntimeException error) {
			return 0;
		}
	}
}
