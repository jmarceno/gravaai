package dev.gravaai.ui.launch;

import dev.gravaai.media.audio.PulseAudio;
import dev.gravaai.media.audio.PulseSource;
import dev.gravaai.media.capture.WebcamDevice;
import dev.gravaai.media.capture.WebcamDevices;
import java.util.List;

/**
 * Device enumeration for the HUD menus (plan §5.6.6, §5.6.9). The React HUD used
 * {@code enumerateDevices}; in Java the same facts come from the media layer: PulseAudio sources
 * for microphones (monitor sources excluded) and sysfs V4L2 nodes for cameras.
 */
public final class HudDevices {

	private HudDevices() {}

	/** Microphone sources: every non-monitor PulseAudio source. */
	public static List<HudRecordingPort.MicDevice> microphones() {
		List<PulseSource> sources = PulseAudio.listSources();
		return sources.stream()
				.filter(source -> !isMonitor(source))
				.map(source -> new HudRecordingPort.MicDevice(source.name(), source.description()))
				.toList();
	}

	private static boolean isMonitor(PulseSource source) {
		return source.name().endsWith(".monitor") || PulseAudio.DEFAULT_MONITOR.equals(source.name());
	}

	/** Cameras from {@code /sys/class/video4linux} (index 0 nodes only). */
	public static List<HudRecordingPort.CameraDevice> cameras() {
		List<WebcamDevice> devices = WebcamDevices.list();
		return devices.stream()
				.map(device -> new HudRecordingPort.CameraDevice(device.path(), device.name()))
				.toList();
	}
}
