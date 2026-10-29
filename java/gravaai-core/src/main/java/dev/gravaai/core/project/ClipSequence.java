package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Clip-sequence rippling (port of {@code src/components/video-editor/clipSequence.ts}).
 *
 * <p>Primary footage is a sequence: source in-points survive every ripple edit, and trim
 * positions never determine clip order.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class ClipSequence {

	private ClipSequence() {}

	/** {@code closeClipGaps}: repack the sequence in timeline order. */
	public static List<ClipRegion> closeClipGaps(List<ClipRegion> clips) {
		return packClipSequence(Clips.sortClipRegions(clips));
	}

	/** {@code packClipSequence}: closes gaps while keeping each clip's source in-point. */
	public static List<ClipRegion> packClipSequence(List<ClipRegion> clips) {
		long cursor = 0;
		List<ClipRegion> packed = new ArrayList<>(clips.size());
		for (ClipRegion clip : clips) {
			long duration = clip.endMs() - clip.startMs();
			ClipRegion next = clip.startMs() == cursor
					? clip
					: new ClipRegion(clip.id(), cursor, cursor + duration, Clips.getClipSourceStartMs(clip),
							clip.sourceMinMs(), clip.sourceMaxMs(), clip.speed(), clip.muted(),
							clip.showSourceAudio(), clip.cameraLayout(), clip.transitionOut());
			cursor += duration;
			packed.add(next);
		}
		return packed;
	}

	/** {@code reorderClipSequence}: insert by sequence index, then repack. */
	public static List<ClipRegion> reorderClipSequence(List<ClipRegion> clips, String id, Integer sequenceIndex) {
		List<ClipRegion> ordered = new ArrayList<>(Clips.sortClipRegions(clips));
		int activeIndex = -1;
		for (int index = 0; index < ordered.size(); index++) {
			if (ordered.get(index).id().equals(id)) {
				activeIndex = index;
				break;
			}
		}
		if (activeIndex < 0 || sequenceIndex == null) {
			return closeClipGaps(clips);
		}

		ClipRegion active = ordered.remove(activeIndex);
		int insertAt = Math.max(0, Math.min(ordered.size(), Math.round(sequenceIndex.floatValue())));
		ordered.add(insertAt, active);
		return packClipSequence(ordered);
	}

	private static double safeSpeed(ClipRegion clip) {
		return Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
	}

	private static long mapWithinClip(double time, ClipRegion before, ClipRegion after) {
		double source = Clips.getClipSourceStartMs(before) + (time - before.startMs()) * safeSpeed(before);
		return Math.round(Math.max(after.startMs(), Math.min(after.endMs(),
				after.startMs() + (source - Clips.getClipSourceStartMs(after)) / safeSpeed(after))));
	}

	/** {@code mapClipSequenceTime}: map one anchor through a sequence edit. */
	public static long mapClipSequenceTime(double time, List<ClipRegion> before, List<ClipRegion> after) {
		if (before.isEmpty()) {
			return Math.round(time);
		}
		List<ClipRegion> sorted = Clips.sortClipRegions(before);
		ClipRegion owner = null;
		for (ClipRegion clip : sorted) {
			if (time >= clip.startMs() && time < clip.endMs()) {
				owner = clip;
				break;
			}
		}
		ClipRegion next = null;
		if (owner != null) {
			for (ClipRegion candidate : after) {
				if (candidate.id().equals(owner.id())) {
					next = candidate;
					break;
				}
			}
		}
		if (owner != null && next != null) {
			return mapWithinClip(time, owner, next);
		}

		long beforeEnd = sorted.stream().mapToLong(ClipRegion::endMs).max().orElse(0);
		long afterEnd = Math.max(0, after.stream().mapToLong(ClipRegion::endMs).max().orElse(0));
		if (time >= beforeEnd) {
			return Math.round(afterEnd + time - beforeEnd);
		}
		for (ClipRegion clip : sorted) {
			if (clip.startMs() >= time && after.stream().anyMatch(candidate -> candidate.id().equals(clip.id()))) {
				for (ClipRegion candidate : after) {
					if (candidate.id().equals(clip.id())) {
						return candidate.startMs();
					}
				}
			}
		}
		return afterEnd;
	}

	/** Span accessor used by the two generic ripple helpers. */
	public interface Span<T> {
		long startMs(T value);

		long endMs(T value);

		T withSpan(T value, long startMs, long endMs);
	}

	/** {@code rippleRegionAnchors}: imported audio keeps its duration, its anchor moves. */
	public static <T> List<T> rippleRegionAnchors(
			List<T> regions, List<ClipRegion> before, List<ClipRegion> after, Span<T> span) {
		List<T> result = new ArrayList<>(regions.size());
		for (T region : regions) {
			long startMs = mapClipSequenceTime(span.startMs(region), before, after);
			result.add(span.withSpan(region, startMs, startMs + span.endMs(region) - span.startMs(region)));
		}
		return result;
	}

	/** {@code rippleRegions}: map the retained footage covered by each connected effect. */
	public static <T> List<T> rippleRegions(
			List<T> regions, List<ClipRegion> before, List<ClipRegion> after, Span<T> span) {
		List<T> result = new ArrayList<>();
		for (T region : regions) {
			long regionStart = span.startMs(region);
			long regionEnd = span.endMs(region);
			List<long[]> retainedSpans = new ArrayList<>();
			for (ClipRegion clip : before) {
				ClipRegion next = null;
				for (ClipRegion candidate : after) {
					if (candidate.id().equals(clip.id())) {
						next = candidate;
						break;
					}
				}
				long start = Math.max(regionStart, clip.startMs());
				long end = Math.min(regionEnd, clip.endMs());
				if (next == null || end <= start) {
					continue;
				}
				long startMs = mapWithinClip(start, clip, next);
				long endMs = mapWithinClip(end, clip, next);
				if (endMs > startMs) {
					retainedSpans.add(new long[] {startMs, endMs});
				}
			}
			if (retainedSpans.isEmpty()) {
				continue;
			}
			long startMs = retainedSpans.stream().mapToLong(spanPair -> spanPair[0]).min().orElse(0);
			long endMs = retainedSpans.stream().mapToLong(spanPair -> spanPair[1]).max().orElse(0);
			result.add(span.withSpan(region, startMs, endMs));
		}
		return result;
	}

	public static final Span<AudioRegion> AUDIO_SPAN = new Span<>() {
		@Override
		public long startMs(AudioRegion value) {
			return value.startMs();
		}

		@Override
		public long endMs(AudioRegion value) {
			return value.endMs();
		}

		@Override
		public AudioRegion withSpan(AudioRegion value, long startMs, long endMs) {
			return new AudioRegion(value.id(), startMs, endMs, value.audioPath(), value.volume(),
					value.normalize(), value.trackIndex());
		}
	};

	public static final Span<ZoomRegion> ZOOM_SPAN = new Span<>() {
		@Override
		public long startMs(ZoomRegion value) {
			return value.startMs();
		}

		@Override
		public long endMs(ZoomRegion value) {
			return value.endMs();
		}

		@Override
		public ZoomRegion withSpan(ZoomRegion value, long startMs, long endMs) {
			return new ZoomRegion(value.id(), startMs, endMs, value.depth(), value.focus(), value.mode());
		}
	};

	public static final Span<AnnotationRegion> ANNOTATION_SPAN = new Span<>() {
		@Override
		public long startMs(AnnotationRegion value) {
			return value.startMs();
		}

		@Override
		public long endMs(AnnotationRegion value) {
			return value.endMs();
		}

		@Override
		public AnnotationRegion withSpan(AnnotationRegion value, long startMs, long endMs) {
			return new AnnotationRegion(value.id(), startMs, endMs, value.type(), value.content(),
					value.textContent(), value.imageContent(), value.position(), value.size(), value.style(),
					value.zIndex(), value.trackIndex(), value.figureData(), value.blurIntensity(),
					value.blurColor());
		}
	};
}
