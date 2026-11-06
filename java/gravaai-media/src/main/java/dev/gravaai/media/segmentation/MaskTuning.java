package dev.gravaai.media.segmentation;

/**
 * How the background-blur mask is shaped, per project: the user-facing controls behind
 * Webcam → Advanced.
 *
 * <p>{@code precision} controls how crisp the mask edge is — a softer edge blends the person into
 * the blurred room over more pixels, a harder edge cuts them out — and {@code offsetPercent} moves
 * the edge towards or away from the detected subject, in percent of the mask's short side, which
 * fixes a mask that clips the person's hair, shoulders or shirt collar.</p>
 *
 * <p>Defaults reproduce the shape the blur shipped with, so a project that never touches the
 * controls renders exactly as before. The mapping lives here rather than in the segmenter so the
 * ranges are unit-testable without the inference runtime.</p>
 *
 * @param precision 0..1, where 0 is the softest edge, 1 the hardest and 0.5 the shipped shape
 * @param offsetPercent -5..5, positive growing the mask past the subject, negative pulling it in
 */
public record MaskTuning(double precision, double offsetPercent) {

	public static final double DEFAULT_PRECISION = 0.5;
	public static final double DEFAULT_OFFSET_PERCENT = 0;
	public static final double MIN_OFFSET_PERCENT = -5;
	public static final double MAX_OFFSET_PERCENT = 5;

	public static final MaskTuning DEFAULT = new MaskTuning(DEFAULT_PRECISION,
			DEFAULT_OFFSET_PERCENT);

	/**
	 * The shipped shape: dilate 2 at the model's 256px mask, a σ2 Gaussian and a slope-12 sigmoid
	 * around 0.5. The precision dial interpolates either side of these values.
	 */
	private static final double DEFAULT_DILATION_RADIUS = 2;
	private static final double DEFAULT_SIGMA = 2.0;
	private static final double DEFAULT_SLOPE = 12.0;

	public MaskTuning {
		if (!Double.isFinite(precision) || !Double.isFinite(offsetPercent)) {
			throw new IllegalArgumentException("mask tuning values must be finite");
		}
		precision = Math.max(0, Math.min(1, precision));
		offsetPercent = Math.max(MIN_OFFSET_PERCENT, Math.min(MAX_OFFSET_PERCENT, offsetPercent));
	}

	/**
	 * Dilation radius in mask pixels: the baseline halo-fix dilation plus the offset, where a
	 * negative result erodes the mask instead. Expressed as a fraction of the mask's short side so
	 * the control means the same thing if the model's output resolution changes.
	 */
	public int dilationRadius(int maskWidth, int maskHeight) {
		double shortSide = Math.min(maskWidth, maskHeight);
		double offsetPixels = offsetPercent / 100.0 * shortSide;
		return (int) Math.round(DEFAULT_DILATION_RADIUS + offsetPixels);
	}

	/** Gaussian sigma of the edge band: the soft end of the dial widens it, the hard end narrows it. */
	public double sigma() {
		return precision <= DEFAULT_PRECISION
				? lerp(4.0, DEFAULT_SIGMA, precision / DEFAULT_PRECISION)
				: lerp(DEFAULT_SIGMA, 0.6, (precision - DEFAULT_PRECISION) / (1 - DEFAULT_PRECISION));
	}

	/** Sigmoid slope that collapses the band: steeper means a crisper, more precise edge. */
	public double slope() {
		return precision <= DEFAULT_PRECISION
				? lerp(4.0, DEFAULT_SLOPE, precision / DEFAULT_PRECISION)
				: lerp(DEFAULT_SLOPE, 40.0, (precision - DEFAULT_PRECISION) / (1 - DEFAULT_PRECISION));
	}

	private static double lerp(double from, double to, double t) {
		return from + (to - from) * Math.max(0, Math.min(1, t));
	}
}
