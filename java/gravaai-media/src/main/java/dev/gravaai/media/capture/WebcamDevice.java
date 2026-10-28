package dev.gravaai.media.capture;

/**
 * One V4L2 video capture device (plan §5.6.6).
 *
 * @param path the device node, for example {@code /dev/video0}
 * @param name the device name from {@code /sys/class/video4linux/videoN/name}
 */
public record WebcamDevice(String path, String name) {
}
