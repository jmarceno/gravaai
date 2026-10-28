package dev.gravaai.media.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * The filter chain that produces a speed factor with libavfilter's {@code atempo} (plan §5.9:
 * "chain atempo for <0.5 or >2"). {@code atempo} accepts a factor in [0.5, 2], so slower/faster
 * regions are split into a sequence whose product is the requested rate.
 *
 * <p>The same output is used by the preview audio path and the export audio graph, so both hear the
 * identical speed change.
 */
public final class AtempoChain {

	public static final double MIN_FACTOR = 0.5;
	public static final double MAX_FACTOR = 2.0;

	private AtempoChain() {
	}

	/** The individual {@code atempo} factors whose product is {@code rate}. */
	public static List<Double> factors(double rate) {
		if (!Double.isFinite(rate) || rate <= 0) {
			throw new IllegalArgumentException("rate must be a finite positive number: " + rate);
		}
		List<Double> factors = new ArrayList<>();
		double remaining = rate;
		while (remaining < MIN_FACTOR) {
			factors.add(MIN_FACTOR);
			remaining /= MIN_FACTOR;
		}
		while (remaining > MAX_FACTOR) {
			factors.add(MAX_FACTOR);
			remaining /= MAX_FACTOR;
		}
		if (remaining != 1) {
			factors.add(remaining);
		}
		return List.copyOf(factors);
	}

	/** The filter strings for an ffmpeg filtergraph segment, in order. */
	public static List<String> filters(double rate) {
		List<String> filters = new ArrayList<>();
		for (double factor : factors(rate)) {
			filters.add("atempo=" + number(factor));
		}
		return List.copyOf(filters);
	}

	/** The filters joined with commas for one filtergraph chain; empty for rate 1. */
	public static String filterChain(double rate) {
		return String.join(",", filters(rate));
	}

	private static String number(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e15) {
			return Long.toString((long) value);
		}
		return Double.toString(value);
	}
}
