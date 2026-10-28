package dev.gravaai.media.compose;

/**
 * The pixel size of a compositor render target (plan §5.8: preview renders at the panel size
 * (times a render scale under decision rule R2), export renders at the configured output size).
 *
 * @param width the width in pixels, positive
 * @param height the height in pixels, positive
 */
public record RenderTargetSize(int width, int height) {

	public RenderTargetSize {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid render target size: " + width + "x" + height);
		}
	}

	/** The preview target for a panel size (no scaling yet; R2 adds the render scale). */
	public static RenderTargetSize preview(int panelWidth, int panelHeight) {
		return new RenderTargetSize(panelWidth, panelHeight);
	}

	/** The export target for the configured output size. */
	public static RenderTargetSize export(int outputWidth, int outputHeight) {
		return new RenderTargetSize(outputWidth, outputHeight);
	}

	/**
	 * Scales both dimensions by {@code factor} and rounds to whole pixels (at least 1x1), the
	 * preview render-scale rule from decision rule R2.
	 */
	public RenderTargetSize scaled(double factor) {
		if (!Double.isFinite(factor) || factor <= 0) {
			throw new IllegalArgumentException("invalid scale factor: " + factor);
		}
		return new RenderTargetSize(
				Math.max(1, (int) Math.round(width * factor)),
				Math.max(1, (int) Math.round(height * factor)));
	}

	/** {@code width / height}. */
	public double aspectRatio() {
		return (double) width / height;
	}
}
