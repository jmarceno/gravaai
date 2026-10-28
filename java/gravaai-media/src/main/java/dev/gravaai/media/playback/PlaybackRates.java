package dev.gravaai.media.playback;

import java.util.HashMap;
import java.util.Map;

/**
 * Preview playback-rate support, ported from
 * {@code src/components/video-editor/videoPlayback/playbackRate.ts}.
 *
 * <p>The React version probes the runtime by assigning {@code video.playbackRate}; the Java engine
 * has a single runtime, so the probe is injected. {@link #engineProbe()} is the preview engine's
 * capability: every rate is produced by the atempo chain, and the UI offers quarter steps between
 * 0.25x and 30x (the widest range the Electron preview accepted, see the TS test fixtures).
 */
public final class PlaybackRates {

	/** The supported range of quarter-step playback rates. */
	public record Range(double min, double max) {
	}

	/** Ask the playback engine whether it accepts a rate. */
	@FunctionalInterface
	public interface RateProbe {
		boolean supports(double rate);
	}

	private static final double QUARTER_STEP = 0.25;
	private static final double LOWEST_RATE = 0.25;
	private static final double HIGHEST_RATE = 30;

	private final RateProbe probe;
	private final Map<Double, Boolean> supportedRates = new HashMap<>();

	public PlaybackRates(RateProbe probe) {
		if (probe == null) {
			throw new IllegalArgumentException("probe must not be null");
		}
		this.probe = probe;
	}

	/** The preview engine's own capability; see the class comment for the documented range. */
	public static RateProbe engineProbe() {
		return rate -> rate >= LOWEST_RATE && rate <= HIGHEST_RATE;
	}

	/** {@code supportsPreviewPlaybackRate}: probe once per rate and cache the answer. */
	public boolean supportsPreviewPlaybackRate(double rate) {
		if (!Double.isFinite(rate) || rate <= 0) {
			return false;
		}
		Boolean cached = supportedRates.get(rate);
		if (cached != null) {
			return cached;
		}
		boolean supported = probe.supports(rate);
		supportedRates.put(rate, supported);
		return supported;
	}

	/** Contiguous quarter-step range supported by the runtime, anchored at normal speed. */
	public Range getPreviewPlaybackRateRange() {
		double min = 1;
		double max = 1;
		while (min > QUARTER_STEP && supportsPreviewPlaybackRate(min - QUARTER_STEP)) {
			min -= QUARTER_STEP;
		}
		while (max < HIGHEST_RATE && supportsPreviewPlaybackRate(max + QUARTER_STEP)) {
			max += QUARTER_STEP;
		}
		return new Range(min, max);
	}
}
