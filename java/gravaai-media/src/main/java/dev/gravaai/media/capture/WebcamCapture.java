package dev.gravaai.media.capture;

import java.nio.ByteBuffer;

/**
 * In-process webcam capture through libavdevice's v4l2 input (plan §5.6.6).
 *
 * <p>{@link #open} prefers MJPEG (lower USB bandwidth, higher frame sizes) and falls back to the
 * device's raw format (normally YUYV). Every frame is converted to packed RGBA, top row first,
 * so the HUD preview can upload it to a JavaFX {@code PixelBuffer} and the recorder can feed the
 * same bytes to the encoder.</p>
 *
 * <p>One thread at a time: the recorder opens it and hands it to its capture thread, which reads
 * and closes it (it sits in native code and FFM downcalls pin virtual threads, plan §4.1).
 * {@link #readFrame} fills the buffer with absolute puts; its position is unchanged.</p>
 */
public interface WebcamCapture extends AutoCloseable {

	/**
	 * Opens a camera and negotiates a format.
	 *
	 * @param devicePath the device node, for example {@code /dev/video0}
	 * @param width the requested width
	 * @param height the requested height
	 * @param fps the requested frame rate
	 * @return an open capture
	 * @throws IllegalStateException when the device cannot be opened or has no video stream
	 */
	static WebcamCapture open(String devicePath, int width, int height, int fps) {
		return V4l2WebcamCapture.open(devicePath, width, height, fps);
	}

	/** The format actually negotiated (may differ from the requested one). */
	WebcamFormat format();

	/**
	 * Blocks until the next frame is decoded and writes it into {@code rgbaTopFirst}.
	 *
	 * @param rgbaTopFirst destination of exactly {@code width * height * 4} bytes, packed RGBA,
	 *        top row first
	 * @return {@code true} when a frame was written, {@code false} when the device stopped
	 *         producing frames
	 * @throws IllegalArgumentException when the buffer is smaller than one frame
	 */
	boolean readFrame(ByteBuffer rgbaTopFirst);

	/** The number of frames written by {@link #readFrame(ByteBuffer)} so far. */
	long frameCount();

	/** Releases the device. Idempotent; never throws. */
	@Override
	void close();
}
