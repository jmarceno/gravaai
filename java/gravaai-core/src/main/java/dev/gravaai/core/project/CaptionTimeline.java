package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.List;

/** Caption projection onto the edited timeline (port of {@code captionTimeline.ts}). */
public final class CaptionTimeline {

	/** A caption fragment on the timeline: one source cue clipped to one clip. */
	public record Fragment(
			String id,
			String sourceCueId,
			CaptionCue sourceCue,
			ClipRegion clip,
			double startMs,
			double endMs) {}

	/** {@code captionSpanToSource}. */
	public record SourceSpan(long startMs, long endMs) {}

	private CaptionTimeline() {}

	/** {@code captionSpanToSource}. */
	public static SourceSpan captionSpanToSource(ClipRegion clip, double start, double end) {
		return new SourceSpan(sourceTime(clip, start), sourceTime(clip, end));
	}

	private static long sourceTime(ClipRegion clip, double time) {
		return Math.round(Clips.getClipSourceStartMs(clip)
				+ (Math.max(clip.startMs(), Math.min(clip.endMs(), time)) - clip.startMs())
						* clip.speed());
	}

	/** {@code projectCaptionCues}. */
	public static List<Fragment> projectCaptionCues(List<CaptionCue> cues, List<ClipRegion> clips) {
		List<Fragment> fragments = new ArrayList<>();
		for (ClipRegion clip : Clips.sortClipRegions(clips)) {
			long sourceStart = Clips.getClipSourceStartMs(clip);
			long sourceEnd = Clips.getClipSourceEndMs(clip);
			for (CaptionCue cue : cues) {
				long start = Math.max(cue.startMs(), sourceStart);
				long end = Math.min(cue.endMs(), sourceEnd);
				if (start >= end) {
					continue;
				}
				fragments.add(new Fragment(
						fragmentId(cue, clip), cue.id(), cue, clip,
						clip.startMs() + ((double) start - sourceStart) / clip.speed(),
						clip.startMs() + ((double) end - sourceStart) / clip.speed()));
			}
		}
		return fragments;
	}

	/** {@code retimeCaptionFragment}: an unchanged clipped edge keeps the whole source cue. */
	public static SourceSpan retimeCaptionFragment(Fragment fragment, double start, double end) {
		SourceSpan mapped = captionSpanToSource(fragment.clip(), start, end);
		long startMs = start == fragment.startMs()
				? fragment.sourceCue().startMs() : mapped.startMs();
		long endMs = end == fragment.endMs() ? fragment.sourceCue().endMs() : mapped.endMs();
		return new SourceSpan(startMs, endMs);
	}

	/** {@code JSON.stringify([cue.id, clip.id])}: the fragment identity used by the UI. */
	private static String fragmentId(CaptionCue cue, ClipRegion clip) {
		var array = dev.gravaai.core.json.Json.nodeFactory().arrayNode();
		array.add(cue.id());
		array.add(clip.id());
		return dev.gravaai.core.json.Json.MAPPER.writeValueAsString(array);
	}
}
