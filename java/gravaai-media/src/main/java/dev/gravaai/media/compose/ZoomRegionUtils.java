package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Port of {@code src/components/video-editor/videoPlayback/zoomRegionUtils.ts} (plan §5.8
 * stage 3): region strength over the timeline, dominant-region selection and the connected-zoom
 * handoff. Ported 1:1, including the animation delay, the overlap/early-out windows and the
 * chained-zoom gap rules.
 */
public final class ZoomRegionUtils {

	/** {@code CHAINED_ZOOM_PAN_GAP_MS}. */
	public static final double CHAINED_ZOOM_PAN_GAP_MS = 1350;

	/** {@code CONNECTED_ZOOM_PAN_DURATION_MS}. */
	public static final double CONNECTED_ZOOM_PAN_DURATION_MS = 1000;

	/** {@code ZOOM_IN_OVERLAP_MS}. */
	public static final double ZOOM_IN_OVERLAP_MS = 1000;

	/** {@code ZOOM_ANIMATION_DELAY_MS}. */
	public static final double ZOOM_ANIMATION_DELAY_MS = 500;

	private ZoomRegionUtils() {}

	/**
	 * Options for {@link #findDominantRegion} / {@link #computeRegionStrength}
	 * (TS {@code DominantRegionOptions}).
	 */
	public record DominantRegionOptions(@Nullable Boolean connectZooms, @Nullable Double zoomInDurationMs,
			@Nullable Double zoomOutDurationMs) {

		/** No options: disconnected zooms, default durations. */
		public static final DominantRegionOptions EMPTY =
				new DominantRegionOptions(null, null, null);

		public static DominantRegionOptions connectZooms(boolean connect) {
			return new DominantRegionOptions(connect, null, null);
		}

		public static DominantRegionOptions durations(double zoomInDurationMs,
				double zoomOutDurationMs) {
			return new DominantRegionOptions(null, zoomInDurationMs, zoomOutDurationMs);
		}
	}

	/** TS {@code ConnectedPanTransition}; the current algorithm always reports {@code null}. */
	public record ConnectedPanTransition(double progress, ZoomFocus startFocus, ZoomFocus endFocus,
			double startScale, double endScale) {
	}

	/** Result of {@link #findDominantRegion}. */
	public record DominantRegionResult(@Nullable ZoomRegionSpec region, double strength,
			@Nullable Double blendedScale, @Nullable ConnectedPanTransition transition) {

		static DominantRegionResult none() {
			return new DominantRegionResult(null, 0, null, null);
		}
	}

	private record ConnectedRegionPair(ZoomRegionSpec currentRegion, ZoomRegionSpec nextRegion,
			double transitionStart, double transitionEnd) {
	}

	/** {@code computeRegionStrength(region, timeMs)} with default durations. */
	public static double computeRegionStrength(ZoomRegionSpec region, double timeMs) {
		return computeRegionStrength(region, timeMs, DominantRegionOptions.EMPTY);
	}

	/** {@code computeRegionStrength(region, timeMs, options)}. */
	public static double computeRegionStrength(ZoomRegionSpec region, double timeMs,
			DominantRegionOptions options) {
		DominantRegionOptions safeOptions = options == null ? DominantRegionOptions.EMPTY : options;
		double zoomInDurationMs = Math.max(1,
				safeOptions.zoomInDurationMs() == null
						? ZoomConstants.ZOOM_IN_TRANSITION_WINDOW_MS
						: safeOptions.zoomInDurationMs());
		double zoomOutDurationMs = Math.max(1,
				safeOptions.zoomOutDurationMs() == null
						? ZoomConstants.TRANSITION_WINDOW_MS
						: safeOptions.zoomOutDurationMs());
		double adjustedTimeMs = timeMs - ZOOM_ANIMATION_DELAY_MS;
		double leadInStart = region.startMs() + ZOOM_IN_OVERLAP_MS
				- ZoomConstants.ZOOM_IN_TRANSITION_WINDOW_MS;
		double zoomOutStart = region.endMs() - ZoomConstants.ZOOM_OUT_EARLY_START_MS;
		double zoomInEnd = leadInStart + zoomInDurationMs;

		if (zoomInEnd > zoomOutStart) {
			double midpoint = (zoomInEnd + zoomOutStart) / 2;
			zoomInEnd = midpoint;
			zoomOutStart = midpoint;
		}

		double leadOutEnd = zoomOutStart + zoomOutDurationMs;
		if (adjustedTimeMs < leadInStart || adjustedTimeMs > leadOutEnd) {
			return 0;
		}
		if (adjustedTimeMs < zoomInEnd) {
			double progress = (adjustedTimeMs - leadInStart) / zoomInDurationMs;
			return MathUtils.easeOutZoom(progress);
		}
		if (adjustedTimeMs <= zoomOutStart) {
			return 1;
		}
		double progress = MathUtils.clamp01((adjustedTimeMs - zoomOutStart) / zoomOutDurationMs);
		return 1 - MathUtils.easeOutZoom(progress);
	}

	private static ZoomFocus getResolvedFocus(ZoomRegionSpec region, double zoomScale) {
		return FocusUtils.clampFocusToScale(region.focus(), zoomScale);
	}

	private static List<ConnectedRegionPair> getConnectedRegionPairs(
			List<ZoomRegionSpec> regions) {
		List<ZoomRegionSpec> sortedRegions = new ArrayList<>(regions);
		sortedRegions.sort(Comparator.comparingInt(ZoomRegionSpec::startMs));
		List<ConnectedRegionPair> pairs = new ArrayList<>();
		for (int index = 0; index < sortedRegions.size() - 1; index += 1) {
			ZoomRegionSpec currentRegion = sortedRegions.get(index);
			ZoomRegionSpec nextRegion = sortedRegions.get(index + 1);
			double gapMs = nextRegion.startMs() - currentRegion.endMs();
			if (gapMs > CHAINED_ZOOM_PAN_GAP_MS) {
				continue;
			}
			pairs.add(new ConnectedRegionPair(currentRegion, nextRegion,
					currentRegion.endMs() + ZOOM_ANIMATION_DELAY_MS,
					currentRegion.endMs() + ZOOM_ANIMATION_DELAY_MS
							+ CONNECTED_ZOOM_PAN_DURATION_MS));
		}
		return pairs;
	}

	private static @Nullable DominantRegionResult getActiveRegion(List<ZoomRegionSpec> regions,
			double timeMs, List<ConnectedRegionPair> connectedPairs,
			DominantRegionOptions options) {
		record ActiveRegion(ZoomRegionSpec region, double strength) {
		}
		List<ActiveRegion> activeRegions = new ArrayList<>();
		for (ZoomRegionSpec region : regions) {
			double strength;
			ConnectedRegionPair outgoingPair = connectedPairs.stream()
					.filter(pair -> pair.currentRegion().id().equals(region.id()))
					.findFirst().orElse(null);
			if (outgoingPair != null) {
				if (timeMs >= outgoingPair.transitionStart()) {
					strength = 0;
					activeRegions.add(new ActiveRegion(region, strength));
					continue;
				}
				double zoomOutStart = outgoingPair.currentRegion().endMs()
						- ZoomConstants.ZOOM_OUT_EARLY_START_MS + ZOOM_ANIMATION_DELAY_MS;
				if (timeMs >= zoomOutStart) {
					activeRegions.add(new ActiveRegion(region, 1));
					continue;
				}
			}
			ConnectedRegionPair incomingPair = connectedPairs.stream()
					.filter(pair -> pair.nextRegion().id().equals(region.id()))
					.findFirst().orElse(null);
			if (incomingPair != null) {
				if (timeMs < incomingPair.transitionStart()) {
					activeRegions.add(new ActiveRegion(region, 0));
					continue;
				}
				double nextRegionZoomOutStart = incomingPair.nextRegion().endMs()
						- ZoomConstants.ZOOM_OUT_EARLY_START_MS + ZOOM_ANIMATION_DELAY_MS;
				if (timeMs < nextRegionZoomOutStart) {
					activeRegions.add(new ActiveRegion(region, 1));
					continue;
				}
			}
			activeRegions.add(new ActiveRegion(region,
					computeRegionStrength(region, timeMs, options)));
		}
		activeRegions.removeIf(entry -> !(entry.strength() > 0));
		activeRegions.sort((left, right) -> {
			if (right.strength() != left.strength()) {
				return Double.compare(right.strength(), left.strength());
			}
			return Integer.compare(right.region().startMs(), left.region().startMs());
		});
		if (activeRegions.isEmpty()) {
			return null;
		}
		ZoomRegionSpec activeRegion = activeRegions.get(0).region();
		double activeScale = activeRegion.scale();
		return new DominantRegionResult(
				activeRegion.withFocus(getResolvedFocus(activeRegion, activeScale)),
				activeRegions.get(0).strength(), null, null);
	}

	private static @Nullable DominantRegionResult getConnectedRegionHold(double timeMs,
			List<ConnectedRegionPair> connectedPairs) {
		for (ConnectedRegionPair pair : connectedPairs) {
			if (timeMs >= pair.transitionEnd() && timeMs < pair.nextRegion().startMs()) {
				ZoomRegionSpec nextRegion = pair.nextRegion();
				double nextScale = nextRegion.scale();
				return new DominantRegionResult(
						nextRegion.withFocus(getResolvedFocus(nextRegion, nextScale)), 1, null,
						null);
			}
		}
		return null;
	}

	/** {@code findDominantRegion(regions, timeMs)}. */
	public static DominantRegionResult findDominantRegion(List<ZoomRegionSpec> regions,
			double timeMs) {
		return findDominantRegion(regions, timeMs, DominantRegionOptions.EMPTY);
	}

	/** {@code findDominantRegion(regions, timeMs, options)}. */
	public static DominantRegionResult findDominantRegion(List<ZoomRegionSpec> regions,
			double timeMs, DominantRegionOptions options) {
		DominantRegionOptions safeOptions = options == null ? DominantRegionOptions.EMPTY : options;
		List<ZoomRegionSpec> safeRegions = regions == null ? List.of() : regions;
		boolean connectZooms = Boolean.TRUE.equals(safeOptions.connectZooms());
		List<ConnectedRegionPair> connectedPairs = connectZooms
				? getConnectedRegionPairs(safeRegions)
				: List.of();
		if (connectZooms) {
			DominantRegionResult connectedHold = getConnectedRegionHold(timeMs, connectedPairs);
			if (connectedHold != null) {
				return connectedHold;
			}
		}
		DominantRegionResult activeRegion = getActiveRegion(safeRegions, timeMs, connectedPairs,
				safeOptions);
		return activeRegion != null ? activeRegion : DominantRegionResult.none();
	}
}
