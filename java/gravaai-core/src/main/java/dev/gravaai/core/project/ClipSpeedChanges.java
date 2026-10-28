package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Speed-change planning (port of {@code clipSpeedChange.ts}). */
public final class ClipSpeedChanges {

	/** TS {@code ClipSpeedChangeBlockReason}. */
	public enum BlockReason {
		CLIP_OVERLAP("clip-overlap"), ZOOM_OVERLAP("zoom-overlap");

		private final String wire;

		BlockReason(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}
	}

	public sealed interface Result permits Plan, Blocked {}

	public record Plan(List<ClipRegion> clipRegions, List<ZoomRegion> zoomRegions) implements Result {}

	public record Blocked(BlockReason blockedReason) implements Result {}

	private ClipSpeedChanges() {}

	/** {@code formatClipSpeedLabel}: null for 1x, invalid or missing speed. */
	public static String formatClipSpeedLabel(double speed) {
		if (!Double.isFinite(speed) || speed <= 0 || speed == 1) {
			return null;
		}
		String label = speed == Math.rint(speed)
				? Long.toString((long) speed)
				: Double.toString(speed);
		return label + "x";
	}

	private static boolean spansOverlap(long leftStart, long leftEnd, long rightStart, long rightEnd) {
		return leftStart < rightEnd && leftEnd > rightStart;
	}

	/** {@code planClipSpeedChange}. */
	public static Result planClipSpeedChange(
			List<ClipRegion> clipRegions, List<ZoomRegion> zoomRegions,
			String selectedClipId, double speed) {
		if (selectedClipId == null || !Double.isFinite(speed) || speed <= 0) {
			return null;
		}
		ClipRegion clip = null;
		for (ClipRegion candidate : clipRegions) {
			if (candidate.id().equals(selectedClipId)) {
				clip = candidate;
				break;
			}
		}
		if (clip == null) {
			return null;
		}

		double oldSpeed = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
		double sourceDurationMs = Math.max(0, clip.endMs() - clip.startMs()) * oldSpeed;
		long newEndMs = Math.round(clip.startMs() + sourceDurationMs / speed);
		ClipRegion nextClip = null;
		for (ClipRegion candidate : clipRegions) {
			if (!candidate.id().equals(selectedClipId) && candidate.startMs() >= clip.endMs()) {
				if (nextClip == null || candidate.startMs() < nextClip.startMs()) {
					nextClip = candidate;
				}
			}
		}
		if (nextClip != null && newEndMs > nextClip.startMs()) {
			return new Blocked(BlockReason.CLIP_OVERLAP);
		}

		double scaleFactor = oldSpeed / speed;
		List<ZoomRegion> nextZoomRegions = new ArrayList<>(zoomRegions.size());
		for (ZoomRegion zoom : zoomRegions) {
			if (zoom.startMs() < clip.startMs() || zoom.startMs() >= clip.endMs()) {
				nextZoomRegions.add(zoom);
			} else {
				nextZoomRegions.add(new ZoomRegion(zoom.id(),
						Math.round(clip.startMs() + (zoom.startMs() - clip.startMs()) * scaleFactor),
						Math.round(clip.startMs() + (zoom.endMs() - clip.startMs()) * scaleFactor),
						zoom.depth(), zoom.focus(), zoom.mode()));
			}
		}

		Set<String> changedZoomIds = new HashSet<>();
		for (int index = 0; index < nextZoomRegions.size(); index++) {
			ZoomRegion previous = zoomRegions.get(index);
			ZoomRegion next = nextZoomRegions.get(index);
			if (previous.startMs() != next.startMs() || previous.endMs() != next.endMs()) {
				changedZoomIds.add(next.id());
			}
		}

		boolean hasZoomOverlap = false;
		for (int index = 0; index < nextZoomRegions.size() && !hasZoomOverlap; index++) {
			ZoomRegion zoom = nextZoomRegions.get(index);
			for (int otherIndex = 0; otherIndex < nextZoomRegions.size(); otherIndex++) {
				if (index == otherIndex) {
					continue;
				}
				ZoomRegion other = nextZoomRegions.get(otherIndex);
				if ((changedZoomIds.contains(zoom.id()) || changedZoomIds.contains(other.id()))
						&& spansOverlap(zoom.startMs(), zoom.endMs(), other.startMs(), other.endMs())) {
					hasZoomOverlap = true;
					break;
				}
			}
		}
		if (hasZoomOverlap) {
			return new Blocked(BlockReason.ZOOM_OVERLAP);
		}

		List<ClipRegion> nextClips = new ArrayList<>(clipRegions.size());
		for (ClipRegion candidate : clipRegions) {
			nextClips.add(candidate.id().equals(selectedClipId)
					? new ClipRegion(candidate.id(), candidate.startMs(), newEndMs,
							candidate.sourceStartMs(), candidate.sourceMinMs(), candidate.sourceMaxMs(),
							speed, candidate.muted(), candidate.showSourceAudio(),
							candidate.cameraLayout(), candidate.transitionOut())
					: candidate);
		}
		return new Plan(List.copyOf(nextClips), List.copyOf(nextZoomRegions));
	}
}
