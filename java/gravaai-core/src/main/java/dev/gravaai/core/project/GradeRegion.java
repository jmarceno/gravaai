package dev.gravaai.core.project;

/**
 * A color/luminosity grade over a timeline span ({@code GradeRegion}): the element the user drops
 * on the Color track to brighten, warm up or desaturate the footage underneath it.
 *
 * <p>Brightness, contrast, saturation and temperature are signed values in {@code -100..100}
 * (0 = neutral); gamma is the midtone exponent in {@code 0.2..2.4} (1 = neutral). The values ride
 * the timeline: like every region they ripple with the clip sequence.
 */
public record GradeRegion(String id, long startMs, long endMs, double brightness, double contrast,
		double saturation, double temperature, double gamma) {

	public static final double MIN_ADJUST = -100;
	public static final double MAX_ADJUST = 100;
	public static final double MIN_GAMMA = 0.2;
	public static final double MAX_GAMMA = 2.4;
	public static final double DEFAULT_GAMMA = 1;

	public GradeRegion {
		if (endMs < startMs) {
			endMs = startMs;
		}
		brightness = clamp(brightness, MIN_ADJUST, MAX_ADJUST, 0);
		contrast = clamp(contrast, MIN_ADJUST, MAX_ADJUST, 0);
		saturation = clamp(saturation, MIN_ADJUST, MAX_ADJUST, 0);
		temperature = clamp(temperature, MIN_ADJUST, MAX_ADJUST, 0);
		gamma = clamp(gamma, MIN_GAMMA, MAX_GAMMA, DEFAULT_GAMMA);
	}

	/** A neutral grade over the given span: every slider at its default. */
	public static GradeRegion neutral(String id, long startMs, long endMs) {
		return new GradeRegion(id, startMs, endMs, 0, 0, 0, 0, DEFAULT_GAMMA);
	}

	/** Copies this region with a new span, keeping the grading params. */
	public GradeRegion withSpan(long nextStartMs, long nextEndMs) {
		return new GradeRegion(id, nextStartMs, nextEndMs, brightness, contrast, saturation,
				temperature, gamma);
	}

	/** Copies this region with new grading params, keeping the span. */
	public GradeRegion withParams(double nextBrightness, double nextContrast,
			double nextSaturation, double nextTemperature, double nextGamma) {
		return new GradeRegion(id, startMs, endMs, nextBrightness, nextContrast, nextSaturation,
				nextTemperature, nextGamma);
	}

	/** True when no slider moved away from neutral, so the region changes nothing on screen. */
	public boolean isNeutral() {
		return brightness == 0 && contrast == 0 && saturation == 0 && temperature == 0
				&& gamma == DEFAULT_GAMMA;
	}

	/** The timeline label, e.g. {@code "+20 warm"}; a neutral region reads "Grade". */
	public String label() {
		if (isNeutral()) {
			return "Grade";
		}
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
