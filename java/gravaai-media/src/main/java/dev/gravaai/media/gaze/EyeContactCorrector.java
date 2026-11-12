package dev.gravaai.media.gaze;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;

/**
 * Applies eye contact to one webcam frame from its {@link GazeTrack.Sample}: segments the glasses
 * frame, runs each eye's warp net and composites the result into the frame in place.
 *
 * <p>The nets run on the GPU (WebGPU) when the process has one; the composite itself is a small
 * CPU pass over the two eye patches (about 2 x 3 000 pixels at 720p, well under a millisecond), done
 * on the CPU on purpose: it has to produce byte-identical output in the preview's decode worker
 * and in the export, and to leave every glasses-frame pixel exactly as decoded — a guarantee a
 * GPU resample through the compositor's colour pipeline could not give.</p>
 *
 * <p>Single-threaded; owns three ORT sessions.</p>
 */
public final class EyeContactCorrector implements AutoCloseable {

	/** Below this effective angle the frame is left alone. */
	private static final double MIN_DEGREES = 0.25;

	/**
	 * The warp nets run on the CPU on purpose: they are tiny (64x48), and on the RX 560 a WebGPU
	 * dispatch costs more than the math (8.5 ms per eye on WebGPU against 5.0 ms on two CPU
	 * threads). The two eyes run in parallel with the glasses segmenter, which stays on the GPU.
	 */
	private static final OnnxWebGpuPlugin.Selection WARP_PROVIDER = OnnxWebGpuPlugin.Selection.cpu(
			"the eye-warp nets are faster on the CPU than through a WebGPU dispatch");

	private final GlassesFrameSegmenter glasses;
	private final EyeWarpNet[] nets;
	private final ExecutorService eyeWorkers = Executors.newFixedThreadPool(2,
			Thread.ofPlatform().daemon().name("gravaai-eye-warp-", 0).factory());
	private ByteBuffer scratch = ByteBuffer.allocateDirect(0);

	private record Eye(int index, EyeContactMath.Patch patch) {}

	public EyeContactCorrector(OnnxWebGpuPlugin.Selection selection) {
		GlassesFrameSegmenter openedGlasses = new GlassesFrameSegmenter(selection);
		try {
			EyeWarpNet left = new EyeWarpNet(0, WARP_PROVIDER);
			try {
				this.nets = new EyeWarpNet[] {left, new EyeWarpNet(1, WARP_PROVIDER)};
			} catch (RuntimeException failure) {
				left.close();
				throw failure;
			}
		} catch (RuntimeException failure) {
			openedGlasses.close();
			throw failure;
		}
		this.glasses = openedGlasses;
	}

	/** Where the models run, for logs and the inspector ("WebGPU" / "CPU"). */
	public String providerSummary() {
		return "glasses " + glasses.provider().label() + ", eyes " + nets[0].provider().label();
	}

	/**
	 * Corrects {@code frame} in place.
	 *
	 * @param degrees the lift at full weight; the sample's weight scales it (blinks, head turns
	 *        and look-aways fade the effect out)
	 * @return the number of pixels changed
	 */
	public int correct(RgbaFrame frame, GazeTrack.Sample sample, double degrees) {
		double angle = degrees * sample.weight();
		if (!(angle >= MIN_DEGREES)) {
			return 0;
		}
		int width = frame.width();
		int height = frame.height();
		List<Eye> eyes = new ArrayList<>(2);
		for (int eye = 0; eye < 2; eye++) {
			float[] xs = new float[6];
			float[] ys = new float[6];
			for (int k = 0; k < 6; k++) {
				xs[k] = sample.x(eye, k);
				ys[k] = sample.y(eye, k);
			}
			EyeContactMath.Patch patch = EyeContactMath.Patch.of(xs, ys);
			if (patch.usable() && patch.right() > 0 && patch.bottom() > 0 && patch.left() < width
					&& patch.top() < height) {
				eyes.add(new Eye(eye, patch));
			}
		}
		if (eyes.isEmpty()) {
			return 0;
		}
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		int bytes = width * height * 4;
		if (scratch.capacity() < bytes) {
			scratch = ByteBuffer.allocateDirect(bytes);
		}
		// The warp samples the original around each pixel it writes: read from a copy.
		scratch.clear();
		scratch.put(0, pixels, 0, bytes);
		float[] roi = sample.roi();
		FaceSquare square = new FaceSquare(roi[0], roi[1], roi[2]);
		RgbaFrame original = new RgbaFrame(width, height, scratch);
		ByteBuffer source = scratch;
		List<Future<EyeWarpNet.Warp>> warps = new ArrayList<>(eyes.size());
		for (Eye found : eyes) {
			warps.add(eyeWorkers.submit(() -> nets[found.index()].run(
					EyeContactMath.patchImage(source, width, height, found.patch()),
					EyeContactMath.anchorMap(found.patch(), found.index()), angle)));
		}
		float[] logits = glasses.logits(original, square);
		int written = 0;
		for (int i = 0; i < eyes.size(); i++) {
			EyeContactMath.Patch patch = eyes.get(i).patch();
			EyeWarpNet.Warp warp = await(warps.get(i));
			float[] alpha = EyeContactMath.glassesAlpha(patch, logits, square.x(), square.y(),
					square.side());
			written += EyeContactMath.composite(scratch, pixels, width, height, patch, warp.flow(),
					warp.light(), alpha);
		}
		return written;
	}

	private static EyeWarpNet.Warp await(Future<EyeWarpNet.Warp> warp) {
		try {
			return warp.get(5, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("eye warp interrupted", interrupted);
		} catch (ExecutionException | TimeoutException failed) {
			throw new IllegalStateException("eye warp failed: " + failed, failed);
		}
	}

	@Override
	public void close() {
		eyeWorkers.shutdown();
		try {
			eyeWorkers.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		glasses.close();
		for (EyeWarpNet net : nets) {
			net.close();
		}
	}
}
