package dev.gravaai.ui.editor.transcript;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;

/**
 * Source-time to edited-timeline mapping for transcripts (the transcript words are always in
 * source time; clip speed changes the timeline length). Port of the parts of the React
 * {@code caption-speed} behaviour the transcript panel needs: highlighting the spoken word under
 * the playhead and click-to-seek landing at the right timeline position after speed changes.
 */
public final class TranscriptTimeMap {

	private final List<ClipRegion> clips;

	@Nullable public TranscriptTimeMap(List<ClipRegion> clips) {
		List<ClipRegion> sorted = clips == null ? List.of() : new ArrayList<>(clips);
		sorted.sort(Comparator.comparingLong(ClipRegion::startMs));
		this.clips = List.copyOf(sorted);
	}

	/** Source time for a timeline time, or {@code null} when the time falls in a gap. */
	public @Nullable Double timelineToSourceMs(double timelineMs) {
		if (!Double.isFinite(timelineMs)) {
			return null;
		}
		for (ClipRegion clip : clips) {
			if (timelineMs >= clip.startMs() && timelineMs < clip.endMs()) {
				double speed = speedOf(clip);
				return Clips.getClipSourceStartMs(clip)
						+ (timelineMs - clip.startMs()) * speed;
			}
		}
		return null;
	}

	/** Timeline time for a source time, or {@code null} when that footage was cut away. */
	public @Nullable Double sourceToTimelineMs(double sourceMs) {
		if (!Double.isFinite(sourceMs)) {
			return null;
		}
		for (ClipRegion clip : clips) {
			if (sourceMs >= Clips.getClipSourceStartMs(clip)
					&& sourceMs < Clips.getClipSourceEndMs(clip)) {
				return clip.startMs()
						+ (sourceMs - Clips.getClipSourceStartMs(clip)) / speedOf(clip);
			}
		}
		return null;
	}

	/** Timeline seconds to seek for a source time (null when the footage is gone). */
	public @Nullable Double seekSecondsForSourceMs(double sourceMs) {
		Double timeline = sourceToTimelineMs(sourceMs);
		return timeline == null ? null : timeline / 1000.0;
	}

	/** The word spoken at a timeline time, or {@code null}. */
	public TranscriptWords.@Nullable Word activeWord(List<TranscriptWords.Word> words, double timelineMs) {
		Double source = timelineToSourceMs(timelineMs);
		if (source == null) {
			return null;
		}
		for (TranscriptWords.Word word : words) {
			if (source >= word.startMs() && source < word.endMs()) {
				return word;
			}
		}
		return null;
	}

	/** Timeline duration of a source-time span at the clip's speed (caption-speed values). */
	public double timelineDurationMs(double sourceDurationMs, double speed) {
		double safeSpeed = speed > 0 ? speed : 1;
		return sourceDurationMs / safeSpeed;
	}

	private static double speedOf(ClipRegion clip) {
		return Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
	}
}
