package dev.gravaai.media.onnx;

/**
 * Which ONNX Runtime execution provider a session runs on (super-resolution is the only feature
 * that asks; every other model stays on the CPU).
 *
 * <p>The GPU path is the vendor-neutral <b>WebGPU plugin EP</b>, which on Linux runs through Dawn
 * on Vulkan: one library for AMD (RADV/AMDVLK), Intel (ANV) and NVIDIA. The CPU EP is built into
 * the bundled runtime and is always available, so it is the guaranteed fallback.</p>
 *
 * @param kind  the runtime's own provider name
 * @param device the hardware the provider will run on, for the UI
 */
public record OnnxExecutionProvider(Kind kind, Device device) {

	/** The provider families this app ships. */
	public enum Kind {
		/** The bundled CPU provider. */
		CPU("CPUExecutionProvider", "CPU"),
		/** The WebGPU plugin provider: Dawn over Vulkan on Linux. */
		WEBGPU("WebGpuExecutionProvider", "WebGPU");

		private final String runtimeName;
		private final String label;

		Kind(String runtimeName, String label) {
			this.runtimeName = runtimeName;
			this.label = label;
		}

		/** The name {@code OrtApi.EpDevice_EpName} reports for this provider. */
		public String runtimeName() {
			return runtimeName;
		}

		/** The short label the settings panel shows. */
		public String label() {
			return label;
		}

		/**
		 * The kind for a runtime provider name, or empty when it is neither of ours (a different
		 * runtime build, or a provider we never append).
		 */
		public static java.util.Optional<Kind> fromRuntimeName(String name) {
			for (Kind kind : values()) {
				if (kind.runtimeName.equals(name)) {
					return java.util.Optional.of(kind);
				}
			}
			return java.util.Optional.empty();
		}
	}

	/**
	 * The hardware a session's provider runs on.
	 *
	 * @param name     a human-readable device name ("AMD Radeon RX 560", "CPU")
	 * @param vendorId the PCI vendor id Dawn reported, or 0 when unknown
	 * @param deviceId the PCI device id Dawn reported, or 0 when unknown
	 */
	public record Device(String name, int vendorId, int deviceId) {

		public Device {
			if (name.isBlank()) {
				throw new IllegalArgumentException("device name must not be blank");
			}
		}

		/** The CPU, as the settings panel names it. */
		public static final Device CPU = new Device("CPU", 0, 0);
	}

	/** The provider and device sessions fall back to when no GPU device is usable. */
	public static final OnnxExecutionProvider CPU =
			new OnnxExecutionProvider(Kind.CPU, Device.CPU);

	/** Whether this provider runs on a GPU. */
	public boolean hardware() {
		return kind != Kind.CPU;
	}

	/**
	 * What the settings panel shows: "WebGPU · AMD Radeon RX 560" or "CPU".
	 */
	public String label() {
		return kind == Kind.CPU ? kind.label() : kind.label() + " · " + device.name();
	}
}
