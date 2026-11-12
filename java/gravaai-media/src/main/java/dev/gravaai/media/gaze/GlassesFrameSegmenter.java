package dev.gravaai.media.gaze;

import java.util.List;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxExecutionProvider;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.onnx.OnnxRuntimeApi;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * Outlines a glasses frame (rims and bridge, not the lenses) over a {@link FaceSquare}, so the
 * correction can hand those pixels back untouched. LR-ASPP from the glasses-detector project,
 * about 9 ms on the RX 560 through WebGPU. Single-threaded.
 */
public final class GlassesFrameSegmenter implements AutoCloseable {

	public static final String MODEL_FILE = "glasses_frames.onnx";

	private final GazeSession session;
	private final String input;

	public GlassesFrameSegmenter(OnnxWebGpuPlugin.Selection selection) {
		OnnxLibs.ensureLoaded();
		session = GazeSession.open(OnnxLibs.resolveGazeModelFile(MODEL_FILE), selection, 2);
		input = session.inputs().getFirst();
	}

	public OnnxExecutionProvider provider() {
		return session.provider();
	}

	/** Frame logits over the square, 256x256 row-major; above 0 is frame. */
	public float[] logits(RgbaFrame frame, FaceSquare square) {
		int size = EyeContactMath.FACE_INPUT;
		float[] in = square.sample(frame, size, true);
		long[] counts = new long[session.outputCount()];
		counts[0] = (long) size * size;
		return session.run(List.of(new OnnxRuntimeApi.NamedFloat(input,
				new long[] {1, 3, size, size}, in)), counts)[0];
	}

	@Override
	public void close() {
		session.close();
	}
}
