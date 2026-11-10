package dev.gravaai.media.compose;

/**
 * The deep-zoom quality pass: stages that magnify the source at least
 * {@link #MIN_MAGNIFICATION} sample the video layer with the cubic resampler into an offscreen
 * capture and blit it through an unsharp-mask shader ({@link ZoomSharpenShaders}), so text on a
 * 1080p source stays legible at the 3.5&ndash;5&times; zoom depths instead of turning to mush.
 *
 * <p>The pass costs one offscreen capture plus one sharpen draw per frame, so it is strictly
 * gated on the camera magnification (baseMask/source width &times; camera scale): mild zooms and
 * unzoomed frames keep the plain bilinear draw and pay nothing. Preview and export share the
 * compositor, so both get the same pass.</p>
 */
public final class ZoomSharpen {

	/**
	 * The camera magnification from which a stage runs the sharpening pass; the smallest zoom
	 * depth above it is 2.2&times; ({@code D4}).
	 */
	public static final double MIN_MAGNIFICATION = 2.0;

	/** The per-frame pass parameters for one zoomed frame. */
	public record Pass(boolean enabled, float amount, float radiusPx) {
		/** No sharpening (mild zoom or unzoomed frame). */
		public static final Pass NONE = new Pass(false, 0, 0);
	}

	private ZoomSharpen() {}

	/**
	 * Resolves the pass for a camera magnification. The amount ramps from the threshold to the
	 * deepest zoom ({@code D6}, 5&times;) so deeper zooms sharpen harder, and the kernel radius
	 * tracks half a source texel on screen (the span bilinear smooths over), capped to keep
	 * halos in check.
	 */
	public static Pass passFor(double magnification) {
		if (!Double.isFinite(magnification) || magnification < MIN_MAGNIFICATION) {
			return Pass.NONE;
		}
		double deepest = ZoomDepth.D6.scale();
		double ramp = Math.min(1, (magnification - MIN_MAGNIFICATION) / (deepest - MIN_MAGNIFICATION));
		float amount = (float) (0.35 + 0.35 * ramp);
		float radius = (float) Math.min(6, Math.max(1, magnification * 0.6));
		return new Pass(true, amount, radius);
	}

	/**
	 * Whether a zoom stage at this depth scale runs the pass (the timeline badge marks these
	 * stages).
	 */
	public static boolean isSharpened(double scale) {
		return Double.isFinite(scale) && scale >= MIN_MAGNIFICATION;
	}
}
