package dev.gravaai.media.gaze;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.media.onnx.OnnxExecutionProvider;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.onnx.OnnxRuntimeApi;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * One eye's gaze-redirection net (Hsu et al., "Look at me!", TOMM 2019): from a 64x48 eye patch,
 * its anchor map and a target angle it predicts a flow field and a light map, which
 * {@link EyeContactMath#composite} applies at full resolution. One net per eye. Single-threaded.
 */
public final class EyeWarpNet implements AutoCloseable {

	private final GazeSession session;
	private final String imageInput;
	private final String anchorInput;
	private final String angleInput;
	private final int flowOutput;
	private final int lightOutput;

	/** The net's output for one patch: 64x48x2 raw flow and 64x48x2 light weights. */
	public record Warp(float[] flow, float[] light) {}

	/** @param eye 0 for the subject's left eye ({@code eye_warp_L}), 1 for the right */
	public EyeWarpNet(int eye, OnnxWebGpuPlugin.Selection selection) {
		OnnxLibs.ensureLoaded();
		String file = eye == 0 ? "eye_warp_L.onnx" : "eye_warp_R.onnx";
		session = GazeSession.open(OnnxLibs.resolveGazeModelFile(file), selection, 2);
		imageInput = session.input("img");
		anchorInput = session.input("fp");
		angleInput = session.input("ang");
		flowOutput = session.outputIndex("flow");
		lightOutput = session.outputIndex("lcm");
	}

	public OnnxExecutionProvider provider() {
		return session.provider();
	}

	/**
	 * @param verticalDegrees how far to lift the gaze; positive looks up
	 */
	public Warp run(float[] image, float[] anchors, double verticalDegrees) {
		int h = EyeContactMath.PATCH_H;
		int w = EyeContactMath.PATCH_W;
		List<OnnxRuntimeApi.NamedFloat> feeds = new ArrayList<>(3);
		for (String name : session.inputs()) {
			if (name.equals(imageInput)) {
				feeds.add(new OnnxRuntimeApi.NamedFloat(name, new long[] {1, h, w, 3}, image));
			} else if (name.equals(anchorInput)) {
				feeds.add(new OnnxRuntimeApi.NamedFloat(name, new long[] {1, h, w, 12}, anchors));
			} else if (name.equals(angleInput)) {
				feeds.add(new OnnxRuntimeApi.NamedFloat(name, new long[] {1, 2},
						new float[] {(float) verticalDegrees, 0f}));
			} else {
				throw new IllegalStateException("unexpected eye-warp input " + name);
			}
		}
		long[] counts = new long[session.outputCount()];
		counts[flowOutput] = (long) h * w * 2;
		counts[lightOutput] = (long) h * w * 2;
		float[][] out = session.run(feeds, counts);
		return new Warp(out[flowOutput], out[lightOutput]);
	}

	@Override
	public void close() {
		session.close();
	}
}
