package dev.gravaai.media.capture;

import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;

import dev.gravaai.media.ffmpeg.FfmpegException;
import dev.gravaai.media.ffmpeg.FfmpegLibs;
import dev.gravaai.media.ffmpeg.gen.AVCodecContext;
import dev.gravaai.media.ffmpeg.gen.AVCodecParameters;
import dev.gravaai.media.ffmpeg.gen.AVFormatContext;
import dev.gravaai.media.ffmpeg.gen.AVFrame;
import dev.gravaai.media.ffmpeg.gen.AVPacket;
import dev.gravaai.media.ffmpeg.gen.AVStream;
import dev.gravaai.media.ffmpeg.gen.ffmpeg_h;

/** libavdevice v4l2 implementation of {@link WebcamCapture} (plan §5.6.6). */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
final class V4l2WebcamCapture implements WebcamCapture {

	private static final System.Logger LOG = System.getLogger(V4l2WebcamCapture.class.getName());
	private static final int MAX_PACKETS_PER_FRAME = 200;
	private static final long NOPTS = ffmpeg_h.AV_NOPTS_VALUE();

	private final Arena arena;
	private final String devicePath;
	private final int targetWidth;
	private final int targetHeight;
	private final MemorySegment formatContextPtr;
	private final MemorySegment formatContext;
	private final MemorySegment codecContextPtr;
	private final MemorySegment codecContext;
	private final MemorySegment framePtr;
	private final MemorySegment frame;
	private final MemorySegment packetPtr;
	private final MemorySegment packet;
	private final MemorySegment targetDataArray;
	private final MemorySegment targetStrideArray;
	private final MemorySegment sourceDataArray;
	private final MemorySegment sourceStrideArray;
	private final MemorySegment convertBuffer;
	private final int videoStreamIndex;
	private @Nullable MemorySegment swsContext;
	private MemorySegment convertDataArray;
	private @Nullable WebcamFormat format;
	private long frameCount;
	private boolean closed;

	private V4l2WebcamCapture(Arena arena, String devicePath, int targetWidth, int targetHeight,
			MemorySegment formatContextPtr, MemorySegment formatContext,
			MemorySegment codecContextPtr, MemorySegment codecContext, MemorySegment framePtr,
			MemorySegment frame, MemorySegment packetPtr, MemorySegment packet, int videoStreamIndex) {
		this.arena = arena;
		this.devicePath = devicePath;
		this.targetWidth = targetWidth;
		this.targetHeight = targetHeight;
		this.formatContextPtr = formatContextPtr;
		this.formatContext = formatContext;
		this.codecContextPtr = codecContextPtr;
		this.codecContext = codecContext;
		this.framePtr = framePtr;
		this.frame = frame;
		this.packetPtr = packetPtr;
		this.packet = packet;
		this.videoStreamIndex = videoStreamIndex;
		this.convertBuffer = arena.allocate((long) targetWidth * targetHeight * 4);
		this.convertDataArray = arena.allocate(8L * ValueLayout.ADDRESS.byteSize());
		this.convertDataArray.setAtIndex(ValueLayout.ADDRESS, 0, convertBuffer);
		this.targetDataArray = arena.allocate(8L * ValueLayout.ADDRESS.byteSize());
		this.targetStrideArray = arena.allocate(8L * ValueLayout.JAVA_INT.byteSize());
		this.sourceDataArray = arena.allocate(8L * ValueLayout.ADDRESS.byteSize());
		this.sourceStrideArray = arena.allocate(8L * ValueLayout.JAVA_INT.byteSize());
	}

	static WebcamCapture open(String devicePath, int width, int height, int fps) {
		FfmpegLibs.ensureLoaded();
		if (devicePath == null || devicePath.isBlank()) {
			throw new IllegalArgumentException("devicePath must not be blank");
		}
		int requestedWidth = Math.max(0, width);
		int requestedHeight = Math.max(0, height);
		int requestedFps = Math.max(0, fps);
		// Shared: the capture is opened by the caller but read and closed on the webcam thread.
		Arena arena = Arena.ofShared();
		try {
			ffmpeg_h.avdevice_register_all();
			MemorySegment inputFormat = ffmpeg_h.av_find_input_format(
					FfmpegLibs.cString(arena, "v4l2"));
			if (inputFormat.address() == 0) {
				throw new IllegalStateException("libavdevice is missing the v4l2 input format");
			}
			OpenResult opened = openDevice(arena, inputFormat, devicePath, requestedWidth,
					requestedHeight, requestedFps);
			MemorySegment formatContextPtr = opened.contextPtr();
			MemorySegment formatContext = opened.context();
			FfmpegException.check(
					ffmpeg_h.avformat_find_stream_info(formatContext, MemorySegment.NULL),
					"avformat_find_stream_info");
			int streamIndex = ffmpeg_h.av_find_best_stream(
					formatContext, ffmpeg_h.AVMEDIA_TYPE_VIDEO(), -1, -1, MemorySegment.NULL, 0);
			if (streamIndex < 0) {
				throw new IllegalStateException(devicePath + " has no video stream");
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
				throw new IllegalStateException(devicePath + ": no decoder for codec "
						+ AVCodecParameters.codec_id(codecParameters));
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
			// One decode thread: a webcam feed is latency-sensitive and each frame is small.
			AVCodecContext.thread_count(codecContext, 1);
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

			int frameWidth = AVCodecParameters.width(codecParameters) > 0
					? AVCodecParameters.width(codecParameters) : Math.max(1, requestedWidth);
			int frameHeight = AVCodecParameters.height(codecParameters) > 0
					? AVCodecParameters.height(codecParameters) : Math.max(1, requestedHeight);
			int outputWidth = requestedWidth > 0 ? requestedWidth : frameWidth;
			int outputHeight = requestedHeight > 0 ? requestedHeight : frameHeight;
			double frameRate = requestedFps > 0 ? requestedFps
					: rational(AVStream.avg_frame_rate(stream));
			String pixelFormatName = pixelFormatName(AVCodecParameters.format(codecParameters));
			V4l2WebcamCapture capture = new V4l2WebcamCapture(arena, devicePath, outputWidth,
					outputHeight, formatContextPtr, formatContext, codecContextPtr, codecContext,
					framePtr, frame, packetPtr, packet, streamIndex);
			capture.format = new WebcamFormat(outputWidth, outputHeight, frameRate, pixelFormatName);
			LOG.log(System.Logger.Level.DEBUG, () -> "opened " + devicePath + ": "
					+ capture.format);
			return capture;
		} catch (RuntimeException failure) {
			arena.close();
			throw failure;
		}
	}

	private record OpenResult(MemorySegment contextPtr, MemorySegment context) {
	}

	private static OpenResult openDevice(Arena arena, MemorySegment inputFormat, String devicePath,
			int width, int height, int fps) {
		// MJPEG first: it keeps 1080p30 within USB 2 bandwidth and costs less CPU to decode than
		// raw YUYV for the same resolution. The fallback uses whatever the device exposes.
		RuntimeException mjpegFailure = null;
		try {
			return attemptOpen(arena, inputFormat, devicePath, width, height, fps, "mjpeg");
		} catch (RuntimeException failure) {
			mjpegFailure = failure;
		}
		LOG.log(System.Logger.Level.DEBUG,
				() -> "MJPEG open failed for " + devicePath + ", falling back to the default format");
		try {
			return attemptOpen(arena, inputFormat, devicePath, width, height, fps, null);
		} catch (RuntimeException fallbackFailure) {
			fallbackFailure.addSuppressed(mjpegFailure);
			throw fallbackFailure;
		}
	}

	private static OpenResult attemptOpen(Arena arena, MemorySegment inputFormat, String devicePath,
			int width, int height, int fps, @Nullable String inputFormatName) {
		MemorySegment optionsPtr = arena.allocate(ValueLayout.ADDRESS);
		optionsPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
		try {
			if (width > 0 && height > 0) {
				FfmpegException.check(ffmpeg_h.av_dict_set(optionsPtr,
						FfmpegLibs.cString(arena, "video_size"),
						FfmpegLibs.cString(arena, width + "x" + height), 0),
						"av_dict_set(video_size)");
			}
			if (fps > 0) {
				FfmpegException.check(ffmpeg_h.av_dict_set(optionsPtr,
						FfmpegLibs.cString(arena, "framerate"),
						FfmpegLibs.cString(arena, Integer.toString(fps)), 0),
						"av_dict_set(framerate)");
			}
			if (inputFormatName != null) {
				FfmpegException.check(ffmpeg_h.av_dict_set(optionsPtr,
						FfmpegLibs.cString(arena, "input_format"),
						FfmpegLibs.cString(arena, inputFormatName), 0),
						"av_dict_set(input_format)");
			}
			MemorySegment contextPtr = arena.allocate(ValueLayout.ADDRESS);
			contextPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
			int result = ffmpeg_h.avformat_open_input(contextPtr,
					FfmpegLibs.cString(arena, devicePath), inputFormat, optionsPtr);
			try {
				if (result < 0) {
					throw new IllegalStateException("cannot open " + devicePath + ": "
							+ FfmpegException.errorText(result));
				}
			} finally {
				ffmpeg_h.av_dict_free(optionsPtr);
			}
			return new OpenResult(contextPtr,
					AVFormatContext.reinterpret(contextPtr.get(ValueLayout.ADDRESS, 0), arena, null));
		} finally {
			MemorySegment remaining = optionsPtr.get(ValueLayout.ADDRESS, 0);
			if (remaining.address() != 0) {
				ffmpeg_h.av_dict_free(optionsPtr);
			}
		}
	}

	@Override
	public @Nullable WebcamFormat format() {
		return format;
	}

	@Override
	public long frameCount() {
		return frameCount;
	}

	@Override
	public boolean readFrame(ByteBuffer rgbaTopFirst) {
		if (closed) {
			throw new IllegalStateException("capture for " + devicePath + " is closed");
		}
		int required = targetWidth * targetHeight * 4;
		if (rgbaTopFirst.remaining() < required) {
			throw new IllegalArgumentException("RGBA buffer too small: " + rgbaTopFirst.remaining()
					+ " < " + required);
		}
		for (int attempt = 0; attempt < MAX_PACKETS_PER_FRAME; attempt++) {
			int received = ffmpeg_h.avcodec_receive_frame(codecContext, frame);
			if (received == 0) {
				convert();
				copyTo(rgbaTopFirst, required);
				frameCount++;
				return true;
			}
			if (received == FfmpegException.AVERROR_EOF) {
				return false;
			}
			if (received != FfmpegException.AVERROR_EAGAIN) {
				throw new FfmpegException("avcodec_receive_frame", received);
			}
			int read = ffmpeg_h.av_read_frame(formatContext, packet);
			if (read == FfmpegException.AVERROR_EOF) {
				return false;
			}
			FfmpegException.check(read, "av_read_frame");
			if (AVPacket.stream_index(packet) != videoStreamIndex) {
				ffmpeg_h.av_packet_unref(packet);
				continue;
			}
			int sent = ffmpeg_h.avcodec_send_packet(codecContext, packet);
			ffmpeg_h.av_packet_unref(packet);
			if (sent < 0 && sent != FfmpegException.AVERROR_EAGAIN
					&& sent != FfmpegException.AVERROR_EOF) {
				throw new FfmpegException("avcodec_send_packet", sent);
			}
		}
		LOG.log(System.Logger.Level.WARNING,
				() -> "no frame from " + devicePath + " after " + MAX_PACKETS_PER_FRAME + " packets");
		return false;
	}

	private void convert() {
		int frameWidth = AVFrame.width(frame);
		int frameHeight = AVFrame.height(frame);
		int frameFormat = AVFrame.format(frame);
		if (swsContext == null) {
			swsContext = ffmpeg_h.sws_getContext(frameWidth, frameHeight, frameFormat,
					targetWidth, targetHeight, ffmpeg_h.AV_PIX_FMT_RGBA(),
					ffmpeg_h.SWS_FAST_BILINEAR(),
					MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
			if (swsContext.address() == 0) {
				throw new IllegalStateException("sws_getContext returned NULL");
			}
		}
		for (int i = 0; i < 8; i++) {
			targetDataArray.setAtIndex(ValueLayout.ADDRESS, i, MemorySegment.NULL);
			targetStrideArray.setAtIndex(ValueLayout.JAVA_INT, i, 0);
		}
		// Reused every frame: allocating these from the arena per frame grew it for the whole
		// recording.
		MemorySegment srcData = sourceDataArray;
		MemorySegment srcStride = sourceStrideArray;
		for (int i = 0; i < 8; i++) {
			srcData.setAtIndex(ValueLayout.ADDRESS, i, MemorySegment.NULL);
			srcStride.setAtIndex(ValueLayout.JAVA_INT, i, 0);
		}
		int planeCount = Math.max(1, ffmpeg_h.av_pix_fmt_count_planes(frameFormat));
		for (int i = 0; i < planeCount; i++) {
			MemorySegment data = AVFrame.data(frame, i);
			srcData.setAtIndex(ValueLayout.ADDRESS, i, data);
			srcStride.setAtIndex(ValueLayout.JAVA_INT, i, AVFrame.linesize(frame, i));
		}
		targetDataArray.setAtIndex(ValueLayout.ADDRESS, 0, convertBuffer);
		targetStrideArray.setAtIndex(ValueLayout.JAVA_INT, 0, targetWidth * 4);
		int scaled = ffmpeg_h.sws_scale(swsContext, srcData, srcStride, 0, frameHeight,
				targetDataArray, targetStrideArray);
		if (scaled <= 0) {
			throw new FfmpegException("sws_scale", scaled);
		}
	}

	private void copyTo(ByteBuffer destination, int bytes) {
		ByteBuffer view = convertBuffer.asByteBuffer();
		view.position(0);
		view.limit(bytes);
		destination.put(0, view, 0, bytes);
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			if (swsContext != null && swsContext.address() != 0) {
				ffmpeg_h.sws_freeContext(swsContext);
				swsContext = null;
			}
			ffmpeg_h.av_frame_free(framePtr);
			ffmpeg_h.av_packet_free(packetPtr);
			ffmpeg_h.avcodec_free_context(codecContextPtr);
			ffmpeg_h.avformat_close_input(formatContextPtr);
		} finally {
			arena.close();
		}
	}

	private static double rational(MemorySegment rational) {
		if (rational == null || rational.address() == 0) {
			return 0;
		}
		int numerator = dev.gravaai.media.ffmpeg.gen.AVRational.num(rational);
		int denominator = dev.gravaai.media.ffmpeg.gen.AVRational.den(rational);
		if (denominator == 0 || numerator <= 0) {
			return 0;
		}
		return (double) numerator / (double) denominator;
	}

	private static String pixelFormatName(int pixelFormat) {
		String name = FfmpegLibs.readCString(ffmpeg_h.av_get_pix_fmt_name(pixelFormat));
		return name == null ? "unknown" : name;
	}
}
