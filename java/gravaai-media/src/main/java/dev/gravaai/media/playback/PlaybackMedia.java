package dev.gravaai.media.playback;

import java.util.List;

import dev.gravaai.core.project.ClipRegion;

/**
 * The media the preview engine plays (plan §5.9): the project's screen recording, optional webcam
 * track and the kept clip sequence.
 *
 * @param videoPath the source recording path
 * @param webcamPath the linked webcam track path, or null
 * @param webcamTimeOffsetMs the webcam start offset from the recording session manifest
 * @param clips the kept clip sequence (timeline and source positions)
 * @param sourceDurationMs the source recording duration
 * @param webcamVisibleRanges imported webcam spans; null for an original recording, empty for none
 */
public record PlaybackMedia(
		String videoPath,
		String webcamPath,
		long webcamTimeOffsetMs,
		List<ClipRegion> clips,
		long sourceDurationMs,
		List<WebcamSync.WebcamRange> webcamVisibleRanges) {

	public PlaybackMedia {
		if (videoPath == null || videoPath.isBlank()) {
			throw new IllegalArgumentException("videoPath must not be blank");
		}
		if (sourceDurationMs < 0) {
			throw new IllegalArgumentException("sourceDurationMs must not be negative");
		}
		clips = clips == null ? List.of() : List.copyOf(clips);
		webcamVisibleRanges = webcamVisibleRanges == null ? null : List.copyOf(webcamVisibleRanges);
	}
}
