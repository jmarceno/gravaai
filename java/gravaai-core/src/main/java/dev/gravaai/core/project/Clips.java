package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Clip/timeline time mapping and trim conversion (port of the pure helpers in
 * {@code src/components/video-editor/types.ts}).
 *
 * <p>Timeline time is what the editor shows; source time is where the recording is read from.
 * Clips keep their source in-point when moved, so timeline order says nothing about source order.
 */
public final class Clips {

	private Clips() {}

	/** {@code getClipSourceStartMs}: defaults to the timeline start. */
	public static long getClipSourceStartMs(ClipRegion clip) {
		return clip.sourceStartMs() != null ? clip.sourceStartMs() : clip.startMs();
	}

	/** {@code getClipSourceEndMs}: start + display duration * speed, rounded. */
	public static long getClipSourceEndMs(ClipRegion clip) {
		long displayDurationMs = Math.max(0, clip.endMs() - clip.startMs());
		double speed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
		return Math.round(getClipSourceStartMs(clip) + displayDurationMs * speed);
	}

	/** {@code getTimelineDurationMs}. */
	public static long getTimelineDurationMs(List<ClipRegion> clips, long sourceDurationMs) {
		long baseDurationMs = Math.max(0, sourceDurationMs);
		if (clips.isEmpty()) {
			return baseDurationMs;
		}

		long durationMs = 0;
		for (ClipRegion clip : clips) {
			durationMs = Math.max(durationMs, Math.max(0, clip.endMs()));
		}
		return durationMs;
	}

	/** {@code sortClipRegions}: a sorted copy, stable for equal starts. */
	public static List<ClipRegion> sortClipRegions(List<ClipRegion> clips) {
		List<ClipRegion> sorted = new ArrayList<>(clips);
		sorted.sort(Comparator.comparingLong(ClipRegion::startMs));
		return sorted;
	}

	private static double getSafeClipSpeed(ClipRegion clip) {
		return Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
	}

	private static long mapNearestClipBoundary(
			double timeMs, List<ClipRegion> clips, boolean fromTimeline) {
		long nearestTimeMs = Math.round(timeMs);
		double nearestDistance = Double.POSITIVE_INFINITY;

		for (ClipRegion clip : clips) {
			long[][] boundaries = {
					{clip.startMs(), getClipSourceStartMs(clip)},
					{clip.endMs(), getClipSourceEndMs(clip)},
			};
			for (long[] boundary : boundaries) {
				long boundaryTimeMs = fromTimeline ? boundary[0] : boundary[1];
				double distance = Math.abs(timeMs - boundaryTimeMs);
				if (distance < nearestDistance) {
					nearestDistance = distance;
					nearestTimeMs = Math.round(fromTimeline ? boundary[1] : boundary[0]);
				}
			}
		}

		return nearestTimeMs;
	}

	/** {@code mapTimelineTimeToSourceTime}. */
	public static long mapTimelineTimeToSourceTime(double timeMs, List<ClipRegion> clips) {
		long roundedTimeMs = Math.round(timeMs);
		List<ClipRegion> sortedClips = sortClipRegions(clips);

		for (ClipRegion clip : sortedClips) {
			if (timeMs < clip.startMs() || timeMs >= clip.endMs()) {
				continue;
			}
			return Math.round(
					getClipSourceStartMs(clip) + (roundedTimeMs - clip.startMs()) * getSafeClipSpeed(clip));
		}

		if (sortedClips.isEmpty()) {
			return roundedTimeMs;
		}
		return mapNearestClipBoundary(roundedTimeMs, sortedClips, true);
	}

	/** {@code mapSourceTimeToTimelineTime}. */
	public static long mapSourceTimeToTimelineTime(double timeMs, List<ClipRegion> clips) {
		long roundedTimeMs = Math.round(timeMs);
		List<ClipRegion> sortedClips = sortClipRegions(clips);

		for (ClipRegion clip : sortedClips) {
			long sourceStartMs = getClipSourceStartMs(clip);
			long sourceEndMs = getClipSourceEndMs(clip);
			if (timeMs < sourceStartMs || timeMs >= sourceEndMs) {
				continue;
			}
			return Math.round(clip.startMs() + (roundedTimeMs - sourceStartMs) / getSafeClipSpeed(clip));
		}

		if (sortedClips.isEmpty()) {
			return roundedTimeMs;
		}
		return mapNearestClipBoundary(roundedTimeMs, sortedClips, false);
	}

	/** {@code findClipAtTimelineTime}. */
	public static ClipRegion findClipAtTimelineTime(double timeMs, List<ClipRegion> clips) {
		for (ClipRegion clip : sortClipRegions(clips)) {
			if (timeMs >= clip.startMs() && timeMs < clip.endMs()) {
				return clip;
			}
		}
		return null;
	}

	/** {@code extendAutoFullTrackClip}: grow the untouched full-track clip; otherwise null. */
	public static List<ClipRegion> extendAutoFullTrackClip(
			List<ClipRegion> clips,
			String autoClipId,
			Long previousAutoEndMs,
			Long nextTotalDurationMs) {
		if (autoClipId == null
				|| previousAutoEndMs == null
				|| nextTotalDurationMs == null
				|| nextTotalDurationMs <= previousAutoEndMs
				|| clips.size() != 1) {
			return null;
		}

		ClipRegion clip = clips.get(0);
		if (!clip.id().equals(autoClipId)
				|| clip.startMs() != 0
				|| clip.speed() != 1
				|| clip.endMs() != previousAutoEndMs) {
			return null;
		}

		return List.of(new ClipRegion(clip.id(), clip.startMs(), nextTotalDurationMs, clip.sourceStartMs(),
				clip.sourceMinMs(), clip.sourceMaxMs(), clip.speed(), clip.muted(), clip.showSourceAudio(),
				clip.cameraLayout(), clip.transitionOut()));
	}

	/** {@code clipsToTrims}: convert kept clip regions into the gaps to remove. */
	public static List<TrimRegion> clipsToTrims(List<ClipRegion> clips, long totalDurationMs) {
		if (clips.isEmpty()) {
			return List.of();
		}

		List<long[]> coveredSpans = new ArrayList<>();
		for (ClipRegion clip : clips) {
			long startMs = getClipSourceStartMs(clip);
			long endMs = getClipSourceEndMs(clip);
			if (endMs > startMs) {
				coveredSpans.add(new long[] {startMs, endMs});
			}
		}
		coveredSpans.sort(Comparator.comparingLong(span -> span[0]));

		List<TrimRegion> trims = new ArrayList<>();
		long cursor = 0;
		int trimId = 1;
		for (long[] span : coveredSpans) {
			if (span[0] > cursor) {
				trims.add(new TrimRegion("trim-gap-" + trimId++, cursor, span[0]));
			}
			cursor = Math.max(cursor, span[1]);
		}
		if (cursor < totalDurationMs) {
			trims.add(new TrimRegion("trim-gap-" + trimId++, cursor, totalDurationMs));
		}
		return trims;
	}

	/** {@code trimsToClips}: convert legacy trim regions into clip regions (the complement). */
	public static List<ClipRegion> trimsToClips(List<TrimRegion> trims, long totalDurationMs) {
		if (trims.isEmpty()) {
			return List.of(new ClipRegion("clip-1", 0, totalDurationMs, null, null, null, 1,
					null, null, null, null));
		}

		List<TrimRegion> sorted = new ArrayList<>(trims);
		sorted.sort(Comparator.comparingLong(TrimRegion::startMs));

		List<ClipRegion> clips = new ArrayList<>();
		long cursor = 0;
		int clipId = 1;
		for (TrimRegion trim : sorted) {
			if (trim.startMs() > cursor) {
				clips.add(new ClipRegion("clip-" + clipId++, cursor, trim.startMs(), null, null, null, 1,
						null, null, null, null));
			}
			cursor = trim.endMs();
		}
		if (cursor < totalDurationMs) {
			clips.add(new ClipRegion("clip-" + clipId++, cursor, totalDurationMs, null, null, null, 1,
					null, null, null, null));
		}
		return clips;
	}
}
