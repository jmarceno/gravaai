package dev.gravaai.media.segmentation;

import dev.gravaai.media.onnx.OnnxLibs;

/**
 * MODNet (ONNX), the <em>slow</em> path: about 28 ms per 256x256 inference at eight threads, used
 * for paused/stopped frames, scrubs and exports — and during playback too when the project turns on
 * the Advanced high-quality switch.
 *
 * <p>It exists because the fast selfie segmenter bleeds the room through the person's shoulders and
 * leaves a bright halo along the silhouette; MODNet 256 gives a clean edge with no bleed for a cost
 * that is negligible once the timeline is not playing. 256 px is a deliberate choice over 512: the
 * network is super-linear in pixels, and 512 costs 4.5x for a hairline improvement.</p>
 *
 * <p>Pre-processing is MODNet's trained configuration: [-1,1] input (mean 0.5, std 0.5) and a
 * stretch resize without letterboxing, matching the fast path's geometry. Eight intra-op threads:
 * measured on a 16-core box, 8 is the fastest setting and 16 regresses (intra-op
 * oversubscription).</p>
 *
 * <p>A 25 MB model, so it is never opened at startup: {@link LazyWebcamSegmenter} opens it on the
 * first slow-path request, on the background worker.</p>
 */
public final class ModnetSegmenter extends OnnxMaskSegmenter {

	/** Input size both paths run at; 512 is deliberately not used (see the class comment). */
	private static final int INPUT_SIZE = 256;
	/** Measured optimum: 8 threads beat 1/2/4 and 16 on a 16-core machine. */
	private static final int INFERENCE_THREADS = 8;

	public ModnetSegmenter(java.nio.file.Path modelFile) {
		super(modelFile, INFERENCE_THREADS, INPUT_SIZE, true);
	}

	/** Opens the bundled MODNet model ({@link OnnxLibs#resolveModnetModelFile()}). */
	public static ModnetSegmenter openBundled() {
		return new ModnetSegmenter(OnnxLibs.resolveModnetModelFile());
	}

	@Override
	protected String modelDescription() {
		return "MODNet segmenter";
	}
}
