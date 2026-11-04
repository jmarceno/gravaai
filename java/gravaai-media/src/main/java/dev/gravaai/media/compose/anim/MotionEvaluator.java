package dev.gravaai.media.compose.anim;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.gravaai.core.project.ElementMotion;

/**
 * Evaluates an element's host-applied {@link ElementMotion} tracks at a local time with the same
 * kernel components use, so a curve written as data and the same curve written in code agree.
 * Values hold before the first key and after the last (clamp); each segment is shaped by the
 * easing of the key it ends at.
 */
public final class MotionEvaluator {

	private static final Map<String, Easing> EASINGS = new ConcurrentHashMap<>();

	private MotionEvaluator() {}

	/** The element transform at one instant. Offsets in percent of the output frame. */
	public record Pose(double opacity, double translateX, double translateY, double scale,
			double rotate) {

		public static final Pose REST = new Pose(1, 0, 0, 1, 0);

		public boolean isRest() {
			return opacity == 1 && translateX == 0 && translateY == 0 && scale == 1 && rotate == 0;
		}
	}

	public static Pose evaluate(List<ElementMotion.Track> tracks, double localMs, long durationMs) {
		if (tracks.isEmpty()) {
			return Pose.REST;
		}
		double opacity = 1;
		double translateX = 0;
		double translateY = 0;
		double scale = 1;
		double rotate = 0;
		for (ElementMotion.Track track : tracks) {
			double value = value(track, localMs, durationMs);
			switch (track.property()) {
				case OPACITY -> opacity = Math.max(0, Math.min(1, value));
				case TRANSLATE_X -> translateX = value;
				case TRANSLATE_Y -> translateY = value;
				case SCALE -> scale = Math.max(0, value);
				case ROTATE -> rotate = value;
			}
		}
		return new Pose(opacity, translateX, translateY, scale, rotate);
	}

	/** One track's value at {@code localMs}. */
	public static double value(ElementMotion.Track track, double localMs, long durationMs) {
		List<ElementMotion.Keyframe> keys = track.resolved(durationMs);
		ElementMotion.Keyframe first = keys.getFirst();
		if (localMs <= first.resolve(durationMs)) {
			return first.value();
		}
		for (int index = 1; index < keys.size(); index++) {
			ElementMotion.Keyframe previous = keys.get(index - 1);
			ElementMotion.Keyframe next = keys.get(index);
			long start = previous.resolve(durationMs);
			long end = next.resolve(durationMs);
			if (localMs <= end) {
				if (end <= start) {
					return next.value();
				}
				double progress = (localMs - start) / (double) (end - start);
				double eased = easing(next.easing()).apply(progress);
				return previous.value() + (next.value() - previous.value()) * eased;
			}
		}
		return keys.getLast().value();
	}

	/** True when {@code name} is an easing this evaluator understands. */
	public static boolean isKnownEasing(String name) {
		return Easing.parse(name).isPresent();
	}

	/** Accepted easing names, for help text and errors. */
	public static String easingNames() {
		return Easing.names();
	}

	private static Easing easing(String name) {
		return EASINGS.computeIfAbsent(name, key -> Easing.parse(key).orElse(Easing.LINEAR));
	}
}
