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
	 * Builds the sharp-person-through-mask shader for one webcam layer.
	 *
	 * <p>The caller draws this over the card rect (in card-local coordinates on an offscreen
	 * surface) rather than the full stage: the mask only ever needs to cover the card, and shading
	 * the whole frame for a small card is pure waste. The two textures come from different images
	 * at different resolutions, so the mask gets its own matrix.</p>
	 *
	 * <p>The effect shader holds its own reference to the children: close ours here. A leaked
	 * child shader is freed by the Java cleaner on another thread, which races the GL thread when
	 * the layer is a GPU image (export crash in GrGpuResource::release).</p>
	 *
	 * @param webcam the webcam layer image
	 * @param mask the person mask image (mask value in every channel), at any resolution covering
	 *        the whole frame
	 * @param imageToCanvas the cover matrix from webcam pixels to canvas space
	 * @param maskToCanvas the matrix from mask pixels to canvas space (accounts for the mask
	 *        being smaller than the frame)
	 * @param webcamSampling sampling for the webcam texture (mipmapped); the mask is LINEAR
	 */
	public static Shader personThroughMask(Image webcam, Image mask, Matrix33 imageToCanvas,
			Matrix33 maskToCanvas, SamplingMode webcamSampling) {
		Shader webcamChild = webcam.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
				webcamSampling, imageToCanvas);
		Shader maskChild = mask.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP,
				SamplingMode.LINEAR, maskToCanvas);
		try (webcamChild; maskChild; Data empty = Data.makeFromBytes(new byte[0])) {
			return PERSON_EFFECT.makeShader(empty, new Shader[] {webcamChild, maskChild});
		}
	}

	/**
	 * Same-matrix overload for a mask already at the webcam image's resolution.
	 *
	 * @param imageToCanvas the shared cover matrix
	 */
	public static Shader personThroughMask(Image webcam, Image mask, Matrix33 imageToCanvas,
			SamplingMode webcamSampling) {
		return personThroughMask(webcam, mask, imageToCanvas, imageToCanvas, webcamSampling);
	}
}
