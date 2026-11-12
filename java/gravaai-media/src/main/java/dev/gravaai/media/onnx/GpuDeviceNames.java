package dev.gravaai.media.onnx;

import java.util.Map;

/**
 * Human-readable GPU names from the PCI ids ONNX Runtime reports.
 *
 * <p>Dawn identifies the physical adapter by PCI vendor/device id, not by name, so an RX 560 shows
 * up as {@code 0x1002/0x67ff}. The settings panel is the only consumer and it must not print raw
 * hex, so this maps the small set of ids the app has actually been validated on and falls back to
 * a vendor + id label for everything else. A full {@code pci.ids} lookup would be a better name but
 * it is a distro package with no stable path; this list is enough to be truthful about the cards
 * the app supports and honest ("AMD GPU 0x1234") about the rest.</p>
 */
public final class GpuDeviceNames {

	/** PCI vendor ids the three supported Vulkan drivers report. */
	static final Map<Integer, String> VENDORS = Map.of(
			0x1002, "AMD",
			0x8086, "Intel",
			0x10de, "NVIDIA");

	/** Cards validated by name (vendor id + device id), keyed the same way Dawn reports them. */
	static final Map<Long, String> DEVICES = Map.of(
			key(0x1002, 0x67FF), "AMD Radeon RX 560",
			key(0x1002, 0x67EF), "AMD Radeon RX 460",
			key(0x1002, 0x699F), "AMD Radeon RX 550");

	private GpuDeviceNames() {}

	private static long key(int vendorId, int deviceId) {
		return ((long) vendorId << 32) | (deviceId & 0xFFFF_FFFFL);
	}

	/**
	 * A name for the PCI device, or "GPU" when the ids are unknown (a software adapter, a driver
	 * that reported nothing).
	 */
	public static String describe(int vendorId, int deviceId) {
		String known = DEVICES.get(key(vendorId, deviceId));
		if (known != null) {
			return known;
		}
		String vendor = VENDORS.get(vendorId);
		if (vendor != null && deviceId != 0) {
			return String.format(java.util.Locale.ROOT, "%s GPU 0x%04x", vendor, deviceId);
		}
		if (vendor != null) {
			return vendor + " GPU";
		}
		if (deviceId != 0) {
			return String.format(java.util.Locale.ROOT, "GPU 0x%04x:0x%04x", vendorId, deviceId);
		}
		return "GPU";
	}
}
