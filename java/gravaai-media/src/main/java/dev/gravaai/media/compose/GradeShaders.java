package dev.gravaai.media.compose;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.jspecify.annotations.Nullable;

import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.RuntimeEffect;

/**
 * The SkSL color filter for the Color-track grade (stage 5.5): brightness, temperature, contrast,
 * saturation and gamma in one pass over the video layer. The sliders are the project's
 * {@code GradeRegion} values: signed -100..100 adjusts plus the gamma exponent.
 *
 * <p>Order inside the shader is the grading convention: additive brightness, then the warm/cool
 * channel shift, then contrast around mid grey, then saturation around Rec.709 luma, then the
 * midtone gamma. Only the drawn video layer is filtered — the background, webcam and overlays keep
 * their own look.
 */
public final class GradeShaders {

	private static final String SKSL = """
			uniform float uBrightness;
			uniform float uContrast;
			uniform float uSaturation;
			uniform float uTemperature;
			uniform float uGamma;

			half4 main(half4 color) {
			    half3 c = color.rgb;
			    c += half3(uBrightness);
			    c.r += half(uTemperature * 0.15);
			    c.b -= half(uTemperature * 0.15);
			    c = (c - half3(0.5)) * half(uContrast) + half3(0.5);
			    half luma = dot(c, half3(0.2126, 0.7152, 0.0722));
			    c = mix(half3(luma), c, half(uSaturation));
			    c = pow(max(c, half3(0.0)), half3(1.0 / uGamma));
			    return half4(clamp(c, half3(0.0), half3(1.0)), color.a);
			}
			""";

	private static final RuntimeEffect EFFECT = RuntimeEffect.makeForColorFilter(SKSL);

	/** The signed adjust sliders run -100..100. */
	private static final double ADJUST_RANGE = 100;

	private GradeShaders() {}

	/**
	 * The color filter for one grade window's sliders; null when the shader failed to compile on
	 * this Skia build (the frame then renders ungraded instead of failing preview/export).
	 */
	public static @Nullable ColorFilter filter(double brightness,
			double contrast, double saturation, double temperature, double gamma) {
		RuntimeEffect effect = EFFECT;
		if (effect == null) {
			return null;
		}
		float[] uniforms = {
				(float) (brightness / ADJUST_RANGE),
				(float) (1 + contrast / ADJUST_RANGE),
				(float) (1 + saturation / ADJUST_RANGE),
				(float) (temperature / ADJUST_RANGE),
				(float) gamma};
		ByteBuffer buffer = ByteBuffer.allocate(uniforms.length * Float.BYTES)
				.order(ByteOrder.LITTLE_ENDIAN);
		for (float value : uniforms) {
			buffer.putFloat(value);
		}
		try (Data data = Data.makeFromBytes(buffer.array())) {
			return effect.makeColorFilter(data);
		}
	}
}
