package dev.gravaai.media.compose;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;

/**
 * SkSL for the webcam background blur composite (stage 7): the sharp person drawn over the
 * blurred webcam through the segmentation mask (both textures share the cover matrix, so the
 * mask is evaluated in the same canvas space as the webcam).
 */
final class WebcamBlurShaders {

	private static final String PERSON_SKSL = """
			uniform shader webcam;
			uniform shader mask;

			half4 main(float2 coord) {
			    half a = saturate(mask.eval(coord).a);
			    half4 c = webcam.eval(coord);
			    return half4(c.rgb * a, a);
			}
			""";

	private static final RuntimeEffect PERSON_EFFECT = RuntimeEffect.makeForShader(PERSON_SKSL);

	private WebcamBlurShaders() {}

	/**
	 * Builds the sharp-person-through-mask shader for one webcam layer. The effect must be drawn
	 * over the full stage rect: runtime-effect draws with child shaders only composite correctly
	 * at stage bounds in this Skia/Skija stack (a sub-rect draw renders displaced and shrunk), so
	 * the caller runs it on a full-stage offscreen and clips the result to the card.
	 *
	 * <p>The effect shader holds its own reference to the children: close ours here. A leaked
	 * child shader is freed by the Java cleaner on another thread, which races the GL thread when
	 * the layer is a GPU image (export crash in GrGpuResource::release).
	 *
	 * @param webcam the webcam layer image
	 * @param mask the person mask image (mask value in every channel)
	 * @param imageToCanvas the shared cover matrix (see {@code Compositor#webcamCoverMatrix}), so
	 *        the mask lands in register with the webcam
	 * @param webcamSampling sampling for the webcam texture (mipmapped); the mask is LINEAR
	 */
	public static Shader personThroughMask(Image webcam, Image mask, Matrix33 imageToCanvas,
			SamplingMode webcamSampling) {
		Shader webcamChild = webcam.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
				webcamSampling, imageToCanvas);
		Shader maskChild = mask.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
				SamplingMode.LINEAR, imageToCanvas);
		try (webcamChild; maskChild; Data empty = Data.makeFromBytes(new byte[0])) {
			return PERSON_EFFECT.makeShader(empty, new Shader[] {webcamChild, maskChild});
		}
	}
}
