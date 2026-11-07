package dev.gravaai.core.project;

/**
 * A color/luminosity grade over a timeline span ({@code GradeRegion}): the element the user drops
 * on the Color track to brighten, warm up or desaturate the footage underneath it.
 *
 * <p>Brightness, contrast, saturation and temperature are signed values in {@code -100..100}
 * (0 = neutral); gamma is the midtone exponent in {@code 0.2..2.4} (1 = neutral). The values ride
 * the timeline: like every region they ripple with the clip sequence. {@code targets} picks the
 * layers the grade shades — the screen, the camera or both — so each picture can carry its own
 * calibration (two grades over one span, one per layer, is legal; they may not share a layer).
 */
public record GradeRegion(String id, long startMs, long endMs, double brightness, double contrast,
		double saturation, double temperature, double gamma, GradeTarget targets) {

	public static final double MIN_ADJUST = -100;
	public static final double MAX_ADJUST = 100;
	public static final double MIN_GAMMA = 0.2;
	public static final double MAX_GAMMA = 2.4;
	public static final double DEFAULT_GAMMA = 1;
	/** The layer choice every grade starts with: shade the recorded screen only. */
	public static final GradeTarget DEFAULT_TARGET = GradeTarget.MAIN;

	public GradeRegion {
		if (endMs < startMs) {
			endMs = startMs;
		}
		brightness = clamp(brightness, MIN_ADJUST, MAX_ADJUST, 0);
		contrast = clamp(contrast, MIN_ADJUST, MAX_ADJUST, 0);
		saturation = clamp(saturation, MIN_ADJUST, MAX_ADJUST, 0);
		temperature = clamp(temperature, MIN_ADJUST, MAX_ADJUST, 0);
		gamma = clamp(gamma, MIN_GAMMA, MAX_GAMMA, DEFAULT_GAMMA);
		// Lenient callers (old JSON without the field) read as screen-only.
		targets = targets == null ? DEFAULT_TARGET : targets;
	}

	/** The pre-targets constructor shape, for callers that do not care about the layers. */
	public GradeRegion(String id, long startMs, long endMs, double brightness, double contrast,
			double saturation, double temperature, double gamma) {
		this(id, startMs, endMs, brightness, contrast, saturation, temperature, gamma,
				DEFAULT_TARGET);
	}

	/** A neutral grade over the given span: every slider at its default. */
	public static GradeRegion neutral(String id, long startMs, long endMs) {
		return new GradeRegion(id, startMs, endMs, 0, 0, 0, 0, DEFAULT_GAMMA);
	}

	/** Copies this region with a new span, keeping the grading params. */
	public GradeRegion withSpan(long nextStartMs, long nextEndMs) {
		return new GradeRegion(id, nextStartMs, nextEndMs, brightness, contrast, saturation,
				temperature, gamma, targets);
	}

	/** Copies this region with new grading params, keeping the span and layers. */
	public GradeRegion withParams(double nextBrightness, double nextContrast,
			double nextSaturation, double nextTemperature, double nextGamma) {
		return new GradeRegion(id, startMs, endMs, nextBrightness, nextContrast, nextSaturation,
				nextTemperature, nextGamma, targets);
	}

	/** Copies this region with a new layer choice, keeping the span and sliders. */
	public GradeRegion withTargets(GradeTarget nextTargets) {
		return new GradeRegion(id, startMs, endMs, brightness, contrast, saturation, temperature,
				gamma, nextTargets);
	}

	/** True when no slider moved away from neutral, so the region changes nothing on screen. */
	public boolean isNeutral() {
		return brightness == 0 && contrast == 0 && saturation == 0 && temperature == 0
				&& gamma == DEFAULT_GAMMA;
	}

	/** The timeline label, e.g. {@code "Cam +20 warm"}; a neutral region reads "Grade". */
	public String label() {
		String sliders = sliderLabel();
		if (sliders.isEmpty()) {
			return targets == DEFAULT_TARGET ? "Grade" : targetPrefix() + "Grade";
		}
		return targetPrefix() + sliders;
	}

	private String sliderLabel() {
		StringBuilder label = new StringBuilder();
		if (brightness != 0) {
			label.append(label.isEmpty() ? "" : " ").append(signed(brightness)).append(" light");
		}
		if (temperature != 0) {
			label.append(label.isEmpty() ? "" : " ")
					.append(temperature > 0 ? "warm" : "cool");
		}
		if (saturation != 0) {
			label.append(label.isEmpty() ? "" : " ")
					.append(signed(saturation)).append(" sat");
		}
		if (contrast != 0) {
			label.append(label.isEmpty() ? "" : " ").append(signed(contrast)).append(" cont");
		}
		if (gamma != DEFAULT_GAMMA) {
			label.append(label.isEmpty() ? "" : " ")
					.append(String.format(java.util.Locale.ROOT, "γ%.2f", gamma));
		}
		return label.toString();
	}

	/** The layer tag ahead of the sliders: {@code "Cam "}, {@code "Both "}, or none. */
	private String targetPrefix() {
		return switch (targets) {
			case CAMERA -> "Cam ";
			case BOTH -> "Both ";
			case MAIN -> "";
		};
	}

	private static String signed(double value) {
		return String.format(java.util.Locale.ROOT, "%+.0f", value);
	}

	/** Clamps into {@code [min, max]}; a non-finite value falls back to {@code fallback}. */
	private static double clamp(double value, double min, double max, double fallback) {
		if (!Double.isFinite(value)) {
			return fallback;
		}
		return Math.max(min, Math.min(max, value));
	}
}
