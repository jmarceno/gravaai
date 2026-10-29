package dev.gravaai.media.playback;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.Clips;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.TrimRegion;

/**
 * Timeline segmentation for preview/export, ported from
 * {@code src/lib/exporter/videoTimelineSegments.ts} and {@code src/lib/exporter/clipTimeline.ts}.
 *
 * <p>One forward decode pass per source-order run; timeline gaps need no decoding. The output frame
 * grid is shared across fractional-frame cuts so preview and export agree.
 */
public final class TimelineSegments {

	/** One decode segment ({@code VideoSegment}). */
	public record VideoSegment(
			double startSec,
			double endSec,
			double speed,
			@Nullable Double outputStartSec,
			@Nullable Double outputEndSec) {
	}

	/** A kept time range ({@code { startSec, endSec }}). */
	public record TimeSegment(double startSec, double endSec) {
	}

	/** A kept time range with a speed ({@code { startSec, endSec, speed }}). */
	public record SpedSegment(double startSec, double endSec, double speed) {
	}

	private TimelineSegments() {
	}

	/** {@code buildClipDecodeRuns}: one forward pass per source-order run. */
	public static List<List<VideoSegment>> buildClipDecodeRuns(List<ClipRegion> clips) {
		List<List<VideoSegment>> runs = new ArrayList<>();
		for (ClipRegion clip : Clips.sortClipRegions(clips)) {
			VideoSegment segment = new VideoSegment(
					Clips.getClipSourceStartMs(clip) / 1000.0,
					Clips.getClipSourceEndMs(clip) / 1000.0,
					clip.speed(),
					clip.startMs() / 1000.0,
					clip.endMs() / 1000.0);
			List<VideoSegment> run = runs.isEmpty() ? null : runs.get(runs.size() - 1);
			if (run != null && segment.startSec() >= run.get(run.size() - 1).endSec()) {
				run.add(segment);
			} else {
				List<VideoSegment> next = new ArrayList<>();
				next.add(segment);
				runs.add(next);
			}
		}
		return List.copyOf(runs);
	}

	/** {@code segmentFrameCount}. */
	public static long segmentFrameCount(VideoSegment segment, double fps) {
		if (segment.outputStartSec() != null && segment.outputEndSec() != null) {
			return (long) Math.ceil(segment.outputEndSec() * fps)
					- (long) Math.ceil(segment.outputStartSec() * fps);
		}
		double speed = Double.isFinite(segment.speed()) && segment.speed() > 0 ? segment.speed() : 1;
		return (long) Math.ceil(((segment.endSec() - segment.startSec()) / speed) * fps);
	}

	/** {@code segmentSourceTime}: the source time for one output frame of a segment. */
	public static double segmentSourceTime(VideoSegment segment, int index, double fps) {
		double outputOffset;
		if (segment.outputStartSec() == null) {
			outputOffset = index / fps;
		} else {
			double outputStart = segment.outputStartSec();
			outputOffset = (Math.ceil(outputStart * fps) + index) / fps - outputStart;
		}
		double speed = Double.isFinite(segment.speed()) && segment.speed() > 0 ? segment.speed() : 1;
		return segment.startSec() + outputOffset * speed;
	}

	/** {@code computeVideoSegments}: the kept ranges after the removed trims. */
	public static List<TimeSegment> computeVideoSegments(
			double totalDuration, List<TrimRegion> trimRegions) {
		if (trimRegions == null || trimRegions.isEmpty()) {
			return List.of(new TimeSegment(0, totalDuration));
		}
		List<TrimRegion> sorted = new ArrayList<>(trimRegions);
		sorted.sort(Comparator.comparingLong(TrimRegion::startMs));
		List<TimeSegment> segments = new ArrayList<>();
		double cursor = 0;
		for (TrimRegion trim : sorted) {
			double trimStart = trim.startMs() / 1000.0;
			double trimEnd = trim.endMs() / 1000.0;
			if (cursor < trimStart) {
				segments.add(new TimeSegment(cursor, trimStart));
			}
			cursor = Math.max(cursor, trimEnd);
		}
		if (cursor < totalDuration) {
			segments.add(new TimeSegment(cursor, totalDuration));
		}
		return List.copyOf(segments);
	}

	/** {@code splitVideoSegmentsBySpeed}: split the kept ranges at the speed regions. */
	public static List<SpedSegment> splitVideoSegmentsBySpeed(
			List<TimeSegment> segments, List<SpeedRegion> speedRegions) {
		if (speedRegions == null || speedRegions.isEmpty()) {
			return segments.stream().map(segment -> new SpedSegment(segment.startSec(), segment.endSec(), 1)).toList();
		}
		List<SpedSegment> result = new ArrayList<>();
		for (TimeSegment segment : segments) {
			List<SpeedRegion> overlapping = speedRegions.stream()
					.filter(speed -> speed.startMs() / 1000.0 < segment.endSec()
							&& speed.endMs() / 1000.0 > segment.startSec())
					.sorted(Comparator.comparingLong(SpeedRegion::startMs))
					.toList();
			if (overlapping.isEmpty()) {
				result.add(new SpedSegment(segment.startSec(), segment.endSec(), 1));
				continue;
			}
			double cursor = segment.startSec();
			for (SpeedRegion speed : overlapping) {
				double speedStart = Math.max(speed.startMs() / 1000.0, segment.startSec());
				double speedEnd = Math.min(speed.endMs() / 1000.0, segment.endSec());
				if (cursor < speedStart) {
					result.add(new SpedSegment(cursor, speedStart, 1));
				}
				double effectiveStart = Math.max(cursor, speedStart);
				if (speedEnd > effectiveStart) {
					result.add(new SpedSegment(effectiveStart, speedEnd, speed.speed()));
				}
				cursor = Math.max(cursor, speedEnd);
			}
			if (cursor < segment.endSec()) {
				result.add(new SpedSegment(cursor, segment.endSec(), 1));
			}
		}
		return result.stream().filter(segment -> segment.endSec() - segment.startSec() > 0.0001).toList();
	}

	/**
	 * {@code requiresClipTimelineRendering}: trim-only native paths concatenate source ranges; they
	 * cannot place clips.
	 */
	public static boolean requiresClipTimelineRendering(List<ClipRegion> clips) {
		if (clips == null) {
			return false;
		}
		if (clips.size() != 1) {
			return true;
		}
		ClipRegion clip = clips.get(0);
		return clip.startMs() != 0
				|| Clips.getClipSourceStartMs(clip) != 0
				|| clip.speed() != 1;
	}
}
