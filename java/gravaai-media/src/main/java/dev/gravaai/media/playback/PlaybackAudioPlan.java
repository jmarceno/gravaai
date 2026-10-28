package dev.gravaai.media.playback;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.media.audio.AtempoChain;

/**
 * Audio execution plan for the preview: one segment per clip with its timeline/source range and the
 * {@code atempo} filter chain that preserves pitch for the clip speed (plan §5.9: "audio goes
 * through an atempo filter graph per region; chain atempo for &lt;0.5 or &gt;2").
 *
 * <p>Pure data so the soon-to-land audio decoder can build one filtergraph per segment and the
 * preview and export paths can be compared segment by segment.
 */
public final class PlaybackAudioPlan {

	/**
	 * One decoded audio segment.
	 *
	 * @param timelineStartSeconds timeline in-point
	 * @param timelineEndSeconds timeline out-point (exclusive)
	 * @param sourceStartSeconds source in-point
	 * @param sourceEndSeconds source out-point
	 * @param speed the clip speed (1 = untouched)
	 * @param atempoFilters the filter chain that realises {@code speed} (empty at 1x)
	 */
	public record AudioSegment(
			double timelineStartSeconds,
			double timelineEndSeconds,
			double sourceStartSeconds,
			double sourceEndSeconds,
			double speed,
			List<String> atempoFilters) {
	}

	private PlaybackAudioPlan() {
	}

	/** Builds the plan for the kept clip sequence, in timeline order. */
	public static List<AudioSegment> plan(List<ClipRegion> clips) {
		if (clips == null || clips.isEmpty()) {
			return List.of();
		}
		List<AudioSegment> segments = new ArrayList<>();
		for (ClipRegion clip : Clips.sortClipRegions(clips)) {
			double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
			double timelineStart = clip.startMs() / 1000.0;
			double timelineEnd = clip.endMs() / 1000.0;
			if (timelineEnd <= timelineStart) {
				continue;
			}
			segments.add(new AudioSegment(
					timelineStart,
					timelineEnd,
					Clips.getClipSourceStartMs(clip) / 1000.0,
					Clips.getClipSourceEndMs(clip) / 1000.0,
					speed,
					speed == 1 ? List.of() : AtempoChain.filters(speed)));
		}
		return List.copyOf(segments);
	}
}
