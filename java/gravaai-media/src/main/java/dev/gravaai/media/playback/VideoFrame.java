package dev.gravaai.media.playback;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One decoded video frame for the playback engine, packed top-first RGBA.
 *
 * <p>The buffer is owned by the decoder adapter, which may recycle it after {@link #release()}
 * runs; the decode-ahead ring releases the frames it drops, and the source releases the frame it
 * handed to the consumer once the consumer asks for the next one (the documented "valid until the
 * next decode" contract). Consumers must not mutate the pixels.</p>
 */
public final class VideoFrame {

	private final int width;
	private final int height;
	private final long ptsUs;
	private final ByteBuffer rgbaTopFirst;
	private final Runnable releaseAction;
	private final AtomicBoolean released = new AtomicBoolean();

	public VideoFrame(int width, int height, long ptsUs, ByteBuffer rgbaTopFirst) {
		this(width, height, ptsUs, rgbaTopFirst, null);
	}

	public VideoFrame(int width, int height, long ptsUs, ByteBuffer rgbaTopFirst,
			Runnable releaseAction) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid frame size: " + width + "x" + height);
		}
		if (ptsUs < 0) {
			throw new IllegalArgumentException("ptsUs must not be negative: " + ptsUs);
		}
		if (rgbaTopFirst == null || !rgbaTopFirst.isDirect()) {
			throw new IllegalArgumentException("frame pixels must be a direct ByteBuffer");
		}
		int required = width * height * 4;
		if (rgbaTopFirst.capacity() < required) {
			throw new IllegalArgumentException("frame buffer holds " + rgbaTopFirst.capacity()
					+ " bytes, need " + required + " for " + width + "x" + height);
		}
		this.width = width;
		this.height = height;
		this.ptsUs = ptsUs;
		this.rgbaTopFirst = rgbaTopFirst;
		this.releaseAction = releaseAction;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public long ptsUs() {
		return ptsUs;
	}

	public ByteBuffer rgbaTopFirst() {
		return rgbaTopFirst;
	}

	/** Releases the frame buffer back to its owner; idempotent when the frame has no owner. */
	public void release() {
		if (released.compareAndSet(false, true) && releaseAction != null) {
			releaseAction.run();
		}
	}
}
