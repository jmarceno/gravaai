package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

/**
 * Port of {@code src/components/video-editor/videoPlayback/motionSmoothing.ts}: the damped
 * harmonic oscillator used for zoom and cursor smoothing, plus the smoothing-factor → spring
 * configuration mapping.
 *
 * <p>Ported 1:1 (plan §8.5): the closed-form analytic solution for the underdamped, critically
 * damped and overdamped regimes, the overshoot guard for ζ ≥ 1, the forward-difference
 * analytical velocity and both configuration curves.</p>
 */
public final class SpringSmoothing {

	private static final double CURSOR_SMOOTHING_MIN = 0;
	private static final double CURSOR_SMOOTHING_MAX = 2;
	private static final double CURSOR_SMOOTHING_LEGACY_MAX = 0.5;
	private static final double DEFAULT_CURSOR_STIFFNESS_BOOST = 1.12;
	private static final double DEFAULT_REST_DELTA = 0.0005;
	private static final double DEFAULT_REST_SPEED = 0.02;

	private SpringSmoothing() {}

	/** Mutable spring state (the TS {@code SpringState}; tests mutate {@code value}). */
	public static final class SpringState {
		public double value;
		public double velocity;
		public boolean initialized;

		private SpringState(double value) {
			this.value = value;
		}
	}

	/**
	 * Spring constants. The TS interface has optional rest thresholds; the Java callers always
	 * provide both (see {@link #stepSpringValue} for the defaults applied when a config omits
	 * them, which Java cannot express — use {@link #withDefaults} to build one).
	 */
	public record SpringConfig(double stiffness, double damping, double mass, double restDelta,
			double restSpeed) {

		/** A config with the TS default rest thresholds. */
		public static SpringConfig withDefaults(double stiffness, double damping, double mass) {
			return new SpringConfig(stiffness, damping, mass, DEFAULT_REST_DELTA,
					DEFAULT_REST_SPEED);
		}
	}

	/** Optional cursor spring tuning multipliers (TS {@code CursorSpringTuning}). */
	public record CursorSpringTuning(@Nullable Double stiffnessMultiplier, @Nullable Double dampingMultiplier,
			@Nullable Double massMultiplier) {

		/** No tuning. */
		public static final CursorSpringTuning NONE = new CursorSpringTuning(null, null, null);

		public static CursorSpringTuning of(double stiffness, double damping, double mass) {
			return new CursorSpringTuning(stiffness, damping, mass);
		}
	}

	/** {@code createSpringState}. */
	public static SpringState createSpringState() {
		return createSpringState(0);
	}

	/** {@code createSpringState(initialValue)}. */
	public static SpringState createSpringState(double initialValue) {
		return new SpringState(initialValue);
	}

	/** {@code resetSpringState}. */
	public static void resetSpringState(SpringState state) {
		resetSpringState(state, null);
	}

	/**
	 * {@code resetSpringState(state, initialValue)}: resets velocity/initialized, and the value
	 * only when {@code initialValue} is non-null.
	 */
	public static void resetSpringState(SpringState state, @Nullable Double initialValue) {
		if (initialValue != null) {
			state.value = initialValue;
		}
		state.velocity = 0;
		state.initialized = false;
	}

	/** {@code clampDeltaMs(deltaMs)} with the 1000/60 fallback. */
	public static double clampDeltaMs(double deltaMs) {
		return clampDeltaMs(deltaMs, 1000.0 / 60.0);
	}

	/** {@code clampDeltaMs(deltaMs, fallbackMs)}. */
	public static double clampDeltaMs(double deltaMs, double fallbackMs) {
		if (!Double.isFinite(deltaMs) || deltaMs <= 0) {
			return fallbackMs;
		}
		return Math.min(80, Math.max(1, deltaMs));
	}

	private static double clampSpringMultiplier(@Nullable Double value) {
		if (value == null || !Double.isFinite(value)) {
			return 1;
		}
		return Math.min(3, Math.max(0.25, value));
	}

	private static SpringConfig applyCursorSpringTuning(SpringConfig config,
			CursorSpringTuning tuning) {
		CursorSpringTuning safeTuning = tuning == null ? CursorSpringTuning.NONE : tuning;
		return new SpringConfig(
				config.stiffness() * clampSpringMultiplier(safeTuning.stiffnessMultiplier()),
				config.damping() * clampSpringMultiplier(safeTuning.dampingMultiplier()),
				config.mass() * clampSpringMultiplier(safeTuning.massMultiplier()),
				config.restDelta(),
				config.restSpeed());
	}

	private static double msToSec(double ms) {
		return ms / 1000.0;
	}

	private static double resolveSpringPosition(double t, double target, double initialDelta,
			double initialVelocity, double dampingRatio, double undampedAngularFreq) {
		if (dampingRatio < 1) {
			double dampedFreq = undampedAngularFreq * Math.sqrt(1 - dampingRatio * dampingRatio);
			double envelope = Math.exp(-dampingRatio * undampedAngularFreq * t);
			return target - envelope
					* (((initialVelocity + dampingRatio * undampedAngularFreq * initialDelta)
							/ dampedFreq) * Math.sin(dampedFreq * t)
							+ initialDelta * Math.cos(dampedFreq * t));
		}
		if (dampingRatio == 1) {
			return target - Math.exp(-undampedAngularFreq * t)
					* (initialDelta + (initialVelocity + undampedAngularFreq * initialDelta) * t);
		}
		double dampedFreq = undampedAngularFreq * Math.sqrt(dampingRatio * dampingRatio - 1);
		double envelope = Math.exp(-dampingRatio * undampedAngularFreq * t);
		double freqT = Math.min(dampedFreq * t, 300);
		return target - (envelope
				* ((initialVelocity + dampingRatio * undampedAngularFreq * initialDelta)
						* Math.sinh(freqT)
						+ dampedFreq * initialDelta * Math.cosh(freqT)))
				/ dampedFreq;
	}

	/** {@code stepSpringValue}: advance the spring by one frame towards {@code target}. */
	public static double stepSpringValue(SpringState state, double target, double deltaMs,
			SpringConfig config) {
		double safeDeltaMs = clampDeltaMs(deltaMs);

		if (!state.initialized || !Double.isFinite(state.value)) {
			state.value = target;
			state.velocity = 0;
			state.initialized = true;
			return state.value;
		}

		double restDelta = config.restDelta();
		double restSpeed = config.restSpeed();

		if (Math.abs(target - state.value) <= restDelta
				&& Math.abs(state.velocity) <= restSpeed) {
			state.value = target;
			state.velocity = 0;
			return state.value;
		}

		double stiffness = config.stiffness();
		double damping = config.damping();
		double mass = config.mass();
		double undampedAngularFreq = Math.sqrt(stiffness / mass);
		double dampingRatio = damping / (2 * Math.sqrt(stiffness * mass));
		double initialDelta = target - state.value;
		double initialVelocity = -state.velocity;
		double tSec = msToSec(safeDeltaMs);

		double current = resolveSpringPosition(tSec, target, initialDelta, initialVelocity,
				dampingRatio, undampedAngularFreq);

		// Overshoot guard for ζ >= 1: a fixed-target overdamped spring cannot overshoot, but a
		// target that moves every frame can carry velocity past it; clamp instead of wobbling.
		if (dampingRatio >= 1) {
			boolean crossed = (state.value <= target && current > target)
					|| (state.value >= target && current < target);
			if (crossed) {
				state.value = target;
				state.velocity = 0;
				return state.value;
			}
		}

		double epsilon = 0.0001;
		double ahead = resolveSpringPosition(tSec + epsilon, target, initialDelta,
				initialVelocity, dampingRatio, undampedAngularFreq);
		double analyticalVelocity = (ahead - current) / epsilon;

		boolean belowVelocityThreshold = Math.abs(analyticalVelocity) <= restSpeed;
		boolean belowDisplacementThreshold = Math.abs(target - current) <= restDelta;
		if (belowVelocityThreshold && belowDisplacementThreshold) {
			state.value = target;
			state.velocity = 0;
		} else {
			state.value = current;
			state.velocity = analyticalVelocity;
		}
		return state.value;
	}

	/** {@code getCursorSpringConfig(smoothingFactor)}. */
	public static SpringConfig getCursorSpringConfig(double smoothingFactor) {
		return getCursorSpringConfig(smoothingFactor, CursorSpringTuning.NONE);
	}

	/** {@code getCursorSpringConfig(smoothingFactor, tuning)}. */
	public static SpringConfig getCursorSpringConfig(double smoothingFactor,
			CursorSpringTuning tuning) {
		double clamped = Math.min(CURSOR_SMOOTHING_MAX,
				Math.max(CURSOR_SMOOTHING_MIN, smoothingFactor));

		if (clamped <= 0) {
			return applyCursorSpringTuning(new SpringConfig(1000, 100, 1, 0.0001, 0.001), tuning);
		}

		if (clamped <= CURSOR_SMOOTHING_LEGACY_MAX) {
			double legacyNormalized = Math.min(1, Math.max(0,
					(clamped - CURSOR_SMOOTHING_MIN)
							/ (CURSOR_SMOOTHING_LEGACY_MAX - CURSOR_SMOOTHING_MIN)));
			return applyCursorSpringTuning(new SpringConfig(
					(760 - legacyNormalized * 420) * DEFAULT_CURSOR_STIFFNESS_BOOST,
					34 + legacyNormalized * 24,
					0.85 + legacyNormalized * 0.55,
					0.0002, 0.01), tuning);
		}

		double extendedNormalized = Math.min(1, Math.max(0,
				(clamped - CURSOR_SMOOTHING_LEGACY_MAX)
						/ (CURSOR_SMOOTHING_MAX - CURSOR_SMOOTHING_LEGACY_MAX)));
		return applyCursorSpringTuning(new SpringConfig(
				(340 - extendedNormalized * 180) * DEFAULT_CURSOR_STIFFNESS_BOOST,
				58 + extendedNormalized * 22,
				1.35 + extendedNormalized * 0.45,
				0.0002, 0.01), tuning);
	}

	/** {@code getZoomSpringConfig()} (default smoothness 0.5). */
	public static SpringConfig getZoomSpringConfig() {
		return getZoomSpringConfig(0.5);
	}

	/** {@code getZoomSpringConfig(smoothnessFactor)}. */
	public static SpringConfig getZoomSpringConfig(double smoothnessFactor) {
		return getZoomSpringConfig(smoothnessFactor, CursorSpringTuning.NONE);
	}

	/** {@code getZoomSpringConfig(smoothnessFactor, tuning)}. */
	public static SpringConfig getZoomSpringConfig(double smoothnessFactor,
			CursorSpringTuning tuning) {
		double clamped = Math.max(0, Math.min(1, smoothnessFactor));
		if (clamped <= 0) {
			return applyCursorSpringTuning(new SpringConfig(1000, 100, 1, 0.0001, 0.001), tuning);
		}
		double scaled = clamped * 2;
		return applyCursorSpringTuning(new SpringConfig(100 / scaled, 21, 1.0 * scaled,
				0.0005, 0.015), tuning);
	}
}
