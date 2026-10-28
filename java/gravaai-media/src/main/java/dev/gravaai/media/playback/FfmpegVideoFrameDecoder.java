package dev.gravaai.media.playback;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.media.ffmpeg.DecodedFrame;
import dev.gravaai.media.ffmpeg.FrameConverter;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.media.ffmpeg.VideoDecoder;

/**
 * {@link VideoFrameDecoder} backed by {@code VideoDecoder.seekPrecise} + {@code FrameConverter}
 * (plan §5.7/§5.9, software decode only). The converter's reusable swscale buffer is copied into a
 * per-frame direct buffer because the decode-ahead ring keeps frames alive.
 */
public final class FfmpegVideoFrameDecoder implements VideoFrameDecoder {

	private static final System.Logger LOG =
			System.getLogger(FfmpegVideoFrameDecoder.class.getName());
	private static final int MAX_POOLED_BUFFERS = 8;

	private final Path path;
	/** Opened lazily on the decode worker thread: FFM confined arenas belong to their opener. */
	private VideoDecoder decoder;
	private final double durationSeconds;
	private FrameConverter converter;
	private boolean closed;
	/** Recycled per-frame direct buffers so steady-state decoding allocates nothing. */
	private final java.util.Deque<ByteBuffer> pooledBuffers = new java.util.ArrayDeque<>();
	private int pooledBufferBytes;
	private boolean loggedFirstFrame;

	public FfmpegVideoFrameDecoder(Path path) {
		if (path == null) {
			throw new IllegalArgumentException("path must not be null");
		}
		this.path = path;
		if (!Files.isReadable(path)) {
			throw new IllegalArgumentException("not a readable file: " + path);
		}
		double duration;
		try {
			MediaInfo info = MediaProbe.probe(path);
			duration = info.durationSeconds();
		} catch (RuntimeException error) {
			duration = 0;
		}
		this.durationSeconds = Double.isFinite(duration) && duration > 0 ? duration : 0;
	}

	@Override
	public void seek(double seconds) {
		ensureOpen();
		long timeUs = Math.max(0, Math.round((Double.isFinite(seconds) ? seconds : 0) * 1_000_000));
		decoder.seekPrecise(timeUs);
	}

	@Override
	public VideoFrame next() {
		ensureOpen();
		DecodedFrame frame;
		do {
			frame = decoder.next();
			if (frame == null) {
				return null;
			}
		} while (frame.planes().isEmpty());
		if (converter == null || converter.width() != frame.width() || converter.height() != frame.height()) {
			if (converter != null) {
				converter.close();
			}
			converter = new FrameConverter(
					frame.width(), frame.height(), FrameConverter.ScaleMode.PREVIEW, null);
		}
		ByteBuffer converted = converter.convert(frame);
		int bytes = frame.width() * frame.height() * 4;
		ByteBuffer copy = acquireBuffer(bytes);
		ByteBuffer source = converted.duplicate();
		source.position(0);
		source.limit(Math.min(bytes, source.capacity()));
		copy.clear();
		copy.put(source);
		copy.flip();
		if (!loggedFirstFrame) {
			loggedFirstFrame = true;
			LOG.log(System.Logger.Level.INFO,
					"first decoded frame: {0}x{1} ptsUs={2} bytes={3} swscaleCapacity={4} pooled={5}",
					frame.width(), frame.height(), frame.ptsUs(), bytes, converted.capacity(),
					pooledBuffers.size());
		}
		return new VideoFrame(frame.width(), frame.height(), Math.max(0, frame.ptsUs()), copy,
				() -> recycleBuffer(copy, bytes));
	}

	private ByteBuffer acquireBuffer(int bytes) {
		synchronized (pooledBuffers) {
			if (pooledBufferBytes != bytes) {
				pooledBuffers.clear();
				pooledBufferBytes = bytes;
			}
			ByteBuffer pooled = pooledBuffers.pollFirst();
			if (pooled != null) {
				return pooled;
			}
		}
		return ByteBuffer.allocateDirect(bytes);
	}

	private void recycleBuffer(ByteBuffer buffer, int bytes) {
		if (buffer == null || bytes != pooledBufferBytes) {
			return;
		}
		synchronized (pooledBuffers) {
			if (pooledBuffers.size() < MAX_POOLED_BUFFERS) {
				buffer.clear();
				pooledBuffers.addLast(buffer);
			}
		}
	}

	@Override
	public double durationSeconds() {
		return durationSeconds;
	}

	/**
	 * Opens the FFmpeg decoder on the calling (decode worker) thread. Opening in the constructor
	 * would bind the decoder's confined arena to the FX thread that calls setMedia.
	 */
	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("decoder is closed: " + path);
		}
		if (decoder == null) {
			decoder = VideoDecoder.open(path);
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
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
