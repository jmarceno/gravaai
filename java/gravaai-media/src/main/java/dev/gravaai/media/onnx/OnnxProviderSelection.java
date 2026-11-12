package dev.gravaai.media.onnx;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Picks the execution provider a session should run on from the devices ONNX Runtime enumerated.
 *
 * <p>Pure and GPU-free so the policy is unit-tested: a WebGPU device is preferred whenever one
 * exists, and every other case (no plugin, no adapter, a device with an unknown provider) falls
 * back to the CPU EP with a reason. The CPU EP is built into the bundled runtime, so the fallback
 * can never be unavailable.</p>
 *
 * <p><b>Why GPU-first despite the wall-clock numbers.</b> Measured on the baseline RX 560
 * (Polaris/RADV, kernel 7.2.15) with the pinned network, one inference costs:</p>
 *
 * <table>
 *   <caption>one inference per window</caption>
 *   <tr><th>window</th><th>CPU (8 threads)</th><th>WebGPU</th></tr>
 *   <tr><td>576×324 (187k px)</td><td>1.7 s</td><td>1.3 s</td></tr>
 *   <tr><td>720×406 (292k px)</td><td>2.6 s</td><td>2.9 s</td></tr>
 *   <tr><td>826×466 (385k px)</td><td>3.4 s</td><td>4.3 s</td></tr>
 *   <tr><td>640×640 (410k px, the 4K cap)</td><td>3.7 s</td><td>3.8 s</td></tr>
 * </table>
 *
 * <p>The upscaler is one inference per eased zoom gesture, not per frame, and the CPU run holds all
 * eight cores for the whole window while the GPU run costs a fraction of one core — on an export
 * that is concurrently decoding, compositing and encoding, freeing those cores matters. This is the
 * same trade-off the encoder policy makes for recording ({@code VideoEncoderProbe}: hardware always
 * for capture, CPU below 4K for export), and the reason the product is GPU-first. The GPU also wins
 * outright at the D6 window, which is the deepest and most common zoom stage.</p>
 *
 * <p><b>Measured CPU cost</b> (user+sys over three runs at the 826×466 window): CPU provider 81.4 s
 * of CPU time, WebGPU 1.9 s — roughly 26 core-seconds per inference against 0.6. The wall-clock
 * crossover is ~260k px on this card; above it the CPU is faster in wall clock, and the engine still
 * prefers the GPU because the export's other stages need those cores. Re-measure and revisit the
 * preference if a GPU is added that reverses the D6 result as well.</p>
 */
public final class OnnxProviderSelection {

	private OnnxProviderSelection() {}

	/**
	 * The selection for an enumerated device list.
	 *
	 * <p>A WebGPU device is preferred; among several, the first is taken, which is the plugin's
	 * adapter ordering (it enumerates in Dawn's power-preference order). A device whose name is
	 * outside {@link OnnxExecutionProvider.Kind} is ignored rather than trusted.</p>
	 */
	public static OnnxWebGpuPlugin.Selection select(List<OnnxEpDevice> devices) {
		if (devices.isEmpty()) {
			return OnnxWebGpuPlugin.Selection.cpu(
					"ONNX Runtime enumerated no execution-provider devices");
		}
		OnnxEpDevice chosen = firstWebGpu(devices);
		if (chosen == null) {
			return OnnxWebGpuPlugin.Selection.cpu(
					"no WebGPU device (the Vulkan loader may be missing, or no GPU driver)");
		}
		return new OnnxWebGpuPlugin.Selection(chosen.provider(), chosen, null);
	}

	/** The first WebGPU device, or null when none is enumerable. */
	public static @Nullable OnnxEpDevice firstWebGpu(List<OnnxEpDevice> devices) {
		for (OnnxEpDevice device : devices) {
			if (device.webGpu()) {
				return device;
			}
		}
		return null;
	}
}
