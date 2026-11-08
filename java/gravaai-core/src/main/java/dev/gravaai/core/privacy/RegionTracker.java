package dev.gravaai.core.privacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns per-sample detections into stable, timed regions — the part that stops auto masking from
 * flickering.
 *
 * <p>The analysis pass samples a recording at ~1 fps, so one face or one line of screen text is
 * seen a handful of times, and a naive one-region-per-detection output would blink on and off
 * between samples. Instead detections are matched into <em>tracks</em> by {@link #MATCH_IOU} and
 * each track becomes <em>one</em> region spanning the track's whole time range with the union of
 * its boxes. Screen PII (window chrome, name fields, chat) is mostly stationary, so a single
 * rectangle per track is both correct and the cheapest thing to draw; for a moving face the union
 * is a conservative superset, which errs toward masking too much rather than leaking.</p>
 *
 * <p>Small gaps are bridged: if the detector loses a face for a sample or two (a blink, a head
 * turn) the track continues as long as the next sighting is within
 * {@link #MAX_GAP_SAMPLES}. A longer absence is a genuinely new sighting, and producing two
 * separate annotations for it is honest — the region really was not detected in between.</p>
 *
 * <p>Pure logic: no I/O, no clocks, no ONNX — the whole class is unit- and property-tested with
 * synthetic box sequences.</p>
 */
public final class RegionTracker {

	/** IoU at which a detection continues an existing track rather than starting a new one. */
	public static final double MATCH_IOU = 0.4;
	/** Samples a track may go unseen and still continue (1 fps sampling, so ~2 s). */
	public static final int MAX_GAP_SAMPLES = 2;
	/** Hard cap on regions per recording, so a busy screen cannot flood the timeline. */
	public static final int MAX_REGIONS = 60;

	/** One sample: the detections of one frame, at {@code timeMs}. */
	public record Sample(long timeMs, List<DetectedRegion> regions) {

		public Sample {
			regions = List.copyOf(regions);
		}
	}

	/**
	 * A finished track: one region per kind, covering {@code [startMs, endMs]}.
	 *
	 * <p>{@code startMs}/{@code endMs} are the annotation's inclusive span; the tracker already
	 * applied {@link Builder#spanMarginMs} and clamped to the recording.</p>
	 */
	public record TimedRegion(DetectedRegion region, long startMs, long endMs) {

		public TimedRegion {
			if (endMs < startMs) {
				throw new IllegalArgumentException("region end before start: " + startMs + ".."
						+ endMs);
			}
		}
	}

	/**
	 * Tunables. Defaults follow the plan: sample margin 0.6 s, union padding 2% of the frame's
	 * short side.
	 */
	public record Options(long spanMarginMs, double paddingFraction) {

		public static final Options DEFAULTS = new Options(600, 0.02);

		public Options {
			if (spanMarginMs < 0) {
				throw new IllegalArgumentException("span margin must not be negative");
			}
			if (paddingFraction < 0) {
				throw new IllegalArgumentException("padding must not be negative");
			}
		}
	}

	/** A track under construction. */
	private static final class Track {
		private final DetectedRegion.Source source;
		private DetectedRegion bounds;
		private long firstMs;
		private long lastMs;
		private int lastSampleIndex;

		private Track(DetectedRegion first, long timeMs, int sampleIndex) {
			this.source = first.source();
			this.bounds = first;
			this.firstMs = timeMs;
			this.lastMs = timeMs;
			this.lastSampleIndex = sampleIndex;
		}

		private void extend(DetectedRegion next, long timeMs, int sampleIndex) {
			bounds = bounds.union(next);
			lastMs = timeMs;
			lastSampleIndex = sampleIndex;
		}
	}

	private final Options options;
	private final double frameWidth;
	private final double frameHeight;

	/**
	 * @param frameWidth  source-frame width in pixels
	 * @param frameHeight source-frame height in pixels
	 */
	public RegionTracker(Options options, double frameWidth, double frameHeight) {
		if (frameWidth <= 0 || frameHeight <= 0) {
			throw new IllegalArgumentException("frame size must be positive: " + frameWidth + "x"
					+ frameHeight);
		}
		this.options = options;
		this.frameWidth = frameWidth;
		this.frameHeight = frameHeight;
	}

	/**
	 * Builds the timed regions for a recording from its samples.
	 *
	 * @param samples        one entry per sampled frame, in time order
	 * @param recordingEndMs the recording's duration, for clamping the last track's margin
	 */
	public List<TimedRegion> track(List<Sample> samples, long recordingEndMs) {
		List<Track> tracks = new ArrayList<>();
		for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
			Sample sample = samples.get(sampleIndex);
			for (DetectedRegion region : sample.regions()) {
				Track best = null;
				double bestIou = MATCH_IOU;
				for (Track track : tracks) {
					if (track.source != region.source()
							|| sampleIndex - track.lastSampleIndex > MAX_GAP_SAMPLES) {
						continue;
					}
					double iou = track.bounds.iou(region);
					if (iou >= bestIou) {
						best = track;
						bestIou = iou;
					}
				}
				if (best == null) {
					tracks.add(new Track(region.clampedTo(frameWidth, frameHeight),
							sample.timeMs(), sampleIndex));
				} else {
					best.extend(region.clampedTo(frameWidth, frameHeight), sample.timeMs(),
							sampleIndex);
				}
			}
		}
		List<TimedRegion> regions = new ArrayList<>(tracks.size());
		double padding = options.paddingFraction() * Math.min(frameWidth, frameHeight);
		for (Track track : tracks) {
			DetectedRegion padded = track.bounds.padded(padding).clampedTo(frameWidth, frameHeight);
			long start = Math.max(0, track.firstMs - options.spanMarginMs());
			long end = Math.min(recordingEndMs, track.lastMs + options.spanMarginMs());
			regions.add(new TimedRegion(padded, start, Math.max(start, end)));
		}
		regions.sort(Comparator.comparingLong(TimedRegion::startMs)
				.thenComparingLong(TimedRegion::endMs));
		return regions.size() <= MAX_REGIONS ? List.copyOf(regions)
				: List.copyOf(regions.subList(0, MAX_REGIONS));
	}

}
