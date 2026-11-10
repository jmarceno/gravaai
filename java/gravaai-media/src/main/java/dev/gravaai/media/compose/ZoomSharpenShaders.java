package dev.gravaai.media.compose;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.Shader;

/**
 * The SkSL unsharp-mask shader for the deep-zoom quality pass ({@link ZoomSharpen}). The
 * compositor captures the zoomed video layer offscreen (cubic-resampled) and blits it 1:1 through
 * this effect: four neighbours at {@code uRadiusPx} estimate the local average the magnification
 * smoothed over, and the layer regains {@code uAmount} of the lost high-frequency detail.
 *
 * <p>The capture is premultiplied RGBA, so the sharpening delta is scaled by the centre alpha and
 * the result is clamped to the premultiplied range: the transparent padding around the layer and
 * the anti-aliased squircle edge are not sharpened, and nothing rings past full opacity.</p>
 */
public final class ZoomSharpenShaders {

	private static final String UNSHARP_SKSL = """
			uniform shader uTexture;
			uniform float uAmount;
			uniform float2 uRadiusPx;

			half4 main(float2 coord) {
			    half4 center = uTexture.eval(coord);
			    if (uAmount <= 0.0) {
			        return center;
			    }
			    float2 r = uRadiusPx;
			    half3 neighbours = uTexture.eval(coord + float2(-r.x, -r.y)).rgb
			            + uTexture.eval(coord + float2(r.x, -r.y)).rgb
			            + uTexture.eval(coord + float2(-r.x, r.y)).rgb
			            + uTexture.eval(coord + float2(r.x, r.y)).rgb
			            + uTexture.eval(coord + float2(-r.x, 0.0)).rgb
			            + uTexture.eval(coord + float2(r.x, 0.0)).rgb
			            + uTexture.eval(coord + float2(0.0, -r.y)).rgb
			            + uTexture.eval(coord + float2(0.0, r.y)).rgb;
			    half3 detail = center.rgb - neighbours * 0.125;
			    half a = center.a;
			    half3 sharpened = center.rgb + detail * uAmount * a;
			    return half4(clamp(sharpened, 0.0, a), a);
			}
			""";

	private static final RuntimeEffect UNSHARP_EFFECT = RuntimeEffect.makeForShader(UNSHARP_SKSL);

	private ZoomSharpenShaders() {}

	/**
	 * Builds the unsharp shader over one captured video layer. The effect shader holds its own
	 * reference to the child, so the caller closes {@code layer} freely (same ownership as the
	 * motion-blur shaders).
	 */
	public static Shader unsharp(Image layer, float amount, float radiusPx) {
		Shader child = layer.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP);
		float[] uniforms = {amount, radiusPx, radiusPx};
		try (Data data = MotionBlurShaders.uniformData(uniforms); child) {
			return UNSHARP_EFFECT.makeShader(data, new Shader[] {child});
		}
	}
}
