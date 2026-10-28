package dev.gravaai.media.ffmpeg;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.gravaai.media.ffmpeg.gen.AVChannelLayout;
import dev.gravaai.media.ffmpeg.gen.AVCodecContext;
import dev.gravaai.media.ffmpeg.gen.AVCodecParameters;
import dev.gravaai.media.ffmpeg.gen.AVFormatContext;
import dev.gravaai.media.ffmpeg.gen.AVFrame;
import dev.gravaai.media.ffmpeg.gen.AVPacket;
import dev.gravaai.media.ffmpeg.gen.AVStream;
import dev.gravaai.media.ffmpeg.gen.ffmpeg_h;

/**
 * Decodes the best audio stream of a file to interleaved float32, 48 kHz, stereo through
 * swresample (plan §5.7).
 *
 * <p>This is the decoder the playback engine and the export audio path use; libpulse playback
 * itself lives in {@code dev.gravaai.media.audio} (plan §5.9). The decoder reuses one
 * {@code AVFrame}, one {@code AVPacket} and one staging buffer, so a caller can stream a whole
 * file with a single small float array.</p>
 */
public final class AudioDecoder implements AutoCloseable {

	/** Output sample rate required by the playback and export paths. */
	public static final int OUTPUT_SAMPLE_RATE = 48000;

	/** Output channel count (stereo, interleaved). */
	public static final int OUTPUT_CHANNELS = 2;

	private static final int STAGING_FRAMES = 16384;
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
	private final MemorySegment swrPtr;
	private final MemorySegment outputLayout;
	private final MemorySegment staging;
	private final MemorySegment outputPointerArray;
	private final int audioStreamIndex;
	private MemorySegment swr;
	private int inputSampleFormat = Integer.MIN_VALUE;
	private int inputSampleRate = Integer.MIN_VALUE;
	private int stagingStart;
	private int stagingCount;
	private boolean flushSent;
	private boolean eof;
	private boolean closed;

	private AudioDecoder(Path path, Arena arena, MemorySegment formatContextPtr,
			MemorySegment formatContext, MemorySegment codecContextPtr,
			MemorySegment codecContext, MemorySegment framePtr, MemorySegment frame,
			MemorySegment packetPtr, MemorySegment packet, MemorySegment swrPtr,
			MemorySegment outputLayout, int audioStreamIndex) {
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
		this.swrPtr = swrPtr;
		this.outputLayout = outputLayout;
		this.audioStreamIndex = audioStreamIndex;
		this.staging = arena.allocate((long) STAGING_FRAMES * OUTPUT_CHANNELS
				* ValueLayout.JAVA_FLOAT.byteSize());
		this.outputPointerArray = arena.allocate(ValueLayout.ADDRESS.byteSize());
		this.outputPointerArray.setAtIndex(ValueLayout.ADDRESS, 0, staging);
	}

	/**
	 * Opens the best audio stream of a file.
	 *
	 * @throws FfmpegException when the file has no audio or the codec cannot be opened
	 */
	public static AudioDecoder open(Path file) {
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
					FfmpegLibs.cString(arena, path.toString()), MemorySegment.NULL,
					MemorySegment.NULL);
			if (opened < 0) {
				throw new FfmpegException("avformat_open_input(" + path.getFileName() + ")", opened);
			}
			MemorySegment formatContext = AVFormatContext.reinterpret(
					formatContextPtr.get(ValueLayout.ADDRESS, 0), arena, null);
			FfmpegException.check(
					ffmpeg_h.avformat_find_stream_info(formatContext, MemorySegment.NULL),
					"avformat_find_stream_info");
			int streamIndex = ffmpeg_h.av_find_best_stream(
					formatContext, ffmpeg_h.AVMEDIA_TYPE_AUDIO(), -1, -1, MemorySegment.NULL, 0);
			if (streamIndex < 0) {
				throw new FfmpegException("av_find_best_stream(audio) on " + path.getFileName(),
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
			MemorySegment codecContext = AVCodecContext.reinterpret(
					ffmpeg_h.avcodec_alloc_context3(decoder), arena, null);
			if (codecContext.address() == 0) {
				throw new IllegalStateException("avcodec_alloc_context3 returned NULL");
			}
			MemorySegment codecContextPtr = arena.allocate(ValueLayout.ADDRESS);
			codecContextPtr.set(ValueLayout.ADDRESS, 0, codecContext);
			FfmpegException.check(
					ffmpeg_h.avcodec_parameters_to_context(codecContext, codecParameters),
					"avcodec_parameters_to_context");
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

			MemorySegment outputLayout = AVChannelLayout.allocate(arena);
			ffmpeg_h.av_channel_layout_default(outputLayout, OUTPUT_CHANNELS);
			MemorySegment swrPtr = arena.allocate(ValueLayout.ADDRESS);
			swrPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);

			AudioDecoder handle = new AudioDecoder(path, arena, formatContextPtr, formatContext,
					codecContextPtr, codecContext, framePtr, frame, packetPtr, packet, swrPtr,
					outputLayout, streamIndex);
			return handle;
		} catch (RuntimeException failure) {
			arena.close();
			throw failure;
		}
	}

	/** The container stream index this decoder reads. */
	public int streamIndex() {
		return audioStreamIndex;
	}

	/** The output sample rate, always {@link #OUTPUT_SAMPLE_RATE}. */
	public int sampleRate() {
		return OUTPUT_SAMPLE_RATE;
	}

	/** The output channel count, always {@link #OUTPUT_CHANNELS}. */
	public int channels() {
		return OUTPUT_CHANNELS;
	}

	/**
	 * Reads interleaved stereo float samples.
	 *
	 * @param interleaved destination array; its length must be a multiple of two
	 * @return the number of stereo frames written, or -1 at end of stream
	 */
	public int read(float[] interleaved) {
		ensureOpen();
		if (interleaved.length % OUTPUT_CHANNELS != 0) {
			throw new IllegalArgumentException(
					"interleaved array length must be a multiple of " + OUTPUT_CHANNELS);
		}
		int capacity = interleaved.length / OUTPUT_CHANNELS;
		int written = 0;
		while (written < capacity) {
			if (stagingCount == 0) {
				if (!refill()) {
					break;
				}
			}
			int available = Math.min(stagingCount, capacity - written);
			MemorySegment.copy(staging, ValueLayout.JAVA_FLOAT,
					(long) stagingStart * OUTPUT_CHANNELS,
					MemorySegment.ofArray(interleaved), ValueLayout.JAVA_FLOAT,
					(long) written * OUTPUT_CHANNELS,
					(long) available * OUTPUT_CHANNELS);
			stagingStart += available;
			stagingCount -= available;
			written += available;
		}
		if (written == 0 && eof) {
			return -1;
		}
		return written;
	}

	/** Seeks to {@code timeUs} and drops all buffered audio. */
	public void seek(long timeUs) {
		ensureOpen();
		if (timeUs < 0) {
			throw new IllegalArgumentException("negative seek time: " + timeUs);
		}
		FfmpegException.check(
				ffmpeg_h.av_seek_frame(formatContext, -1, timeUs, ffmpeg_h.AVSEEK_FLAG_BACKWARD()),
				"av_seek_frame");
		ffmpeg_h.avformat_flush(formatContext);
		ffmpeg_h.avcodec_flush_buffers(codecContext);
		if (swr != null && swr.address() != 0) {
			// Flush swresample's internal buffers (out = NULL).
			ffmpeg_h.swr_convert(swr, MemorySegment.NULL, 0, MemorySegment.NULL, 0);
		}
		stagingStart = 0;
		stagingCount = 0;
		flushSent = false;
		eof = false;
	}

	private boolean refill() {
		while (true) {
			int result = ffmpeg_h.avcodec_receive_frame(codecContext, frame);
			if (result == 0) {
				int converted = convertFrame();
				if (converted > 0) {
					stagingStart = 0;
					stagingCount = converted;
					return true;
				}
				continue;
			}
			if (result == FfmpegException.AVERROR_EOF) {
				eof = true;
				return drainResampler();
			}
			if (result != FfmpegException.AVERROR_EAGAIN) {
				throw new FfmpegException("avcodec_receive_frame", result);
			}
			boolean flushWasSent = flushSent;
			boolean fed = feedPacket();
			if (!fed && flushWasSent) {
				eof = true;
				return drainResampler();
			}
		}
	}

	private boolean feedPacket() {
		while (true) {
			int read = ffmpeg_h.av_read_frame(formatContext, packet);
			if (read == FfmpegException.AVERROR_EOF) {
				return sendFlushPacket();
			}
			FfmpegException.check(read, "av_read_frame");
			if (AVPacket.stream_index(packet) != audioStreamIndex) {
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

	private int convertFrame() {
		ensureResampler();
		int samples = AVFrame.nb_samples(frame);
		if (samples <= 0) {
			return 0;
		}
		int converted = ffmpeg_h.swr_convert(swr, outputPointerArray, STAGING_FRAMES,
				AVFrameUtils.extendedData(frame), samples);
		if (converted < 0) {
			throw new FfmpegException("swr_convert", converted);
		}
		return converted;
	}

	private boolean drainResampler() {
		if (swr == null || swr.address() == 0) {
			return false;
		}
		int drained = ffmpeg_h.swr_convert(swr, outputPointerArray, STAGING_FRAMES,
				MemorySegment.NULL, 0);
		if (drained < 0) {
			throw new FfmpegException("swr_convert(drain)", drained);
		}
		if (drained > 0) {
			stagingStart = 0;
			stagingCount = drained;
			return true;
		}
		return false;
	}

	private void ensureResampler() {
		int sampleFormat = AVFrame.format(frame);
		int sampleRate = AVFrame.sample_rate(frame) > 0
				? AVFrame.sample_rate(frame)
				: 48000;
		if (swr != null && swr.address() != 0
				&& sampleFormat == inputSampleFormat && sampleRate == inputSampleRate) {
			return;
		}
		if (swr != null && swr.address() != 0) {
			ffmpeg_h.swr_free(swrPtr);
			swr = MemorySegment.NULL;
		}
		MemorySegment inputLayout = AVFrame.ch_layout(frame);
		if (AVChannelLayout.order(inputLayout) == ffmpeg_h.AV_CHANNEL_ORDER_UNSPEC()) {
			inputLayout = AVChannelLayout.allocate(arena);
			ffmpeg_h.av_channel_layout_default(inputLayout,
					Math.max(1, AVChannelLayout.nb_channels(AVFrame.ch_layout(frame))));
		}
		int result = ffmpeg_h.swr_alloc_set_opts2(swrPtr, outputLayout,
				ffmpeg_h.AV_SAMPLE_FMT_FLT(), OUTPUT_SAMPLE_RATE,
				inputLayout, sampleFormat, sampleRate, 0, MemorySegment.NULL);
		if (result < 0) {
			throw new FfmpegException("swr_alloc_set_opts2", result);
		}
		swr = swrPtr.get(ValueLayout.ADDRESS, 0);
		if (swr.address() == 0) {
			throw new IllegalStateException("swr_alloc_set_opts2 returned NULL");
		}
		FfmpegException.check(ffmpeg_h.swr_init(swr), "swr_init");
		inputSampleFormat = sampleFormat;
		inputSampleRate = sampleRate;
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
			if (swrPtr.get(ValueLayout.ADDRESS, 0).address() != 0) {
				ffmpeg_h.swr_free(swrPtr);
			}
			ffmpeg_h.av_frame_free(framePtr);
			ffmpeg_h.av_packet_free(packetPtr);
			ffmpeg_h.avcodec_free_context(codecContextPtr);
			ffmpeg_h.avformat_close_input(formatContextPtr);
		} finally {
			arena.close();
		}
	}
}
