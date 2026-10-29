package dev.gravaai.media.ffmpeg;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.media.ffmpeg.gen.AVPacketSideData;
import dev.gravaai.media.ffmpeg.gen.AVRational;
import dev.gravaai.media.ffmpeg.gen.AVCodecParameters;
import dev.gravaai.media.ffmpeg.gen.AVFormatContext;
import dev.gravaai.media.ffmpeg.gen.AVStream;
import dev.gravaai.media.ffmpeg.gen.ffmpeg_h;

/**
 * Probes a media file with libavformat (plan §5.7).
 *
 * <p>Everything is read from the headers and {@code avformat_find_stream_info}; nothing is
 * decoded, and no native memory escapes this class — the result is a plain record. This is
 * the replacement for {@code probe-native-video-metadata}, {@code ipc/ffmpeg/metadata.ts},
 * {@code mp4box} and {@code web-demuxer}.</p>
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class MediaProbe {

	private static final System.Logger LOG = System.getLogger(MediaProbe.class.getName());

	private MediaProbe() {}

	/**
	 * Probes a file.
	 *
	 * @throws FfmpegException when FFmpeg cannot open or read the container
	 * @throws IllegalArgumentException when the path is not a readable regular file
	 */
	public static MediaInfo probe(Path file) {
		FfmpegLibs.ensureLoaded();
		Path path = file.toAbsolutePath().normalize();
		if (!Files.isReadable(path)) {
			throw new IllegalArgumentException("not a readable file: " + path);
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment context = openInput(arena, path);
			try {
				FfmpegException.check(
						ffmpeg_h.avformat_find_stream_info(context, MemorySegment.NULL),
						"avformat_find_stream_info");
				return readInfo(arena, path, context);
			} finally {
				closeInput(arena, context);
			}
		}
	}

	private static MemorySegment openInput(Arena arena, Path path) {
		MemorySegment contextPtr = arena.allocate(ValueLayout.ADDRESS);
		contextPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
		int result = ffmpeg_h.avformat_open_input(
				contextPtr,
				FfmpegLibs.cString(arena, path.toString()),
				MemorySegment.NULL,
				MemorySegment.NULL);
		if (result < 0) {
			throw new FfmpegException("avformat_open_input(" + path.getFileName() + ")", result);
		}
		// Struct pointers returned by FFmpeg are zero-size address segments; reinterpret them
		// with the struct layout before any field access.
		return AVFormatContext.reinterpret(contextPtr.get(ValueLayout.ADDRESS, 0), arena, null);
	}

	private static void closeInput(Arena arena, MemorySegment context) {
		MemorySegment contextPtr = arena.allocate(ValueLayout.ADDRESS);
		contextPtr.set(ValueLayout.ADDRESS, 0, context);
		ffmpeg_h.avformat_close_input(contextPtr);
	}

	private static MediaInfo readInfo(Arena arena, Path path, MemorySegment context) {
		double durationSeconds = containerDurationSeconds(arena, context);
		int nbStreams = AVFormatContext.nb_streams(context);
		MemorySegment streams = AVFormatContext.streams(context)
				.reinterpret((long) nbStreams * ValueLayout.ADDRESS.byteSize());

		List<VideoStreamInfo> videoStreams = new ArrayList<>();
		List<AudioStreamInfo> audioStreams = new ArrayList<>();
		for (int i = 0; i < nbStreams; i++) {
			MemorySegment stream = AVStream.reinterpret(
					streams.getAtIndex(ValueLayout.ADDRESS, i), arena, null);
			MemorySegment codecParameters = AVCodecParameters.reinterpret(
					AVStream.codecpar(stream), arena, null);
			if (codecParameters.address() == 0) {
				continue;
			}
			int codecType = AVCodecParameters.codec_type(codecParameters);
			if (codecType == ffmpeg_h.AVMEDIA_TYPE_VIDEO()) {
				videoStreams.add(readVideoStream(arena, context, stream, i));
			} else if (codecType == ffmpeg_h.AVMEDIA_TYPE_AUDIO()) {
				audioStreams.add(readAudioStream(arena, stream, i));
			}
		}

		// av_find_best_stream returns a negative AVERROR code when there is no stream of that
		// type; MediaInfo models "absent" as -1.
		int bestVideo = ffmpeg_h.av_find_best_stream(
				context, ffmpeg_h.AVMEDIA_TYPE_VIDEO(), -1, -1, MemorySegment.NULL, 0);
		int bestAudio = ffmpeg_h.av_find_best_stream(
				context, ffmpeg_h.AVMEDIA_TYPE_AUDIO(), -1, -1, MemorySegment.NULL, 0);
		MediaInfo info = new MediaInfo(path, durationSeconds, Math.max(bestVideo, -1),
				Math.max(bestAudio, -1), videoStreams, audioStreams);
		LOG.log(System.Logger.Level.DEBUG, () -> "probed " + path.getFileName() + ": "
				+ info.videoStreams().size() + " video / " + info.audioStreams().size()
				+ " audio stream(s), " + String.format(java.util.Locale.ROOT, "%.3f", durationSeconds)
				+ " s");
		return info;
	}

	private static VideoStreamInfo readVideoStream(
			Arena arena, MemorySegment context, MemorySegment stream, int index) {
		MemorySegment codecParameters = AVCodecParameters.reinterpret(
				AVStream.codecpar(stream), arena, null);
		int width = AVCodecParameters.width(codecParameters);
		int height = AVCodecParameters.height(codecParameters);
		ColourInfo colour = new ColourInfo(
				AVCodecParameters.color_primaries(codecParameters),
				AVCodecParameters.color_trc(codecParameters),
				AVCodecParameters.color_space(codecParameters),
				AVCodecParameters.color_range(codecParameters));
		return new VideoStreamInfo(
				index,
				codecName(AVCodecParameters.codec_id(codecParameters)),
				width,
				height,
				frameRate(arena, context, stream),
				streamDurationSeconds(stream),
				rotationDegrees(arena, codecParameters),
				colour);
	}

	private static AudioStreamInfo readAudioStream(Arena arena, MemorySegment stream, int index) {
		MemorySegment codecParameters = AVCodecParameters.reinterpret(
				AVStream.codecpar(stream), arena, null);
		MemorySegment channelLayout = AVCodecParameters.ch_layout(codecParameters);
		int channels = Math.max(1, dev.gravaai.media.ffmpeg.gen.AVChannelLayout.nb_channels(channelLayout));
		return new AudioStreamInfo(
				index,
				codecName(AVCodecParameters.codec_id(codecParameters)),
				AVCodecParameters.sample_rate(codecParameters),
				channels,
				channelLayoutName(arena, channelLayout, channels),
				streamDurationSeconds(stream));
	}

	private static double frameRate(Arena arena, MemorySegment context, MemorySegment stream) {
		double fps = rational(ffmpeg_h.av_guess_frame_rate(arena, context, stream, MemorySegment.NULL));
		if (fps <= 0) {
			fps = rational(AVStream.avg_frame_rate(stream));
		}
		if (fps <= 0) {
			fps = rational(AVStream.r_frame_rate(stream));
		}
		return fps;
	}

	private static double rational(MemorySegment rational) {
		if (rational == null || rational.address() == 0) {
			return 0;
		}
		int numerator = AVRational.num(rational);
		int denominator = AVRational.den(rational);
		if (denominator == 0 || numerator <= 0) {
			return 0;
		}
		return (double) numerator / (double) denominator;
	}

	private static double containerDurationSeconds(Arena arena, MemorySegment context) {
		long duration = AVFormatContext.duration(context);
		if (duration != ffmpeg_h.AV_NOPTS_VALUE() && duration > 0) {
			// AVFormatContext.duration is in AV_TIME_BASE units (microseconds).
			return duration / (double) ffmpeg_h.AV_TIME_BASE();
		}
		return 0;
	}

	private static double streamDurationSeconds(MemorySegment stream) {
		long duration = AVStream.duration(stream);
		if (duration <= 0 || duration == ffmpeg_h.AV_NOPTS_VALUE()) {
			return 0;
		}
		return duration * rational(AVStream.time_base(stream));
	}

	private static int rotationDegrees(Arena arena, MemorySegment codecParameters) {
		MemorySegment sideData = AVCodecParameters.coded_side_data(codecParameters);
		int count = AVCodecParameters.nb_coded_side_data(codecParameters);
		if (sideData.address() == 0 || count <= 0) {
			return 0;
		}
		MemorySegment displayMatrix = ffmpeg_h.av_packet_side_data_get(
				sideData, count, ffmpeg_h.AV_PKT_DATA_DISPLAYMATRIX());
		if (displayMatrix.address() == 0) {
			return 0;
		}
		MemorySegment entry = AVPacketSideData.reinterpret(displayMatrix, arena, null);
		MemorySegment data = AVPacketSideData.data(entry);
		if (data.address() == 0) {
			return 0;
		}
		// av_display_rotation_get returns degrees counterclockwise; normalise to [0, 360).
		double degrees = ffmpeg_h.av_display_rotation_get(data);
		int rounded = (int) Math.round(degrees) % 360;
		return rounded < 0 ? rounded + 360 : rounded;
	}

	private static String channelLayoutName(Arena arena, MemorySegment channelLayout, int channels) {
		long size = 256;
		MemorySegment buffer = arena.allocate(size);
		int written = ffmpeg_h.av_channel_layout_describe(channelLayout, buffer, size);
		if (written < 0) {
			return channels + " channels";
		}
		return buffer.getString(0);
	}

	private static String codecName(int codecId) {
		String name = FfmpegLibs.readCString(ffmpeg_h.avcodec_get_name(codecId));
		return name == null ? "unknown" : name;
	}
}
