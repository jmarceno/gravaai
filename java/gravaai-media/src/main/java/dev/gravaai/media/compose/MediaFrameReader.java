package dev.gravaai.media.compose;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.media.ffmpeg.ColourInfo;
import dev.gravaai.media.ffmpeg.DecodedFrame;
import dev.gravaai.media.ffmpeg.FrameConverter;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.ffmpeg.VideoDecoder;

/**
 * One lazily opened decoder + converter for a media file; single-threaded by contract. The
 * returned {@link RgbaFrame} uses one of two direct buffers in turn, so it stays valid until the
 * decode after next.
 */
public final class MediaFrameReader implements AutoCloseable {

	private final Path path;
	private final boolean video;
	private double durationSeconds;
	private VideoDecoder decoder;
	private FrameConverter converter;
	private ColourInfo colour;
	// Two output buffers used in turn: the previous frame stays intact while the next one decodes,
	// so the export can decode frame N+1 while the GPU still uploads frame N.
	private final ByteBuffer[] reusableBuffers = new ByteBuffer[2];
	private int reusableIndex;
	private int converterWidth = -1;
	private int converterHeight = -1;
	private RgbaFrame lastFrame;
	private long lastPtsUs;
	private long lastEndUs;

	public MediaFrameReader(Path path, double durationOverrideSeconds, boolean video) {
		this.path = path;
		this.video = video;
		this.durationSeconds = durationOverrideSeconds > 0 ? durationOverrideSeconds : 0;
	}

	public double durationSeconds() {
		if (durationSeconds <= 0) {
			try {
				MediaInfo info = MediaProbe.probe(path);
				durationSeconds = info.durationSeconds();
				if (video && colour == null) {
					colour = info.video().map(stream -> stream.colour()).orElse(null);
				}
			} catch (RuntimeException error) {
				durationSeconds = 0;
			}
		}
		return durationSeconds > 0 ? durationSeconds : 0;
	}

	public RgbaFrame decode(double seconds) {
		if (!Files.isReadable(path)) {
			return null;
		}
		ensureOpen();
		decoder.seekPrecise(Math.max(0, Math.round(seconds * 1_000_000)));
		return convert(decoder.next());
	}

	/**
	 * The frame covering {@code seconds}, decoding forward from the last frame instead of seeking:
	 * sequential callers (looping wallpapers, preview playback) pay one decode per source frame.
	 * Going backwards or jumping more than a second ahead falls back to a precise seek.
	 */
	public RgbaFrame decodeForward(double seconds) {
		long targetUs = Math.max(0, Math.round(seconds * 1_000_000));
		if (lastFrame != null && targetUs >= lastPtsUs && targetUs < lastEndUs) {
			return lastFrame;
		}
		if (lastFrame == null || targetUs < lastPtsUs || targetUs - lastEndUs > 1_000_000) {
			return decode(seconds);
		}
		while (true) {
			DecodedFrame frame = decoder.next();
			if (frame == null) {
				return lastFrame;
			}
			long end = frame.ptsUs() + Math.max(1, frame.durationUs());
			if (end > targetUs) {
				return convert(frame);
			}
		}
	}

	private RgbaFrame convert(DecodedFrame frame) {
		if (frame == null) {
			lastFrame = null;
			return null;
		}
		if (converter == null || converterWidth != frame.width()
				|| converterHeight != frame.height()) {
			if (converter != null) {
				converter.close();
			}
			converter = new FrameConverter(frame.width(), frame.height(),
					FrameConverter.ScaleMode.PREVIEW, colour);
			converterWidth = frame.width();
			converterHeight = frame.height();
		}
		ByteBuffer converted = converter.convert(frame);
		int bytes = frame.width() * frame.height() * 4;
		reusableIndex ^= 1;
		ByteBuffer reusable = reusableBuffers[reusableIndex];
		if (reusable == null || reusable.capacity() < bytes) {
			reusable = ByteBuffer.allocateDirect(bytes);
			reusableBuffers[reusableIndex] = reusable;
		}
		ByteBuffer source = converted.duplicate();
		source.position(0);
		source.limit(Math.min(bytes, source.capacity()));
		reusable.clear();
		reusable.put(source);
		// SkiaImages copies from position..limit; leave the reusable buffer rewound so the
		// next compositor draw reads exactly the frame we wrote.
		reusable.position(0);
		reusable.limit(bytes);
		lastPtsUs = frame.ptsUs();
		lastEndUs = frame.ptsUs() + Math.max(1, frame.durationUs());
		lastFrame = new RgbaFrame(frame.width(), frame.height(), reusable);
		return lastFrame;
	}

	private void ensureOpen() {
		if (decoder == null) {
			decoder = VideoDecoder.open(path);
			if (colour == null && video) {
				try {
					MediaInfo info = MediaProbe.probe(path);
					if (durationSeconds <= 0) {
						durationSeconds = info.durationSeconds();
					}
					colour = info.video().map(stream -> stream.colour()).orElse(null);
				} catch (RuntimeException error) {
					colour = null;
				}
			}
		}
	}

	@Override
	public void close() {
		if (converter != null) {
			converter.close();
			converter = null;
		}
		if (decoder != null) {
			decoder.close();
			decoder = null;
		}
	}
}
