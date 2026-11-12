package dev.gravaai.media.superres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.compose.ZoomTransform;
import dev.gravaai.media.onnx.OnnxExecutionProvider;
import dev.gravaai.media.onnx.OnnxWebGpuPlugin;
import io.github.humbleui.types.Rect;

/**
 * The GPU path against the real pinned network, on this machine's GPU.
 *
 * <p>Opt-in, because it touches the Vulkan driver: {@code -Dgravaai.superres.gpu=true}. It runs the
 * ladder the card asks for — device enumeration, a session on the WebGPU EP, the D5/D6 window —
 * and asserts the provider that actually ran, not the one that was requested: a session that
 * silently lands on the CPU EP fails here.</p>
 *
 * <p>The equivalence test compares GPU and CPU output on the same fixture. On this hardware (RX
 * 560, RADV) the adapter is a low-end Polaris and the WebGPU kernels use f32 math like the CPU
 * EP, so the two runs agree closely; the tolerance is deliberately loose enough to allow a
 * different summation order, and far tighter than "both outputs are images".</p>
 */
class SuperResolutionGpuTest {

	private static final Rect SOURCE = Rect.makeXYWH(0, 0, 1920, 1080);

	private static Path modelFile() {
		return SuperResolutionTestModel.resolve();
	}

	private static boolean gpuEnabled() {
		return Boolean.getBoolean("gravaai.superres.gpu");
	}

	/** A smooth, high-contrast pattern: structure the network must preserve, cheap to compare. */
	private static RgbaFrame testFrame(int width, int height) {
		ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean light = ((x / 8) + (y / 8)) % 2 == 0;
				int base = light ? 210 : 40;
				int value = x > width / 2 ? Math.min(255, base + 30) : base;
				pixels.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
			}
		}
		pixels.flip();
		return new RgbaFrame(width, height, pixels);
	}

	@Test
	@Timeout(300)
	@DisplayName("the WebGPU EP is discovered and assigned the session on this machine")
	void webGpuDeviceIsUsed() {
		Assumptions.assumeTrue(gpuEnabled(), "set -Dgravaai.superres.gpu=true to test the GPU path");
		Path model = modelFile();
		Assumptions.assumeTrue(Files.isRegularFile(model), "model not staged at " + model);

		var selection = OnnxWebGpuPlugin.probe();
		Assumptions.assumeTrue(selection.provider().hardware(),
				"no WebGPU device on this machine: " + selection.detail());

		try (SuperResolutionEngine engine = SuperResolutionEngine.open(model, selection)) {
			assertEquals(OnnxExecutionProvider.Kind.WEBGPU, engine.provider().kind(),
					"the engine must report the GPU provider it actually bound");
			assertTrue(engine.hardwareAccelerated());
			assertNotNull(engine.provider().device().name());
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("the D5/D6 window runs on the GPU and keeps the frame's structure")
	void deepZoomWindowRunsOnGpu() {
		Assumptions.assumeTrue(gpuEnabled(), "set -Dgravaai.superres.gpu=true to test the GPU path");
		Path model = modelFile();
		Assumptions.assumeTrue(Files.isRegularFile(model), "model not staged at " + model);

		var selection = OnnxWebGpuPlugin.probe();
		Assumptions.assumeTrue(selection.provider().hardware(),
				"no WebGPU device on this machine: " + selection.detail());

		RgbaFrame frame = testFrame(960, 540);
		Rect source = Rect.makeXYWH(0, 0, 960, 540);
		try (SuperResolutionEngine engine = SuperResolutionEngine.open(model, selection)) {
			var upscaled = engine.upscaleFor(frame, source, SOURCE,
					new ZoomTransform.AppliedTransform(5, -4 * 960, -4 * 540), 1920, 1080);
			assertNotNull(upscaled, "the GPU path returned no window for a deep zoom");
			assertEquals(1, engine.inferenceCount());
			int width = upscaled.upscaled().width();
			int height = upscaled.upscaled().height();
			ByteBuffer pixels = upscaled.upscaled().pixelsTopFirstRgba();
			long dark = 0;
			long light = 0;
			long total = (long) width * height;
			long sum = 0;
			for (int i = 0; i < total; i++) {
				int value = Byte.toUnsignedInt(pixels.get(i * 4));
				sum += value;
				if (value < 110) {
					dark++;
				} else if (value > 170) {
					light++;
				}
			}
			double mean = sum / (double) total;
			assertTrue(mean > 60 && mean < 200, "GPU output collapsed to " + mean);
			assertTrue(dark > total / 10, "dark tiles must survive on the GPU: " + dark + "/" + total);
			assertTrue(light > total / 10,
					"light tiles must survive on the GPU: " + light + "/" + total);
			assertTrue(engine.hardwareAccelerated(),
					"the inference must have stayed on the GPU, not fallen back to the CPU");
		}
	}

	@Test
	@Timeout(600)
	@DisplayName("GPU and CPU produce the same window within tolerance")
	void gpuMatchesCpuOutput() {
		Assumptions.assumeTrue(gpuEnabled(), "set -Dgravaai.superres.gpu=true to test the GPU path");
		Path model = modelFile();
		Assumptions.assumeTrue(Files.isRegularFile(model), "model not staged at " + model);

		var selection = OnnxWebGpuPlugin.probe();
		Assumptions.assumeTrue(selection.provider().hardware(),
				"no WebGPU device on this machine: " + selection.detail());

		RgbaFrame frame = testFrame(960, 540);
		var transform = new ZoomTransform.AppliedTransform(5, -4 * 960, -4 * 540);
		int canvasWidth = 1920;
		int canvasHeight = 1080;

		float[] gpu;
		float[] cpu;
		try (SuperResolutionEngine engine = SuperResolutionEngine.open(model, selection)) {
			gpu = red(windowOf(engine, frame, transform, canvasWidth, canvasHeight));
			assertTrue(engine.hardwareAccelerated(), "the reference run must be the GPU one");
		}
		var cpuSelection = new OnnxWebGpuPlugin.Selection(OnnxExecutionProvider.CPU, null, null);
		try (SuperResolutionEngine engine = SuperResolutionEngine.open(model, cpuSelection)) {
			cpu = red(windowOf(engine, frame, transform, canvasWidth, canvasHeight));
			assertEquals(OnnxExecutionProvider.Kind.CPU, engine.provider().kind());
		}

		assertEquals(cpu.length, gpu.length, "both paths must upscale to the same window");
		double absolute = 0;
		double worst = 0;
		for (int i = 0; i < gpu.length; i++) {
			double diff = Math.abs(gpu[i] - cpu[i]);
			absolute += diff;
			worst = Math.max(worst, diff);
		}
		double meanAbs = absolute / gpu.length;
		assertTrue(meanAbs < 0.02,
				"GPU vs CPU mean absolute error must be small: " + meanAbs);
		assertTrue(worst < 0.15, "no pixel may drift far: " + worst);
	}

	/** The upscaled window's red channel as floats in [0,1], the cheapest full-window comparison. */
	private static float[] red(RgbaFrame frame) {
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		float[] values = new float[frame.width() * frame.height()];
		for (int i = 0; i < values.length; i++) {
			values[i] = Byte.toUnsignedInt(pixels.get(i * 4)) / 255f;
		}
		return values;
	}

	private static RgbaFrame windowOf(SuperResolutionEngine engine, RgbaFrame frame,
			ZoomTransform.AppliedTransform transform, int canvasWidth, int canvasHeight) {
		var upscaled = engine.upscaleFor(frame, Rect.makeXYWH(0, 0, 960, 540), SOURCE, transform,
				canvasWidth, canvasHeight);
		assertNotNull(upscaled, "no upscaled window");
		return upscaled.upscaled();
	}
}
