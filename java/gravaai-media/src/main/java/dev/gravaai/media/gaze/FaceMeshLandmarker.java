package dev.gravaai.media.gaze;

import java.util.List;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxExecutionProvider;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.onnx.OnnxRuntimeApi;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * The 478-point face mesh (MediaPipe Face Mesh V2 with irises) over a {@link FaceSquare}: finds the
 * eyelids and the irises the correction is built on. About 8 ms per frame on the RX 560 through
 * WebGPU. Single-threaded.
 */
public final class FaceMeshLandmarker implements AutoCloseable {

	public static final String MODEL_FILE = "face_landmarks.onnx";
	public static final int POINTS = 478;

	private final GazeSession session;
	private final String input;
	private final int landmarkOutput;
	private final int presenceOutput;

	/** The mesh points in frame pixels and the face-presence score (a logit). */
	public record Landmarks(float[] x, float[] y, float presence) {}

	public FaceMeshLandmarker(OnnxWebGpuPlugin.Selection selection) {
		OnnxLibs.ensureLoaded();
		session = GazeSession.open(OnnxLibs.resolveGazeModelFile(MODEL_FILE), selection, 2);
		input = session.inputs().getFirst();
		landmarkOutput = session.outputIndex("Identity");
		presenceOutput = session.outputIndex("Identity_1");
	}

	public OnnxExecutionProvider provider() {
		return session.provider();
	}

	public Landmarks detect(RgbaFrame frame, FaceSquare square) {
		int size = EyeContactMath.FACE_INPUT;
		float[] in = square.sample(frame, size, false);
		long[] counts = new long[session.outputCount()];
		counts[landmarkOutput] = POINTS * 3L;
		counts[presenceOutput] = 1;
		float[][] out = session.run(List.of(new OnnxRuntimeApi.NamedFloat(input,
				new long[] {1, size, size, 3}, in)), counts);
		float[] lm = out[landmarkOutput];
		float[] xs = new float[POINTS];
		float[] ys = new float[POINTS];
		double scale = square.side() / size;
		for (int i = 0; i < POINTS; i++) {
			xs[i] = (float) (square.x() + lm[i * 3] * scale);
			ys[i] = (float) (square.y() + lm[i * 3 + 1] * scale);
		}
		return new Landmarks(xs, ys, out[presenceOutput][0]);
	}

	@Override
	public void close() {
		session.close();
	}
}
