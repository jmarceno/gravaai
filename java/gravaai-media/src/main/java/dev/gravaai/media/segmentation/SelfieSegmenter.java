package dev.gravaai.media.segmentation;

import dev.gravaai.media.onnx.OnnxLibs;

/**
 * The MediaPipe selfie segmenter (ONNX), the <em>fast</em> path: about 2 ms per 256x256 inference,
 * so it is what the preview uses while the timeline is playing.
 *
 * <p>Everything except the model choice lives in {@link OnnxMaskSegmenter}: the shared
 * pre-processing, the temporal smoothing and the project's edge controls, which is why the fast and
 * slow masks differ in model quality and not in edge character.</p>
 *
 * <p>Two intra-op threads. The model is a 256x256 encoder-decoder bound by per-inference overhead
 * more than throughput, and ORT's default pool (one thread per core) both oversubscribes the
 * process and wakes a core's worth of workers per frame. Two threads keeps the inference latency
 * below the default pool's wake-up cost while leaving the machine to the audio pump and the GL
 * thread.</p>
 */
public final class SelfieSegmenter extends OnnxMaskSegmenter {

	/** Input size the selfie segmenter is trained and run at. */
	private static final int INPUT_SIZE = 256;
	private static final int INFERENCE_THREADS = 2;

	public SelfieSegmenter(java.nio.file.Path modelFile) {
		super(modelFile, INFERENCE_THREADS, INPUT_SIZE, false);
	}

	/** Opens the bundled selfie-segmenter model ({@link OnnxLibs#resolveModelFile()}). */
	public static SelfieSegmenter openBundled() {
		return new SelfieSegmenter(OnnxLibs.resolveModelFile());
	}

	@Override
	protected String modelDescription() {
		return "selfie segmenter";
	}
}
