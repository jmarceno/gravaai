package dev.gravaai.media.compose.anim;

import java.util.Locale;

/**
 * Piecewise-linear mapping from an input range to an output range with easing and extrapolation:
 * the keyframe primitive of the element layer ({@code interpolate(frame, [0, 20], [0, 1])}).
 *
 * <p>Both ranges have the same length (at least 2) and the input range is non-decreasing. The
 * active segment is the one containing the input (the last segment for inputs past the end); the
 * easing applies to the normalized progress inside that segment. Outside the input range the
 * extrapolation of that side decides the result.
 */
public final class Interpolation {

	/** What happens outside the input range. */
	public enum Extrapolation {
		/** Keep the slope of the edge segment (the default). */
		EXTEND,
		/** Hold the edge output value. */
		CLAMP,
		/** Return the input itself. */
		IDENTITY,
		/** Wrap the input around the range, so the curve repeats. */
		WRAP;

		public static Extrapolation parse(String value) {
			return switch (value.trim().toLowerCase(Locale.ROOT)) {
				case "extend" -> EXTEND;
				case "clamp" -> CLAMP;
				case "identity" -> IDENTITY;
				case "wrap" -> WRAP;
				default -> throw new IllegalArgumentException("unknown extrapolation \"" + value
						+ "\"; use extend, clamp, identity or wrap");
			};
		}
	}

	/** Easing plus the extrapolation of each side. */
	public record Options(Easing easing, Extrapolation left, Extrapolation right) {
		public static final Options DEFAULT =
				new Options(Easing.LINEAR, Extrapolation.EXTEND, Extrapolation.EXTEND);

		public static Options clamped(Easing easing) {
			return new Options(easing, Extrapolation.CLAMP, Extrapolation.CLAMP);
		}
	}

	private Interpolation() {}

	public static double interpolate(double input, double[] inRange, double[] outRange) {
		return interpolate(input, inRange, outRange, Options.DEFAULT);
	}

	public static double interpolate(double input, double[] inRange, double[] outRange,
			Options options) {
		validate(inRange, outRange);
		if (Double.isNaN(input)) {
			return input;
		}
		int last = inRange.length - 1;
		double first = inRange[0];
		double end = inRange[last];
		if (input < first || input > end) {
			Extrapolation side = input < first ? options.left() : options.right();
			switch (side) {
				case CLAMP -> {
					return input < first ? outRange[0] : outRange[last];
				}
				case IDENTITY -> {
					return input;
				}
				case WRAP -> {
					double span = end - first;
					if (span > 0) {
						double wrapped = first + ((input - first) % span + span) % span;
						return interpolate(wrapped, inRange, outRange, options);
					}
					return outRange[0];
				}
				case EXTEND -> {
					// Falls through to the segment maths with the edge segment.
				}
			}
		}
		int segment = 0;
		while (segment < last - 1 && input > inRange[segment + 1]) {
			segment++;
		}
		double inStart = inRange[segment];
		double inEnd = inRange[segment + 1];
		double outStart = outRange[segment];
		double outEnd = outRange[segment + 1];
		if (inEnd == inStart) {
			return input < inStart ? outStart : outEnd;
		}
		double progress = (input - inStart) / (inEnd - inStart);
		double eased = progress >= 0 && progress <= 1 ? options.easing().apply(progress) : progress;
		return outStart + eased * (outEnd - outStart);
	}

	private static void validate(double[] inRange, double[] outRange) {
		if (inRange.length != outRange.length) {
			throw new IllegalArgumentException("inputRange (" + inRange.length
					+ ") and outputRange (" + outRange.length + ") must have the same length");
		}
		if (inRange.length < 2) {
			throw new IllegalArgumentException("inputRange needs at least 2 values");
		}
		for (int index = 0; index < inRange.length; index++) {
			if (!Double.isFinite(inRange[index]) || !Double.isFinite(outRange[index])) {
				throw new IllegalArgumentException("ranges must contain finite numbers");
			}
			if (index > 0 && inRange[index] < inRange[index - 1]) {
				throw new IllegalArgumentException(
						"inputRange must be non-decreasing, got " + inRange[index - 1] + " then "
								+ inRange[index]);
			}
		}
	}
}
