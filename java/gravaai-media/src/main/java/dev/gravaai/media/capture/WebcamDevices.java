package dev.gravaai.media.capture;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists V4L2 webcams the same way the Electron app does (plan §5.6.6):
 * {@code /sys/class/video4linux/video*} + the node's {@code index} field, skipping the metadata
 * nodes (a UVC camera exposes index 0 for video and index 1 for metadata).
 */
public final class WebcamDevices {

	private static final System.Logger LOG = System.getLogger(WebcamDevices.class.getName());
	private static final Path SYSFS_ROOT = Path.of("/sys/class/video4linux");

	private WebcamDevices() {}

	/** The capture devices, ordered by their sysfs number; empty when there is no camera. */
	public static List<WebcamDevice> list() {
		if (!Files.isDirectory(SYSFS_ROOT)) {
			return List.of();
		}
		try (var entries = Files.list(SYSFS_ROOT)) {
			List<Path> nodes = new ArrayList<>(entries
					.filter(WebcamDevices::isVideoNode)
					.filter(node -> readInt(node.resolve("index"), -1) == 0)
					.toList());
			nodes.sort(Comparator.comparingInt(WebcamDevices::sysfsNumber));
			List<WebcamDevice> devices = new ArrayList<>(nodes.size());
			for (Path node : nodes) {
				String path = "/dev/" + node.getFileName();
				if (!Files.exists(Path.of(path))) {
					continue;
				}
				devices.add(new WebcamDevice(path, readName(node.resolve("name"))));
			}
			return List.copyOf(devices);
		} catch (IOException failure) {
			LOG.log(System.Logger.Level.WARNING, "could not list " + SYSFS_ROOT + ": " + failure);
			return List.of();
		}
	}

	private static boolean isVideoNode(Path node) {
		String name = node.getFileName().toString();
		return name.startsWith("video") && name.length() > 5;
	}

	private static int sysfsNumber(Path node) {
		try {
			return Integer.parseInt(node.getFileName().toString().substring("video".length()));
		} catch (NumberFormatException malformed) {
			return Integer.MAX_VALUE;
		}
	}

	private static int readInt(Path file, int fallback) {
		try {
			return Integer.parseInt(Files.readString(file, UTF_8).strip());
		} catch (IOException | NumberFormatException unreadable) {
			return fallback;
		}
	}

	private static String readName(Path file) {
		try {
			String name = Files.readString(file, UTF_8).strip();
			return name.isEmpty() ? "Unknown camera" : name;
		} catch (IOException unreadable) {
			return "Unknown camera";
		}
	}
}
