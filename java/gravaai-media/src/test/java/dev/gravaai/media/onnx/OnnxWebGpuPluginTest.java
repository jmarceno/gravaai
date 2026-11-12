package dev.gravaai.media.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The real plugin probe against the bundled runtime and whatever GPU this machine has.
 *
 * <p>Opt-in ({@code -Dgravaai.onnx.gpu=true}) because it loads the native runtime and touches the
 * Vulkan driver. It is the rung that proves the plumbing, not just the policy: registration into
 * the stock runtime, device enumeration, and the provider/device names the settings panel shows.
 * On a machine with no Vulkan loader it must still pass by selecting the CPU — that is the
 * fallback contract, not a skip.</p>
 */
class OnnxWebGpuPluginTest {

	@Test
	@Timeout(300)
	@DisplayName("the process probe registers the plugin once and reports one stable selection")
	void probeIsStableAndProcessWide() {
		Assumptions.assumeTrue(Boolean.getBoolean("gravaai.onnx.gpu"),
				"set -Dgravaai.onnx.gpu=true to load the native runtime");
		OnnxWebGpuPlugin.resetForTests();

		var first = OnnxWebGpuPlugin.probe();
		var second = OnnxWebGpuPlugin.probe();

		assertNotNull(first.provider());
		assertEquals(first.provider(), second.provider(),
				"the keeper registration is process-wide: a second probe must not change it");
		if (first.provider().hardware()) {
			assertNotNull(first.device());
			assertNull(first.detail());
			assertNotNull(first.provider().device().name());
			assertFalse(first.provider().device().name().isBlank());
			assertEquals(OnnxExecutionProvider.Kind.WEBGPU, first.provider().kind());
		} else {
			assertEquals(OnnxExecutionProvider.CPU, first.provider());
			assertNotNull(first.detail(), "a CPU selection must say why");
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("an enumerated WebGPU device carries a usable handle and adapter index")
	void deviceSnapshotIsComplete() {
		Assumptions.assumeTrue(Boolean.getBoolean("gravaai.onnx.gpu"),
				"set -Dgravaai.onnx.gpu=true to load the native runtime");
		OnnxWebGpuPlugin.resetForTests();
		var selection = OnnxWebGpuPlugin.probe();
		Assumptions.assumeTrue(selection.provider().hardware(),
				"no WebGPU device on this machine: " + selection.detail());

		OnnxEpDevice device = selection.device();
		assertNotNull(device);
		assertFalse(device.handle().address() == 0, "the device handle must point at the EP device");
		assertFalse(device.epName().isBlank());
		assertEquals(OnnxExecutionProvider.Kind.WEBGPU, device.kind());
		assertEquals(0, device.adapterIndex(), "the first adapter is the one Dawn reported");
	}
}
