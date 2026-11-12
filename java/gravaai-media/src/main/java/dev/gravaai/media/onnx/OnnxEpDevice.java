package dev.gravaai.media.onnx;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * One execution-provider device as ONNX Runtime enumerated it: which provider it belongs to, what
 * hardware it runs on, and the native handle a session is created with.
 *
 * <p>The handle points into the keeper environment's device list and is only valid while that
 * environment is alive ({@link OnnxWebGpuPlugin}). The record itself is a plain snapshot, so the
 * *selection* (which provider, which adapter) is testable without a GPU.</p>
 *
 * @param epName       the runtime's provider name ({@link OnnxExecutionProvider.Kind#runtimeName()})
 * @param hardwareType the {@code OrtHardwareDeviceType} value (0 CPU, 1 GPU, 2 NPU)
 * @param hardwareVendor the runtime's vendor string for the hardware (may be empty)
 * @param handle       the native {@code OrtEpDevice*} a session is appended with
 * @param adapterIndex the Dawn adapter index for this device (0 when the plugin reported none)
 * @param device       the provider and device a session on this handle will report
 */
public record OnnxEpDevice(String epName, int hardwareType, String hardwareVendor,
		MemorySegment handle, int adapterIndex, OnnxExecutionProvider.Device device) {

	/** {@code OrtHardwareDeviceType_GPU}. */
	public static final int HARDWARE_TYPE_GPU = 1;

	public OnnxEpDevice {
		if (epName.isBlank()) {
			throw new IllegalArgumentException("EP name must not be blank");
		}
		if (adapterIndex < 0) {
			throw new IllegalArgumentException("adapter index must not be negative: " + adapterIndex);
		}
	}

	/** The provider family this device belongs to, if it is one this app ships. */
	public Optional<OnnxExecutionProvider.Kind> kind() {
		return OnnxExecutionProvider.Kind.fromRuntimeName(epName);
	}

	/**
	 * Whether this is a WebGPU device on a real GPU. A software Vulkan adapter (lavapipe,
	 * SwiftShader) reports a CPU hardware type and is not taken: it would claim "GPU" while
	 * running slower than the CPU provider.
	 */
	public boolean webGpu() {
		return kind().filter(OnnxExecutionProvider.Kind.WEBGPU::equals).isPresent()
				&& hardwareType == HARDWARE_TYPE_GPU;
	}

	/** The provider and device a session on this handle will run on. */
	public OnnxExecutionProvider provider() {
		OnnxExecutionProvider.Kind kind = kind().orElseThrow(
				() -> new IllegalStateException("unknown execution provider: " + epName));
		return new OnnxExecutionProvider(kind, device);
	}
}
