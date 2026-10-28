package dev.gravaai.media.export;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.SpeedRegion;
import dev.gravaai.core.project.TrimRegion;

/**
 * Edited-track fast-path decisions (port of {@code src/lib/exporter/editedTrackStrategy.ts},
 * plan §5.10): which trims/speed regions can be expressed as an ffmpeg atempo filter graph.
 */
public final class ExportAudioSegments {

	public static final double MIN_FILTERGRAPH_SPEED = 0.5;
	public static final double MAX_FILTERGRAPH_SPEED = 2;

	/** {@code EditedTrackStrategy}. */
	public enum Strategy {
		FILTERGRAPH_FAST_PATH,
		OFFLINE_RENDER_FALLBACK
	}

	/** A trim boundary in source ms. */
	public record Trim(double startMs, double endMs) {
		public static Trim of(TrimRegion region) {
			return new Trim(region.startMs(), region.endMs());
		}
	}

	/** A speed region in source ms. */
	public record Speed(double startMs, double endMs, double speed) {
		public static Speed of(SpeedRegion region) {
			return new Speed(region.startMs(), region.endMs(), region.speed());
		}
	}

	/** {@code EditedTrackSourceSegment}. */
	public record Segment(double startMs, double endMs, double speed) {}

	/** {@code EditedTrackStrategyInput}. */
	public record StrategyInput(String primaryAudioSourcePath, double sourceDurationMs,
			List<Trim> trimRegions, List<Speed> speedRegions, List<AudioRegion> audioRegions,
			List<String> sourceAudioFallbackPaths) {}

	private ExportAudioSegments() {}

	/** {@code buildEditedTrackSourceSegments}. */
	public static List<Segment> buildEditedTrackSourceSegments(double sourceDurationMs,
			List<Trim> trimRegions, List<Speed> speedRegions) {
		if (!Double.isFinite(sourceDurationMs) || sourceDurationMs <= 0) {
			return List.of();
		}
		List<Speed> safeSpeedRegions = speedRegions == null ? List.of() : speedRegions;
		for (Speed region : safeSpeedRegions) {
			if (!hasFiniteTimelineRange(region.startMs(), region.endMs(), sourceDurationMs)
					|| !isSafeFiltergraphSpeed(region.speed())) {
				return List.of();
			}
		}
		List<Segment> segments = new ArrayList<>();
		List<Trim> safeTrimRegions = trimRegions == null ? List.of() : trimRegions;
		for (KeptRange keptRange : buildKeptRanges(sourceDurationMs, safeTrimRegions)) {
			TreeSet<Double> boundaries = new TreeSet<>();
			boundaries.add(keptRange.startMs());
			boundaries.add(keptRange.endMs());
			for (Speed region : safeSpeedRegions) {
				double startMs = Math.max(keptRange.startMs(), region.startMs());
				double endMs = Math.min(keptRange.endMs(), region.endMs());
				if (endMs > startMs) {
					boundaries.add(startMs);
					boundaries.add(endMs);
				}
			}
			List<Double> ordered = new ArrayList<>(boundaries);
			for (int index = 0; index < ordered.size() - 1; index++) {
				double startMs = ordered.get(index);
				double endMs = ordered.get(index + 1);
				if (endMs - startMs <= 0.5) {
					continue;
				}
				double midpointMs = startMs + (endMs - startMs) / 2;
				double speed = 1;
				for (Speed region : safeSpeedRegions) {
					if (midpointMs >= region.startMs() && midpointMs < region.endMs()) {
						speed = region.speed();
						break;
					}
				}
				if (!isSafeFiltergraphSpeed(speed)) {
					return List.of();
				}
				segments.add(new Segment(startMs, endMs, speed));
			}
		}
		return List.copyOf(segments);
	}

	/** {@code classifyEditedTrackStrategy}. */
	public static Strategy classifyEditedTrackStrategy(StrategyInput input) {
		if (input.primaryAudioSourcePath() == null
				|| input.primaryAudioSourcePath().isEmpty()) {
			return Strategy.OFFLINE_RENDER_FALLBACK;
		}
		if (input.audioRegions() != null && !input.audioRegions().isEmpty()) {
			return Strategy.OFFLINE_RENDER_FALLBACK;
		}
		if (input.speedRegions() == null || input.speedRegions().isEmpty()) {
			return Strategy.OFFLINE_RENDER_FALLBACK;
		}
		for (Speed region : input.speedRegions()) {
			if (!hasFiniteTimelineRange(region.startMs(), region.endMs(), input.sourceDurationMs())
					|| !isSafeFiltergraphSpeed(region.speed())) {
				return Strategy.OFFLINE_RENDER_FALLBACK;
			}
		}
		if (input.sourceAudioFallbackPaths() != null && input.sourceAudioFallbackPaths().stream()
				.anyMatch(path -> !path.equals(input.primaryAudioSourcePath()))) {
			return Strategy.OFFLINE_RENDER_FALLBACK;
		}
		if (buildEditedTrackSourceSegments(input.sourceDurationMs(),
				input.trimRegions(), input.speedRegions()).isEmpty()) {
			return Strategy.OFFLINE_RENDER_FALLBACK;
		}
		return Strategy.FILTERGRAPH_FAST_PATH;
	}

	private static boolean isSafeFiltergraphSpeed(double speed) {
		return Double.isFinite(speed) && speed >= MIN_FILTERGRAPH_SPEED
				&& speed <= MAX_FILTERGRAPH_SPEED;
	}

	private static boolean hasFiniteTimelineRange(double startMs, double endMs,
			double sourceDurationMs) {
		return Double.isFinite(startMs) && Double.isFinite(endMs) && startMs >= 0
				&& endMs > startMs && endMs <= sourceDurationMs;
	}

	private record KeptRange(double startMs, double endMs) {}

	private static List<KeptRange> buildKeptRanges(double sourceDurationMs, List<Trim> trimRegions) {
		List<Trim> sorted = new ArrayList<>(trimRegions);
		sorted.sort(java.util.Comparator.comparingDouble(Trim::startMs));
		List<KeptRange> keptRanges = new ArrayList<>();
		double cursorMs = 0;
		for (Trim trim : sorted) {
			double startMs = Math.max(0, trim.startMs());
			double endMs = Math.min(sourceDurationMs, trim.endMs());
			if (endMs <= startMs) {
				continue;
			}
			if (startMs > cursorMs) {
				keptRanges.add(new KeptRange(cursorMs, startMs));
			}
			cursorMs = Math.max(cursorMs, endMs);
		}
		if (cursorMs < sourceDurationMs) {
			keptRanges.add(new KeptRange(cursorMs, sourceDurationMs));
		}
		return keptRanges;
	}
}
