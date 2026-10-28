package dev.gravaai.media.gl;

import static org.lwjgl.opengl.GL33C.GL_BGRA;
import static org.lwjgl.opengl.GL33C.GL_RGBA;

/** Pixel byte order of a {@link PboDoubleBuffer} readback. */
public enum PboFormat {

	/** Red, green, blue, alpha bytes — the export compositor's input order. */
	RGBA(GL_RGBA),

	/**
	 * Blue, green, red, alpha bytes — the order of a little-endian JavaFX ARGB int, so the
	 * preview can hand the bytes to {@code PixelFormat.getByteBgraPreInstance()} without a
	 * channel swap.
	 */
	BGRA(GL_BGRA);

	private final int glFormat;

	PboFormat(int glFormat) {
		this.glFormat = glFormat;
	}

	int glFormat() {
		return glFormat;
	}

	/** Bytes per pixel, always 4 for the RGBA8 framebuffer. */
	public int bytesPerPixel() {
		return 4;
	}
}
