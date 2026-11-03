package dev.gravaai.media.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.ClickSoundSettings;
import dev.gravaai.media.compose.CursorSample;

/**
 * The single source of click times: maps the telemetry clicks through the clip regions so
 * preview and export consume the same plan and cannot diverge.
 *
 * <p>Clicks are filtered on the source timeline before mapping:
 * {@link Clips#mapSourceTimeToTimelineTime} clamps an out-of-clip time to the nearest clip
 * boundary, which would resurrect a trimmed-away click at the cut.</p>
 */
public final class ClickSoundHits {

	private static final Set<String> CLICK_TYPES =
			Set.of("click", "double-click", "right-click", "middle-click");
	/** Hits closer than this coalesce (double-clicks report two samples in one gesture). */
	private static final long DEDUPE_MS = 8;

	private ClickSoundHits() {}

	/** Timeline positions (ms, ascending, deduplicated) for every click that is on screen. */
	public static long[] timesMs(ClickSoundSettings settings, List<CursorSample> samples,
			List<ClipRegion> clips) {
		if (settings == null || !settings.enabled()) {
			return new long[0];
		}
		List<Long> mapped = new ArrayList<>();
		for (CursorSample sample : samples) {
			if (sample.interactionType() == null
					|| !CLICK_TYPES.contains(sample.interactionType())) {
				continue;
			}
			long sourceTimeMs = Math.round(sample.timeSeconds() * 1000.0);
			if (!insideAnyClip(sourceTimeMs, clips)) {
				continue;
			}
			mapped.add(Clips.mapSourceTimeToTimelineTime(sourceTimeMs, clips));
		}
		mapped.sort(Long::compare);
		List<Long> coalesced = new ArrayList<>(mapped.size());
		long previous = Long.MIN_VALUE;
		for (long time : mapped) {
			if (coalesced.isEmpty() || time - previous > DEDUPE_MS) {
				coalesced.add(time);
			}
			previous = time;
		}
		long[] timesMs = new long[coalesced.size()];
		for (int index = 0; index < timesMs.length; index++) {
			timesMs[index] = coalesced.get(index);
		}
		return timesMs;
	}

	/** Whether the telemetry carries at least one click (the inspector hint). */
	public static boolean hasClicks(List<CursorSample> samples) {
		for (CursorSample sample : samples) {
			if (sample.interactionType() != null
					&& CLICK_TYPES.contains(sample.interactionType())) {
				return true;
			}
		}
		return false;
	}

	/** Empty clips means "the whole source" (the mapper's identity case), so keep everything. */
	private static boolean insideAnyClip(long sourceTimeMs, List<ClipRegion> clips) {
		if (clips.isEmpty()) {
			return true;
		}
		for (ClipRegion clip : clips) {
			if (sourceTimeMs >= Clips.getClipSourceStartMs(clip)
					&& sourceTimeMs < Clips.getClipSourceEndMs(clip)) {
				return true;
			}
		}
		return false;
	}
}
