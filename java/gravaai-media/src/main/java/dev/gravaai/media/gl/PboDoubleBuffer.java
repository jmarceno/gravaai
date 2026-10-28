package dev.gravaai.media.gl;

import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL30C.glMapBufferRange;
import static org.lwjgl.opengl.GL33C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_STREAM_READ;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.glBindBuffer;
import static org.lwjgl.opengl.GL33C.glBufferData;
import static org.lwjgl.opengl.GL33C.glDeleteBuffers;
import static org.lwjgl.opengl.GL33C.glGenBuffers;
import static org.lwjgl.opengl.GL33C.glReadPixels;
import static org.lwjgl.opengl.GL33C.glUnmapBuffer;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memCopy;

import java.nio.ByteBuffer;

/**
 * Two pixel-pack buffers, one-frame-latency readback of the bound framebuffer (plan §5.8, spike
 * {@code GlPreviewRenderer}): start reading frame N into one PBO while frame N-1 is mapped and
 * copied out of the other, so the GPU never stalls on the CPU copy.
 *
 * <p>Rows come out top-first because the Skia surface uses {@code SurfaceOrigin.TOP_LEFT}; the
 * orientation contract is asserted by {@code GlOrientationTest}.</p>
 *
 * <p>Must be used on the GL thread with a framebuffer bound. {@link #finishPreviousFrame}
 * writes into a caller-owned direct buffer without changing its position/limit; the buffer is
 * only valid until the consumer returns.</p>
 */
public final class PboDoubleBuffer implements AutoCloseable {

	private final int width;
	private final int height;
	private final PboFormat format;
	private final int sizeBytes;
	private final int[] buffers = new int[2];
	private int writeIndex;
	private int readIndex;
	private int framesStarted;
	private boolean closed;

	public PboDoubleBuffer(int width, int height, PboFormat format) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid readback size: " + width + "x" + height);
		}
		this.width = width;
		this.height = height;
		this.format = format;
		this.sizeBytes = width * height * format.bytesPerPixel();
		for (int i = 0; i < buffers.length; i++) {
			buffers[i] = glGenBuffers();
			glBindBuffer(GL_PIXEL_PACK_BUFFER, buffers[i]);
			glBufferData(GL_PIXEL_PACK_BUFFER, sizeBytes, GL_STREAM_READ);
		}
		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public int sizeBytes() {
		return sizeBytes;
	}

	/** Asynchronously reads the currently bound framebuffer into the write PBO. */
	public void startReadback() {
		ensureOpen();
		glBindBuffer(GL_PIXEL_PACK_BUFFER, buffers[writeIndex]);
		glReadPixels(0, 0, width, height, format.glFormat(), GL_UNSIGNED_BYTE, 0L);
		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
		writeIndex ^= 1;
		framesStarted++;
	}

	/**
	 * Maps the previous frame's PBO and copies it into {@code destination}.
	 *
	 * @param destination a direct buffer with at least {@link #sizeBytes()} remaining bytes
	 * @return {@code true} when a frame was copied; {@code false} until two readbacks have been
	 *         started (the first frame has nothing older to copy)
	 */
	public boolean finishPreviousFrame(ByteBuffer destination) {
		ensureOpen();
		if (framesStarted < 2) {
			return false;
		}
		requireDirect(destination);
		glBindBuffer(GL_PIXEL_PACK_BUFFER, buffers[readIndex]);
		boolean copied = false;
		try {
			ByteBuffer mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0L, sizeBytes, GL_MAP_READ_BIT);
			if (mapped != null) {
				memCopy(memAddress(mapped), memAddress(destination), sizeBytes);
				copied = true;
			}
		} finally {
			glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
			glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
		}
		if (copied) {
			readIndex ^= 1;
		}
		return copied;
	}

	/**
	 * Synchronous readback into {@code destination} (export/tests: no frame of latency).
	 * Call {@link SkiaGlSurface#resetGlState()} afterwards if Skia draws again.
	 */
	public void readSynchronously(ByteBuffer destination) {
		ensureOpen();
		requireDirect(destination);
		glReadPixels(0, 0, width, height, format.glFormat(), GL_UNSIGNED_BYTE,
				memAddress(destination));
	}

	private static void requireDirect(ByteBuffer destination) {
		if (destination == null || !destination.isDirect()) {
			throw new IllegalArgumentException("readback destination must be a direct ByteBuffer");
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("PBO readback is closed");
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		glDeleteBuffers(buffers);
	}
}
