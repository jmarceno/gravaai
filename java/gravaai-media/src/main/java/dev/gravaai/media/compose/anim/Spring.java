package dev.gravaai.media.compose.anim;

/**
 * A damped harmonic oscillator in closed form, as a pure function of the frame: starts at
 * {@code from} with zero velocity, moves to {@code to}, overshoots (when under-damped) and
 * settles. Frames are converted to seconds through {@code fps}, so the same curve is sampled more
 * often at a higher export rate instead of running faster.
 */
public final class Spring {

	/** Settling threshold used for {@link #durationInFrames}, as a fraction of the distance. */
	public static final double DEFAULT_THRESHOLD = 0.005;

	private static final int MAX_SCAN_SECONDS = 60;

	/**
	 * Spring configuration. Defaults: mass 1, damping 10, stiffness 100, no overshoot clamping,
	 * from 0 to 1, no delay, natural duration ({@code durationInFrames} 0), not reversed.
	 */
	public record Params(double mass, double damping, double stiffness, boolean overshootClamping,
			double from, double to, double delayFrames, double durationInFrames, boolean reverse) {

		public static final Params DEFAULT = new Params(1, 10, 100, false, 0, 1, 0, 0, false);

		public Params {
			if (!(mass > 0) || !(stiffness > 0) || !(damping >= 0)) {
				throw new IllegalArgumentException("spring needs mass > 0, stiffness > 0 and damping >= 0");
			}
			if (!Double.isFinite(from) || !Double.isFinite(to) || !Double.isFinite(delayFrames)
					|| !Double.isFinite(durationInFrames) || durationInFrames < 0) {
				throw new IllegalArgumentException("spring from/to/delay/durationInFrames must be finite");
			}
		}

		public Params withRange(double nextFrom, double nextTo) {
			return new Params(mass, damping, stiffness, overshootClamping, nextFrom, nextTo,
					delayFrames, durationInFrames, reverse);
		}
	}

	private Spring() {}

	/** The spring value at {@code frame}. */
	public static double value(double frame, double fps, Params params) {
		if (!(fps > 0)) {
			throw new IllegalArgumentException("fps must be positive");
		}
		double local = frame - params.delayFrames();
		double natural = params.durationInFrames() > 0 || params.reverse()
				? naturalDurationInFrames(fps, params, DEFAULT_THRESHOLD) : 0;
		double length = params.durationInFrames() > 0 ? params.durationInFrames() : natural;
		if (params.reverse()) {
			local = length - local;
		}
		if (params.durationInFrames() > 0 && natural > 0) {
			local = local * natural / params.durationInFrames();
		}
		double progress = progress(Math.max(0, local) / fps, params);
		if (params.overshootClamping()) {
			progress = Math.min(1, progress);
		}
		return params.from() + progress * (params.to() - params.from());
	}

	/**
	 * Frames until the spring stays within {@code threshold} of the target, delay included; the
	 * stretched {@code durationInFrames} wins when set.
	 */
	public static double durationInFrames(double fps, Params params, double threshold) {
		if (params.durationInFrames() > 0) {
			return params.durationInFrames() + params.delayFrames();
		}
		return naturalDurationInFrames(fps, params, threshold) + params.delayFrames();
	}

	private static double naturalDurationInFrames(double fps, Params params, double threshold) {
		int maxFrames = (int) Math.ceil(fps * MAX_SCAN_SECONDS);
		int lastOutside = 0;
		for (int frame = 0; frame <= maxFrames; frame++) {
			double p = progress(frame / fps, params);
			if (params.overshootClamping() && p >= 1) {
				return frame;
			}
			if (Math.abs(1 - p) >= threshold) {
				lastOutside = frame;
			} else if (frame - lastOutside > fps) {
				break;
			}
		}
		return lastOutside + 1;
	}

	/** Normalized progress 0 → 1 at {@code t} seconds. */
	private static double progress(double t, Params params) {
		double x0 = -1;
		double v0 = 0;
		double omega0 = Math.sqrt(params.stiffness() / params.mass());
		double zeta = params.damping() / (2 * Math.sqrt(params.stiffness() * params.mass()));
		double displacement;
		if (zeta < 1) {
			double omegaD = omega0 * Math.sqrt(1 - zeta * zeta);
			displacement = Math.exp(-zeta * omega0 * t) * (x0 * Math.cos(omegaD * t)
					+ (v0 + zeta * omega0 * x0) / omegaD * Math.sin(omegaD * t));
		} else if (zeta == 1) {
			displacement = Math.exp(-omega0 * t) * (x0 + (v0 + omega0 * x0) * t);
		} else {
			double root = Math.sqrt(zeta * zeta - 1);
			double r1 = -omega0 * (zeta - root);
			double r2 = -omega0 * (zeta + root);
			double c2 = (v0 - r1 * x0) / (r2 - r1);
			double c1 = x0 - c2;
			displacement = c1 * Math.exp(r1 * t) + c2 * Math.exp(r2 * t);
		}
		return 1 + displacement;
	}
}
