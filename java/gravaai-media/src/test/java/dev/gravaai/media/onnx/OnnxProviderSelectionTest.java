package dev.gravaai.media.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GPU-first/CPU-fallback policy, on plain device snapshots: no ORT session, no GPU, no Vulkan.
 *
 * <p>What is under test is the decision the engine makes — which device a session is created for,
 * and what the UI is told when there is none — not the native plumbing that produced the list.</p>
 */
class OnnxProviderSelectionTest {

	private static OnnxEpDevice webGpu(int vendorId, int deviceId, int adapterIndex) {
		return new OnnxEpDevice(OnnxExecutionProvider.Kind.WEBGPU.runtimeName(),
				OnnxEpDevice.HARDWARE_TYPE_GPU, "", MemorySegment.NULL, adapterIndex,
				new OnnxExecutionProvider.Device(
						GpuDeviceNames.describe(vendorId, deviceId), vendorId, deviceId));
	}

	private static OnnxEpDevice cpuDevice() {
		return new OnnxEpDevice(OnnxExecutionProvider.Kind.CPU.runtimeName(), 0, "AMD",
				MemorySegment.NULL, 0, OnnxExecutionProvider.Device.CPU);
	}

	@Test
	@DisplayName("a WebGPU device is selected whenever one is enumerated")
	void selectsWebGpuWhenPresent() {
		OnnxEpDevice gpu = webGpu(0x1002, 0x67FF, 0);
		var selection = OnnxProviderSelection.select(List.of(cpuDevice(), gpu));

		assertEquals(OnnxExecutionProvider.Kind.WEBGPU, selection.provider().kind());
		assertSame(gpu, selection.device().orElseThrow(), "the probed handle is the one sessions get");
		assertTrue(selection.detail().isEmpty(), "a GPU selection has nothing to explain");
		assertTrue(selection.provider().hardware());
	}

	@Test
	@DisplayName("without a WebGPU device the CPU is selected and the reason is reported")
	void fallsBackToCpuWithoutWebGpu() {
		var selection = OnnxProviderSelection.select(List.of(cpuDevice()));

		assertEquals(OnnxExecutionProvider.Kind.CPU, selection.provider().kind());
		assertTrue(selection.device().isEmpty());
		assertTrue(selection.detail().isPresent(), "the panel must be able to say why the CPU is used");
		assertFalse(selection.provider().hardware());
	}

	@Test
	@DisplayName("an empty device list is the CPU, not an error")
	void emptyListFallsBackToCpu() {
		var selection = OnnxProviderSelection.select(List.of());

		assertEquals(OnnxExecutionProvider.CPU, selection.provider());
		assertTrue(selection.detail().isPresent());
	}

	@Test
	@DisplayName("a provider name this build does not know is never trusted")
	void ignoresUnknownProviders() {
		OnnxEpDevice unknown = new OnnxEpDevice("CudaExecutionProvider",
				OnnxEpDevice.HARDWARE_TYPE_GPU, "NVIDIA", MemorySegment.NULL, 0,
				OnnxExecutionProvider.Device.CPU);

		var selection = OnnxProviderSelection.select(List.of(unknown));

		assertEquals(OnnxExecutionProvider.Kind.CPU, selection.provider().kind(),
				"a provider we never append must not be picked just because it is a GPU");
		assertTrue(OnnxProviderSelection.firstWebGpu(List.of(unknown)).isEmpty());
		assertTrue(unknown.kind().isEmpty());
		assertThrows(IllegalStateException.class, unknown::provider);
	}

	@Test
	@DisplayName("the first WebGPU device of several wins, in the order the runtime enumerated")
	void firstWebGpuWins() {
		OnnxEpDevice first = webGpu(0x1002, 0x67FF, 0);
		OnnxEpDevice second = webGpu(0x8086, 0x1234, 1);

		assertSame(first,
				OnnxProviderSelection.firstWebGpu(List.of(cpuDevice(), first, second)).orElseThrow());
	}

	@Test
	@DisplayName("the provider label names the card and the CPU honestly")
	void labelIsHumanReadable() {
		OnnxExecutionProvider gpu = webGpu(0x1002, 0x67FF, 0).provider();
		OnnxExecutionProvider cpu = OnnxExecutionProvider.CPU;

		assertEquals("WebGPU · AMD Radeon RX 560", gpu.label());
		assertEquals("CPU", cpu.label());
		assertTrue(gpu.hardware());
		assertFalse(cpu.hardware());
	}

	@Test
	@DisplayName("a hardware selection cannot exist without its device")
	void hardwareSelectionNeedsDevice() {
		assertThrows(IllegalArgumentException.class, () -> new OnnxWebGpuPlugin.Selection(
				webGpu(0x1002, 0x67FF, 0).provider(), java.util.Optional.empty(),
				java.util.Optional.empty()));
	}

	@Test
	@DisplayName("unknown PCI ids still produce a stable, non-hex label")
	void unknownIdsGetVendorLabels() {
		assertEquals("AMD GPU 0x1234", GpuDeviceNames.describe(0x1002, 0x1234));
		assertEquals("Intel GPU 0x2222", GpuDeviceNames.describe(0x8086, 0x2222));
		assertEquals("GPU", GpuDeviceNames.describe(0, 0));
	}

	@Test
	@DisplayName("a WebGPU device on a software adapter is not taken for a GPU")
	void ignoresSoftwareAdapters() {
		OnnxEpDevice software = new OnnxEpDevice(OnnxExecutionProvider.Kind.WEBGPU.runtimeName(),
				0, "Mesa", MemorySegment.NULL, 0, OnnxExecutionProvider.Device.CPU);

		assertFalse(software.webGpu(), "lavapipe/SwiftShader must not be reported as a GPU");
		assertEquals(OnnxExecutionProvider.CPU, OnnxProviderSelection.select(List.of(software))
				.provider());
	}
}
