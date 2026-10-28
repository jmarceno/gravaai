package dev.gravaai.media.capture;

/**
 * The format a {@link WebcamCapture} negotiated with the device.
 *
 * @param width the decoded frame width in pixels
 * @param height the decoded frame height in pixels
 * @param fps the negotiated frame rate
 * @param pixelFormat the FFmpeg pixel format name the decoder produces (for example
 *        {@code yuvj422p} for MJPEG, {@code yuyv422} for raw YUYV)
 */
public record WebcamFormat(int width, int height, double fps, String pixelFormat) {
}
