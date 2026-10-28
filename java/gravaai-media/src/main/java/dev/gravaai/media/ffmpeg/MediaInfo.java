package dev.gravaai.media.ffmpeg;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The result of {@link MediaProbe#probe(Path)}: everything the app needs to know about a
 * media file without decoding it (plan §5.7 — replaces {@code probe-native-video-metadata},
 * {@code ffmpeg/metadata.ts}, {@code mp4box} and {@code web-demuxer}).
 *
 * @param path the probed file, absolute and normalised
 * @param durationSeconds the container duration in seconds ({@code 0} when unknown)
 * @param videoStreamIndex the index {@code av_find_best_stream} picks for video, or -1
 * @param audioStreamIndex the index {@code av_find_best_stream} picks for audio, or -1
 * @param videoStreams every video stream in container order
 * @param audioStreams every audio stream in container order
 */
public record MediaInfo(
		Path path,
		double durationSeconds,
		int videoStreamIndex,
		int audioStreamIndex,
		List<VideoStreamInfo> videoStreams,
		List<AudioStreamInfo> audioStreams) {

	public MediaInfo {
		videoStreams = List.copyOf(videoStreams);
		audioStreams = List.copyOf(audioStreams);
	}

	/** The best video stream, if the file has one. */
	public Optional<VideoStreamInfo> video() {
		return videoStreams.stream().filter(s -> s.index() == videoStreamIndex).findFirst();
	}

	/** The best audio stream, if the file has one. */
	public Optional<AudioStreamInfo> audio() {
		return audioStreams.stream().filter(s -> s.index() == audioStreamIndex).findFirst();
	}

	public boolean hasVideo() {
		return videoStreamIndex >= 0;
	}

	public boolean hasAudio() {
		return audioStreamIndex >= 0;
	}
}
