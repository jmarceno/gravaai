package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;

/**
 * Builds {@link PlaybackMedia} for the editor preview: probes the source duration and maps the
 * project's media paths/clips into the playback record (plan §5.9, P2.5 hand-off).
 *
 * <p>The caller resolves the paths and clip sequence from the project
 * ({@code services.projects.ProjectMediaSources}, {@code ProjectEditorState.clips()}) and the webcam
 * offset/ranges from the recording session manifest; this helper only adds the media probe.
 */
public final class PlaybackMediaLoader {

	/** Probes one media file's duration; injectable so tests avoid FFmpeg. */
	@FunctionalInterface
	public interface DurationProbe {
		double durationSeconds(Path file);
	}

	private PlaybackMediaLoader() {
	}

	/**
	 * Loads with the real FFmpeg probe; {@code webcamPath}/{@code webcamVisibleRanges} are null
	 * when the project has no webcam.
	 */
	public static PlaybackMedia load(
			Path videoPath,
			@Nullable Path webcamPath,
			long webcamTimeOffsetMs,
			List<ClipRegion> clips,
			@Nullable List<WebcamSync.WebcamRange> webcamVisibleRanges) {
		return load(videoPath, webcamPath, webcamTimeOffsetMs, clips, webcamVisibleRanges,
				PlaybackMediaLoader::probeWithFfmpeg);
	}

	/**
	 * Loads with an injected duration probe (tests, future native metadata sources);
	 * {@code webcamPath}/{@code webcamVisibleRanges} are null when the project has no webcam.
	 */
	public static PlaybackMedia load(
			Path videoPath,
			@Nullable Path webcamPath,
			long webcamTimeOffsetMs,
			List<ClipRegion> clips,
			@Nullable List<WebcamSync.WebcamRange> webcamVisibleRanges,
			DurationProbe probe) {
		if (videoPath == null) {
			throw new IllegalArgumentException("videoPath must not be null");
		}
		if (probe == null) {
			throw new IllegalArgumentException("probe must not be null");
		}
		double durationSeconds = probe.durationSeconds(videoPath);
		long sourceDurationMs = Double.isFinite(durationSeconds) && durationSeconds > 0
				? Math.round(durationSeconds * 1000)
				: 0;
		return new PlaybackMedia(
				videoPath.toString(),
				webcamPath == null ? null : webcamPath.toString(),
				webcamTimeOffsetMs,
				clips,
				sourceDurationMs,
				webcamVisibleRanges);
	}

	private static double probeWithFfmpeg(Path file) {
		try {
			MediaInfo info = MediaProbe.probe(file);
			return info.durationSeconds();
		} catch (RuntimeException error) {
			throw new IllegalArgumentException("Could not probe media duration of " + file + ": " + error, error);
		}
	}
}
