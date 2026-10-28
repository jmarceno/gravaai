package dev.gravaai.media.compose;

/**
 * Port of {@code src/components/video-editor/videoPlayback/sceneEffects.ts}:
 * {@code getSceneEffectMetrics}.
 *
 * <p>Effect settings (background blur, shadow intensity) are authored against a 640 px wide
 * reference scene. Treating them as literal pixels makes preview and export diverge, so both
 * compositors scale effect pixels by {@code viewportWidth / 640}. The reference width is part
 * of the template's behaviour, not a tuning knob.</p>
 */
public final class SceneEffects {

	/** {@code EFFECT_REFERENCE_WIDTH} from sceneEffects.ts. */
	public static final int EFFECT_REFERENCE_WIDTH = 640;

	private SceneEffects() {}

	/**
	 * @param viewportScale {@code max(1, viewportWidth) / 640}
	 * @param backgroundBlurPx the background blur radius in output pixels
	 * @param backgroundOverscanPx how far the pre-blurred background is drawn past the canvas
	 *        ({@code ceil(blurPx * 2)})
	 */
	public record Metrics(double viewportScale, double backgroundBlurPx,
			int backgroundOverscanPx) {
	}

	/** {@code getSceneEffectMetrics({viewportWidth, backgroundBlur, shadowIntensity: 0})}. */
	public static Metrics backgroundMetrics(int viewportWidth, double backgroundBlur) {
		double scale = Math.max(1, viewportWidth) / (double) EFFECT_REFERENCE_WIDTH;
		double blurPx = Math.max(0, backgroundBlur) * scale;
		return new Metrics(scale, blurPx, (int) Math.ceil(blurPx * 2));
	}

	/**
	 * One video-layer drop-shadow layer (TS {@code VIDEO_SHADOW_LAYER_PROFILES} entry scaled by
	 * {@code getSceneEffectMetrics} and the shadow intensity).
	 *
	 * @param offsetY the vertical offset in output pixels
	 * @param blur the blur radius in output pixels
	 * @param alpha the layer alpha (0-1)
	 */
	public record ShadowLayer(double offsetY, double blur, double alpha) {
	}

	/** {@code VIDEO_SHADOW_LAYER_PROFILES}: offsetScale, alphaScale, blurScale. */
	private static final double[][] VIDEO_SHADOW_PROFILES = {
			{12, 0.7, 48},
			{4, 0.5, 16},
			{2, 0.3, 8}};

	/**
	 * The {@code shadowFilter} part of {@code getSceneEffectMetrics}: the three drop-shadow
	 * layers scaled by {@code viewportWidth / 640} and the (clamped) shadow intensity, in CSS
	 * order (largest/shallowest first).
	 */
	public static java.util.List<ShadowLayer> shadowLayers(int viewportWidth,
			double shadowIntensity) {
		double intensity = Math.max(0, shadowIntensity);
		if (intensity <= 0) {
			return java.util.List.of();
		}
		double scale = Math.max(1, viewportWidth) / (double) EFFECT_REFERENCE_WIDTH;
		java.util.List<ShadowLayer> layers = new java.util.ArrayList<>(VIDEO_SHADOW_PROFILES.length);
		for (double[] profile : VIDEO_SHADOW_PROFILES) {
			layers.add(new ShadowLayer(profile[0] * intensity * scale,
					profile[2] * intensity * scale, profile[1] * intensity));
		}
		return java.util.List.copyOf(layers);
	}
}