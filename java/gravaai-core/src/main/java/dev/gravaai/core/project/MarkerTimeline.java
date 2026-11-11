package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Timeline projection of markers (the point-time sibling of {@link CaptionTimeline}).
 *
 * <p>Markers live in source time; this maps each one through the retained clips and drops the ones
 * whose footage is no longer on the timeline. Mirrors {@code Clips.mapSourceTimeToTimelineTime}
 * except that a marker outside every clip is hidden instead of being snapped to the nearest clip
 * boundary: a marker for a trimmed-away intro must not pile up at 00:00.
 */
public final class MarkerTimeline {

	/** One marker on the edited timeline: the clip it landed on and where it sits. */
	public record Fragment(String markerId, TimelineMarker marker, ClipRegion clip, long timelineMs) {}

	private MarkerTimeline() {}

	/** {@code projectMarkers}: markers that still land on retained footage, in timeline order. */
	public static List<Fragment> projectMarkers(List<TimelineMarker> markers,
			List<ClipRegion> clips) {
		List<ClipRegion> sequence = Clips.sortClipRegions(clips);
		List<Fragment> fragments = new ArrayList<>();
		for (TimelineMarker marker : markers) {
			clipAtSourceTime(marker.startMs(), sequence).ifPresent(owner -> fragments.add(
					new Fragment(marker.id(), marker, owner, timelineTime(owner, marker.startMs()))));
		}
		// Stable sort: two markers at the same timeline position keep their list order, so the
		// chapter list's "first title wins" rule is the one the user sees in the marker list.
		fragments.sort(Comparator.comparingLong(Fragment::timelineMs));
		return List.copyOf(fragments);
	}

	/** The timeline position of a marker, or empty when its footage is not on the timeline. */
	public static java.util.OptionalLong timelineMs(TimelineMarker marker,
			List<ClipRegion> clips) {
		return clipAtSourceTime(marker.startMs(), Clips.sortClipRegions(clips))
				.map(owner -> java.util.OptionalLong.of(timelineTime(owner, marker.startMs())))
				.orElse(java.util.OptionalLong.empty());
	}

	private static java.util.Optional<ClipRegion> clipAtSourceTime(long sourceMs,
			List<ClipRegion> sequence) {
		for (int index = 0; index < sequence.size(); index++) {
			ClipRegion clip = sequence.get(index);
			long sourceStart = Clips.getClipSourceStartMs(clip);
			long sourceEnd = Clips.getClipSourceEndMs(clip);
			if (sourceMs >= sourceStart && sourceMs < sourceEnd) {
				return java.util.Optional.of(clip);
			}
			// A marker on the very last frame of the recording belongs to the last kept clip.
			if (sourceMs == sourceEnd && index == sequence.size() - 1) {
				return java.util.Optional.of(clip);
			}
		}
		return java.util.Optional.empty();
	}

	private static long timelineTime(ClipRegion clip, long sourceMs) {
		double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
		return Math.round(clip.startMs()
				+ (sourceMs - Clips.getClipSourceStartMs(clip)) / speed);
	}
}
