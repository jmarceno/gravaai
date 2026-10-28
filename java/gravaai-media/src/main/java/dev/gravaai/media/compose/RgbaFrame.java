package dev.gravaai.media.compose;

import java.nio.ByteBuffer;

/**
 * One decoded video (or webcam) frame as packed top-first RGBA, the compositor's input format
 * (plan §5.8: "swscale to RGBA, then Image.makeRaster").
 *
 * <p>The buffer is a direct buffer owned by the decoder ({@code VideoDecoder} /
 * {@code FrameConverter}) and is only valid until that decoder produces the next frame, so the
 * compositor must draw it within the same frame slot. The byte order is R,G,B,A with no
 * premultiplication and no row padding beyond {@code width * 4}.</p>
 *
 * @param width the frame width in pixels
 * @param height the frame height in pixels
 * @param pixelsTopFirstRgba direct buffer of exactly {@code width * height * 4} bytes
 */
public record RgbaFrame(int width, int height, ByteBuffer pixelsTopFirstRgba) {

	public RgbaFrame {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid frame size: " + width + "x" + height);
		}
		if (pixelsTopFirstRgba == null || !pixelsTopFirstRgba.isDirect()) {
			throw new IllegalArgumentException("frame pixels must be a direct ByteBuffer");
		}
		int required = width * height * 4;
		if (pixelsTopFirstRgba.capacity() < required) {
			throw new IllegalArgumentException("frame buffer holds " + pixelsTopFirstRgba.capacity()
					+ " bytes, need " + required + " for " + width + "x" + height);
		}
	}

	/** Bytes per row ({@code width * 4}). */
	public int strideBytes() {
		return width * 4;
	}
}
