package dev.gravaai.media.ffmpeg;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.media.ffmpeg.gen.AVCodecContext;
import dev.gravaai.media.ffmpeg.gen.AVCodecParameters;
import dev.gravaai.media.ffmpeg.gen.AVFormatContext;
import dev.gravaai.media.ffmpeg.gen.AVFrame;
import dev.gravaai.media.ffmpeg.gen.AVPacket;
import dev.gravaai.media.ffmpeg.gen.AVStream;
import dev.gravaai.media.ffmpeg.gen.ffmpeg_h;

/**
 * Decodes a video stream in-process through libavcodec (plan §5.7).
 *
 * <ul>
 *   <li>{@link #seekPrecise(long)} seeks with {@code AVSEEK_FLAG_BACKWARD} to the previous
 *       keyframe and then decodes forward to the first frame whose pts is at or after the
 *       target, so the next {@link #next()} returns exactly that frame.</li>
 *   <li>Decoding uses {@code thread_count = 0} (auto) and {@code thread_type = FF_THREAD_FRAME},
 *       all software (plan §1.1.1: never a hardware decoder).</li>
 *   <li>One {@code AVFrame} and one {@code AVPacket} are reused for the lifetime of the
 *       decoder; see {@link DecodedFrame} for the buffer lifetime contract.</li>
 * </ul>
 */
public final class VideoDecoder implements AutoCloseable {

	private static final System.Logger LOG = System.getLogger(VideoDecoder.class.getName());
	private static final long NOPTS = ffmpeg_h.AV_NOPTS_VALUE();

	private final Path path;
	private final Arena arena;
	private final MemorySegment formatContextPtr;
	private final MemorySegment formatContext;
	private final MemorySegment codecContextPtr;
	private final MemorySegment codecContext;
	private final MemorySegment framePtr;
	private final MemorySegment frame;
	private final MemorySegment packetPtr;
	private final MemorySegment packet;
	private final MemorySegment streamTimeBase;
	private final MemorySegment avTimeBase;
	private final int videoStreamIndex;
	private final long[] ptsScratch;
	private boolean flushSent;
	private boolean closed;
	/** The frame buffered by {@link #seekPrecise(long)}; {@code null} when none is buffered. */
	private @Nullable DecodedFrame pending;
	private long lastPtsUs = Long.MIN_VALUE;

	private VideoDecoder(Path path, Arena arena, MemorySegment formatContextPtr,
			MemorySegment formatContext, MemorySegment codecContextPtr, MemorySegment codecContext,
			MemorySegment framePtr, MemorySegment frame, MemorySegment packetPtr,
			MemorySegment packet, MemorySegment streamTimeBase, MemorySegment avTimeBase,
			int videoStreamIndex) {
		this.path = path;
		this.arena = arena;
		this.formatContextPtr = formatContextPtr;
		this.formatContext = formatContext;
		this.codecContextPtr = codecContextPtr;
		this.codecContext = codecContext;
		this.framePtr = framePtr;
		this.frame = frame;
		this.packetPtr = packetPtr;
		this.packet = packet;
		this.streamTimeBase = streamTimeBase;
		this.avTimeBase = avTimeBase;
		this.videoStreamIndex = videoStreamIndex;
		this.ptsScratch = new long[1];
	}

	/**
	 * Opens the best video stream of a file and initialises the decoder.
	 *
	 * @throws FfmpegException when the file has no video or the codec cannot be opened
	 */
	public static VideoDecoder open(Path file) {
		FfmpegLibs.ensureLoaded();
		Path path = file.toAbsolutePath().normalize();
		if (!Files.isReadable(path)) {
			throw new IllegalArgumentException("not a readable file: " + path);
		}
		Arena arena = Arena.ofConfined();
		try {
			MemorySegment formatContextPtr = arena.allocate(ValueLayout.ADDRESS);
			formatContextPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
			int opened = ffmpeg_h.avformat_open_input(formatContextPtr,
					FfmpegLibs.cString(arena, path.toString()), MemorySegment.NULL, MemorySegment.NULL);
			if (opened < 0) {
				throw new FfmpegException("avformat_open_input(" + path.getFileName() + ")", opened);
			}
			MemorySegment formatContext = AVFormatContext.reinterpret(
					formatContextPtr.get(ValueLayout.ADDRESS, 0), arena, null);
			FfmpegException.check(
					ffmpeg_h.avformat_find_stream_info(formatContext, MemorySegment.NULL),
					"avformat_find_stream_info");

			int streamIndex = ffmpeg_h.av_find_best_stream(
					formatContext, ffmpeg_h.AVMEDIA_TYPE_VIDEO(), -1, -1, MemorySegment.NULL, 0);
			if (streamIndex < 0) {
				throw new FfmpegException("av_find_best_stream(video) on " + path.getFileName(),
						streamIndex);
			}
			MemorySegment streams = AVFormatContext.streams(formatContext)
					.reinterpret((long) AVFormatContext.nb_streams(formatContext)
							* ValueLayout.ADDRESS.byteSize());
			MemorySegment stream = AVStream.reinterpret(
					streams.getAtIndex(ValueLayout.ADDRESS, streamIndex), arena, null);
			MemorySegment codecParameters = AVCodecParameters.reinterpret(
					AVStream.codecpar(stream), arena, null);
			MemorySegment decoder = ffmpeg_h.avcodec_find_decoder(
					AVCodecParameters.codec_id(codecParameters));
			if (decoder.address() == 0) {
				throw new FfmpegException("avcodec_find_decoder("
						+ AVCodecParameters.codec_id(codecParameters) + ")", -1);
			}
			MemorySegment codecContextPtr = arena.allocate(ValueLayout.ADDRESS);
			MemorySegment codecContext = AVCodecContext.reinterpret(
					ffmpeg_h.avcodec_alloc_context3(decoder), arena, null);
			if (codecContext.address() == 0) {
				throw new IllegalStateException("avcodec_alloc_context3 returned NULL");
			}
			codecContextPtr.set(ValueLayout.ADDRESS, 0, codecContext);
			FfmpegException.check(
					ffmpeg_h.avcodec_parameters_to_context(codecContext, codecParameters),
					"avcodec_parameters_to_context");
			// Plan §5.7: auto thread count, frame threading.
			AVCodecContext.thread_count(codecContext, 0);
			AVCodecContext.thread_type(codecContext, ffmpeg_h.FF_THREAD_FRAME());
			FfmpegException.check(
					ffmpeg_h.avcodec_open2(codecContext, decoder, MemorySegment.NULL),
					"avcodec_open2");

			MemorySegment frame = AVFrame.reinterpret(ffmpeg_h.av_frame_alloc(), arena, null);
			if (frame.address() == 0) {
				throw new IllegalStateException("av_frame_alloc returned NULL");
			}
			MemorySegment framePtr = arena.allocate(ValueLayout.ADDRESS);
			framePtr.set(ValueLayout.ADDRESS, 0, frame);
			MemorySegment packet = AVPacket.reinterpret(ffmpeg_h.av_packet_alloc(), arena, null);
			if (packet.address() == 0) {
				throw new IllegalStateException("av_packet_alloc returned NULL");
			}
			MemorySegment packetPtr = arena.allocate(ValueLayout.ADDRESS);
			packetPtr.set(ValueLayout.ADDRESS, 0, packet);

			MemorySegment avTimeBase = AVRationalAllocator.allocate(arena, 1,
					ffmpeg_h.AV_TIME_BASE());
			VideoDecoder decoderHandle = new VideoDecoder(path, arena, formatContextPtr,
					formatContext, codecContextPtr, codecContext, framePtr, frame, packetPtr,
					packet, AVStream.time_base(stream), avTimeBase, streamIndex);
			LOG.log(System.Logger.Level.DEBUG, () -> "opened video decoder for "
					+ path.getFileName() + " stream " + streamIndex);
			return decoderHandle;
		} catch (RuntimeException failure) {
			arena.close();
			throw failure;
		}
	}

	/** The container stream index this decoder reads. */
	public int streamIndex() {
		return videoStreamIndex;
	}

	/** The rotation from the stream's display matrix, normalised to [0, 360). */
	public int rotationDegrees() {
		ensureOpen();
		MemorySegment stream = streamContext();
		return DisplayMatrix.rotationDegrees(arena, AVStream.codecpar(stream));
	}

	/** The container time base, for callers that work in stream timestamps. */
	public long streamTimeBaseDen() {
		return dev.gravaai.media.ffmpeg.gen.AVRational.den(streamTimeBase);
	}

	/** Seeks to {@code timeUs} and positions the next {@link #next()} on the target frame. */
	public void seekPrecise(long timeUs) {
		ensureOpen();
		if (timeUs < 0) {
			throw new IllegalArgumentException("negative seek time: " + timeUs);
		}
		int result = ffmpeg_h.av_seek_frame(
				formatContext, -1, timeUs, ffmpeg_h.AVSEEK_FLAG_BACKWARD());
		FfmpegException.check(result, "av_seek_frame");
		ffmpeg_h.avformat_flush(formatContext);
		ffmpeg_h.avcodec_flush_buffers(codecContext);
		flushSent = false;
		pending = null;
		lastPtsUs = Long.MIN_VALUE;
		DecodedFrame frame = decodeNext();
		while (frame != null && frame.ptsUs() + toleranceUs(frame) < timeUs) {
			frame = decodeNext();
		}
		pending = frame;
	}

	/**
	 * Decodes the next frame, or returns {@code null} at end of stream. The returned frame's
	 * buffers stay valid until the next call.
	 */
	public @Nullable DecodedFrame next() {
		ensureOpen();
		DecodedFrame frame = pending;
		pending = null;
		if (frame != null) {
			return frame;
		}
		return decodeNext();
	}

	private @Nullable DecodedFrame decodeNext() {
		while (true) {
			int result = ffmpeg_h.avcodec_receive_frame(codecContext, frame);
			if (result == 0) {
				return snapshotFrame();
			}
			if (result == FfmpegException.AVERROR_EOF) {
				return null;
			}
			if (result != FfmpegException.AVERROR_EAGAIN) {
				throw new FfmpegException("avcodec_receive_frame", result);
			}
			boolean flushWasSent = flushSent;
			boolean fed = feedPacket();
			if (!fed && flushWasSent) {
				return null;
			}
		}
	}

	/** Feeds one packet of our stream; false when the input is exhausted. */
	private boolean feedPacket() {
		while (true) {
			int read = ffmpeg_h.av_read_frame(formatContext, packet);
			if (read == FfmpegException.AVERROR_EOF) {
				return sendFlushPacket();
			}
			FfmpegException.check(read, "av_read_frame");
			if (AVPacket.stream_index(packet) != videoStreamIndex) {
				ffmpeg_h.av_packet_unref(packet);
				continue;
			}
			int sent = ffmpeg_h.avcodec_send_packet(codecContext, packet);
			ffmpeg_h.av_packet_unref(packet);
			if (sent == FfmpegException.AVERROR_EAGAIN) {
				return true;
			}
			if (sent == FfmpegException.AVERROR_EOF) {
				return false;
			}
			FfmpegException.check(sent, "avcodec_send_packet");
			return true;
		}
	}

	private boolean sendFlushPacket() {
		if (flushSent) {
			return false;
		}
		flushSent = true;
		int sent = ffmpeg_h.avcodec_send_packet(codecContext, MemorySegment.NULL);
		if (sent < 0 && sent != FfmpegException.AVERROR_EOF) {
			throw new FfmpegException("avcodec_send_packet(flush)", sent);
		}
		return true;
	}

	private DecodedFrame snapshotFrame() {
		int width = AVFrame.width(frame);
		int height = AVFrame.height(frame);
		int pixelFormat = AVFrame.format(frame);
		long bestTimestamp = AVFrame.best_effort_timestamp(frame);
		if (bestTimestamp == NOPTS) {
			bestTimestamp = AVFrame.pts(frame);
		}
		long ptsUs = bestTimestamp == NOPTS ? NOPTS : rescaleToMicros(bestTimestamp);
		long duration = AVFrame.duration(frame);
		long durationUs = duration > 0 ? rescaleToMicros(duration) : 0;
		if (ptsUs != NOPTS && lastPtsUs != Long.MIN_VALUE) {
			// Keep the timeline monotonic; some containers emit duplicate timestamps.
			ptsUs = Math.max(ptsUs, lastPtsUs);
		}
		if (ptsUs != NOPTS) {
			lastPtsUs = ptsUs;
		}
		MemorySegment nativeFrame = frame;
		int[] lineSizes = new int[(int) (AVFrame.linesize$layout().byteSize() / ValueLayout.JAVA_INT.byteSize())];
		for (int i = 0; i < lineSizes.length; i++) {
			lineSizes[i] = AVFrame.linesize(frame, i);
		}
		List<DecodedFrame.Plane> planes = buildPlanes(nativeFrame, width, height, pixelFormat, lineSizes);
		return new DecodedFrame(ptsUs, durationUs, width, height, pixelFormat, planes, nativeFrame,
				lineSizes);
	}

	private List<DecodedFrame.Plane> buildPlanes(MemorySegment frame, int width, int height,
			int pixelFormat, int[] lineSizes) {
		int planeCount = Math.max(1, ffmpeg_h.av_pix_fmt_count_planes(pixelFormat));
		int chromaShiftWidth = 0;
		int chromaShiftHeight = 0;
		MemorySegment descriptor = ffmpeg_h.av_pix_fmt_desc_get(pixelFormat);
		if (descriptor.address() != 0) {
			chromaShiftWidth = dev.gravaai.media.ffmpeg.gen.AVPixFmtDescriptor.log2_chroma_w(descriptor);
			chromaShiftHeight = dev.gravaai.media.ffmpeg.gen.AVPixFmtDescriptor.log2_chroma_h(descriptor);
		}
		List<DecodedFrame.Plane> planes = new ArrayList<>(planeCount);
		for (int i = 0; i < planeCount; i++) {
			MemorySegment data = AVFrame.data(frame, i);
			int stride = lineSizes[i];
			if (data.address() == 0 || stride <= 0) {
				continue;
			}
			int planeWidth = width;
			int planeHeight = height;
			if (planeCount >= 3 && i > 0) {
				planeWidth = (width + (1 << chromaShiftWidth) - 1) >> chromaShiftWidth;
				planeHeight = (height + (1 << chromaShiftHeight) - 1) >> chromaShiftHeight;
			}
			ByteBuffer buffer = data.reinterpret((long) stride * planeHeight).asByteBuffer();
			planes.add(new DecodedFrame.Plane(i, planeWidth, planeHeight, stride, buffer));
		}
		return planes;
	}

	private long rescaleToMicros(long timestamp) {
		return ffmpeg_h.av_rescale_q(timestamp, streamTimeBase, avTimeBase);
	}

	private long toleranceUs(DecodedFrame frame) {
		return frame.durationUs() > 0 ? frame.durationUs() / 2 : 0;
	}

	private MemorySegment streamContext() {
		MemorySegment streams = AVFormatContext.streams(formatContext)
				.reinterpret((long) AVFormatContext.nb_streams(formatContext)
						* ValueLayout.ADDRESS.byteSize());
		return AVStream.reinterpret(streams.getAtIndex(ValueLayout.ADDRESS, videoStreamIndex),
				arena, null);
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("decoder for " + path.getFileName() + " is closed");
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			ffmpeg_h.av_frame_free(framePtr);
			ffmpeg_h.av_packet_free(packetPtr);
			ffmpeg_h.avcodec_free_context(codecContextPtr);
			ffmpeg_h.avformat_close_input(formatContextPtr);
		} finally {
			arena.close();
		}
	}

	/** Allocates an {@code AVRational} with the given numerator and denominator. */
	private static final class AVRationalAllocator {
		private AVRationalAllocator() {}

		static MemorySegment allocate(Arena arena, int numerator, int denominator) {
			MemorySegment rational = dev.gravaai.media.ffmpeg.gen.AVRational.allocate(arena);
			dev.gravaai.media.ffmpeg.gen.AVRational.num(rational, numerator);
			dev.gravaai.media.ffmpeg.gen.AVRational.den(rational, denominator);
			return rational;
		}
	}
}
