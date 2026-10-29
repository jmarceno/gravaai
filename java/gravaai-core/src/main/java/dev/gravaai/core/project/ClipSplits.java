package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Splitting the clip under the playhead (port of {@code clipSplit.ts}).
 *
 * <p>{@code splitMs} is a timeline position, so the halves stay put on the timeline and only their
 * source in-points differ. At non-1x speed the split consumes more (or less) source than timeline,
 * so the right half reads from {@code getClipSourceEndMs(left)}.
 */
public final class ClipSplits {

	public record Plan(String targetId, ClipRegion left, ClipRegion right) {}

	private ClipSplits() {}

	public static @Nullable Plan planClipSplit(
			List<ClipRegion> clipRegions, double splitMs, Supplier<String> createId) {
		if (!Double.isFinite(splitMs)) {
			return null;
		}
		long splitAtMs = Math.round(splitMs);
		ClipRegion target = null;
		for (ClipRegion clip : clipRegions) {
			if (splitAtMs > clip.startMs() && splitAtMs < clip.endMs()) {
				target = clip;
				break;
			}
		}
		if (target == null) {
			return null;
		}

		ClipRegion left = new ClipRegion(createId.get(), target.startMs(), splitAtMs,
				target.sourceStartMs(), target.sourceMinMs(), target.sourceMaxMs(), target.speed(),
				target.muted(), target.showSourceAudio(), target.cameraLayout(), null);
		ClipRegion right = new ClipRegion(createId.get(), splitAtMs, target.endMs(),
				Clips.getClipSourceEndMs(left), target.sourceMinMs(), target.sourceMaxMs(),
				target.speed(), target.muted(), target.showSourceAudio(), target.cameraLayout(),
				target.transitionOut());
		return new Plan(target.id(), left, right);
	}
}
