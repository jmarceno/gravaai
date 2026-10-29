package dev.gravaai.ui.editor.timeline.core;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.project.AudioRegion;

/**
 * Port of {@code timeline/hooks/utils/timelineAudioPlacement.ts}: where a newly added audio file
 * lands relative to the playhead — the first track with room for the desired duration, falling
 * back to the first track with any room when no track was preferred.
 */
public final class TimelineAudioPlacement {

	/** The chosen track and the (possibly clamped) region duration. */
	public record AudioPlacement(int trackIndex, long durationMs) {
	}

	private TimelineAudioPlacement() {
	}

	/** {@code resolveAudioPlacement}; {@code null} when there is no usable slot. */
	public static @Nullable AudioPlacement resolveAudioPlacement(List<AudioRegion> audioRegions, long startPos,
			long totalMs, long audioDurationMs, Integer preferredTrackIndex) {
		long maxRemainingDuration = totalMs - startPos;
		if (audioDurationMs <= 0 || maxRemainingDuration <= 0) {
			return null;
		}
		long desiredDuration = Math.min(audioDurationMs, maxRemainingDuration);
		Integer normalizedPreferredTrackIndex = preferredTrackIndex == null
				? null
				: Math.max(0, preferredTrackIndex);
		int maxTrackIndex = -1;
		for (AudioRegion region : audioRegions) {
			maxTrackIndex = Math.max(maxTrackIndex, trackIndexOf(region));
		}
		List<Integer> candidateTrackIndexes = new ArrayList<>();
		if (normalizedPreferredTrackIndex == null) {
			for (int index = 0; index <= maxTrackIndex + 1; index++) {
				candidateTrackIndexes.add(index);
			}
		} else {
			candidateTrackIndexes.add(normalizedPreferredTrackIndex);
		}

		Integer selectedTrackIndex = null;
		long availableGap = 0;
		for (int trackIndex : candidateTrackIndexes) {
			long gap = gapForTrack(audioRegions, trackIndex, startPos, totalMs, desiredDuration);
			if (gap >= desiredDuration) {
				selectedTrackIndex = trackIndex;
				availableGap = gap;
				break;
			}
		}
		if (selectedTrackIndex == null && normalizedPreferredTrackIndex == null) {
			for (int trackIndex : candidateTrackIndexes) {
				long gap = gapForTrack(audioRegions, trackIndex, startPos, totalMs, desiredDuration);
				if (gap > 0) {
					selectedTrackIndex = trackIndex;
					availableGap = gap;
					break;
				}
			}
		}
		if (selectedTrackIndex == null || availableGap <= 0) {
			return null;
		}
		return new AudioPlacement(selectedTrackIndex,
				Math.min(audioDurationMs, Math.min(availableGap, totalMs - startPos)));
	}

	private static int trackIndexOf(AudioRegion region) {
		return region.trackIndex() == null ? 0 : region.trackIndex();
	}

	private static long gapForTrack(List<AudioRegion> audioRegions, int trackIndex, long startPos,
			long totalMs, long desiredDuration) {
		List<AudioRegion> trackRegions = new ArrayList<>(audioRegions.stream()
				.filter(region -> trackIndexOf(region) == trackIndex)
				.sorted(Comparator.comparingLong(AudioRegion::startMs))
				.toList());
		Span desiredSpan = new Span(startPos, startPos + desiredDuration);
		for (AudioRegion region : trackRegions) {
			if (TimelineSpans.spansOverlap(desiredSpan,
					new Span(region.startMs(), region.endMs()))) {
				return 0;
			}
		}
		for (AudioRegion region : trackRegions) {
			if (region.startMs() > startPos) {
				return region.startMs() - startPos;
			}
		}
		return totalMs - startPos;
	}
}
