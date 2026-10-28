package dev.gravaai.ui.editor.timeline.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Source-audio track resolution (port of {@code timeline/sourceAudioTracks.ts}): builds the
 * per-recording sidecar path candidates ({@code <name>.mic.wav|m4a|webm}) and decides which tracks
 * appear on the timeline from the available sidecars/embedded audio.
 */
public final class TimelineSourceAudioTracks {

	/** Port of {@code SOURCE_SIDECAR_EXTENSIONS}. */
	public static final List<String> SOURCE_SIDECAR_EXTENSIONS = List.of(".wav", ".m4a", ".webm");

	/** Port of the {@code labels} object. */
	public record Labels(String system, String mic, String mixed) {
		public static final Labels DEFAULT = new Labels("Source System", "Source Mic", "Source");
	}

	private TimelineSourceAudioTracks() {
	}

	/** Port of {@code buildSourceSidecarPathCandidates}. */
	public static List<String> buildSidecarPathCandidates(String source, String suffix) {
		if (source == null || source.isBlank()) {
			return List.of();
		}
		String normalized = source.replace('\\', '/');
		int lastSlash = normalized.lastIndexOf('/');
		String directory = lastSlash >= 0 ? normalized.substring(0, lastSlash + 1) : "";
		String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
		int dotIndex = fileName.lastIndexOf('.');
		String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
		List<String> candidates = new ArrayList<>(SOURCE_SIDECAR_EXTENSIONS.size());
		for (String extension : SOURCE_SIDECAR_EXTENSIONS) {
			candidates.add(directory + baseName + "." + suffix + extension);
		}
		return List.copyOf(candidates);
	}

	/**
	 * Port of {@code buildTimelineSourceAudioTracks}: dedicated sidecars win; the embedded track
	 * stays controllable as the system track when only the mic sidecar exists; without sidecars the
	 * embedded audio becomes one mixed track.
	 */
	public static List<TimelineSourceAudioTrack> buildTracks(boolean hasSourceAudio, String sourcePath,
			boolean hasMicSidecar, String micSidecarPath, boolean hasSystemSidecar,
			String systemSidecarPath, Labels labels) {
		Labels resolvedLabels = labels == null ? Labels.DEFAULT : labels;
		List<TimelineSourceAudioTrack> tracks = new ArrayList<>();
		if (hasSystemSidecar || hasMicSidecar) {
			if (hasSystemSidecar) {
				tracks.add(new TimelineSourceAudioTrack(
						dev.gravaai.core.project.SourceAudioTrackSettings.SYSTEM,
						resolvedLabels.system(),
						systemSidecarPath != null ? systemSidecarPath : sourcePath));
			} else if (hasMicSidecar && hasSourceAudio) {
				tracks.add(new TimelineSourceAudioTrack(
						dev.gravaai.core.project.SourceAudioTrackSettings.SYSTEM,
						resolvedLabels.system(), sourcePath));
			}
			if (hasMicSidecar) {
				tracks.add(new TimelineSourceAudioTrack(
						dev.gravaai.core.project.SourceAudioTrackSettings.MIC, resolvedLabels.mic(),
						micSidecarPath));
			}
			return List.copyOf(tracks);
		}
		if (hasSourceAudio) {
			tracks.add(new TimelineSourceAudioTrack(
					dev.gravaai.core.project.SourceAudioTrackSettings.MIXED,
					resolvedLabels.mixed(), sourcePath));
		}
		return List.copyOf(tracks);
	}
}
