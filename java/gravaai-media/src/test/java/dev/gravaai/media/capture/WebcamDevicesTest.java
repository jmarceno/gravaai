package dev.gravaai.media.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Webcam discovery from sysfs (plan §5.6.6). */
class WebcamDevicesTest {

	@Test
	@DisplayName("lists only capture nodes (index 0) with an existing /dev node")
	void listsCaptureNodes() {
		List<WebcamDevice> devices = WebcamDevices.list();
		assertNotNull(devices);
		for (WebcamDevice device : devices) {
			assertTrue(device.path().startsWith("/dev/video"), "unexpected path " + device.path());
			assertTrue(Files.exists(Path.of(device.path())),
					"reported device without a node: " + device.path());
			assertFalse(device.name().isBlank(), "blank name for " + device.path());
		}
		// Every sysfs video node with index 0 must appear at most once.
		assertTrue(devices.stream().map(WebcamDevice::path).distinct().count() == devices.size());
	}

	@Test
	@DisplayName("list() is empty or a stable copy, never null")
	void listIsStable() {
		List<WebcamDevice> first = WebcamDevices.list();
		List<WebcamDevice> second = WebcamDevices.list();
		assertTrue(first.equals(second), "device list changed between calls: " + first + " vs " + second);
	}
}
