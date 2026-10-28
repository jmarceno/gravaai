package dev.gravaai.media.gl;

import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30C.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30C.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30C.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL30C.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL30C.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL30C.glGenRenderbuffers;
import static org.lwjgl.opengl.GL30C.glRenderbufferStorage;
import static org.lwjgl.opengl.GL30C.glBindRenderbuffer;
import static org.lwjgl.opengl.GL30C.GL_RGBA8;
import static org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33C.GL_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.glBindFramebuffer;
import static org.lwjgl.opengl.GL33C.glBindTexture;
import static org.lwjgl.opengl.GL33C.glDeleteTextures;
import static org.lwjgl.opengl.GL33C.glGenTextures;
import static org.lwjgl.opengl.GL33C.glTexImage2D;
import static org.lwjgl.opengl.GL33C.glTexParameteri;

import java.nio.ByteBuffer;

/**
 * An offscreen RGBA8 framebuffer with a depth/stencil renderbuffer, the render target of the
 * compositor (plan §5.8: "FBO-backed BackendRenderTarget").
 *
 * <p>Skia renders into this FBO through {@link SkiaGlSurface}; the preview and export paths read
 * it back with {@link PboDoubleBuffer}. Must be used on the thread owning the {@link GlContext}.
 */
public final class GlFramebuffer implements AutoCloseable {

	private final int width;
	private final int height;
	private int framebuffer;
	private int colorTexture;
	private int depthStencilBuffer;

	/**
	 * Creates a framebuffer with an RGBA8 color texture and a DEPTH24_STENCIL8 renderbuffer
	 * (Skia's backend render target is created with 8 stencil bits, plan §5.8).
	 */
	public GlFramebuffer(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("invalid framebuffer size: " + width + "x" + height);
		}
		this.width = width;
		this.height = height;
		allocateAttachments();
	}

	private void allocateAttachments() {
		colorTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, colorTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
				(ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);

		depthStencilBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, depthStencilBuffer);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
		glBindRenderbuffer(GL_RENDERBUFFER, 0);

		framebuffer = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER,
				depthStencilBuffer);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		if (status != GL_FRAMEBUFFER_COMPLETE) {
			release();
			throw new IllegalStateException(
					"framebuffer incomplete: 0x" + Integer.toHexString(status));
		}
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public int framebufferId() {
		return framebuffer;
	}

	public int colorTextureId() {
		return colorTexture;
	}

	/** Binds this framebuffer as the draw/read target. */
	public void bind() {
		glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
	}

	/** Unbinds the framebuffer (draws to the default target, which is none in surfaceless mode). */
	public static void unbind() {
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	@Override
	public void close() {
		if (framebuffer == 0 && colorTexture == 0 && depthStencilBuffer == 0) {
			return;
		}
		release();
	}

	private void release() {
		if (framebuffer != 0) {
			glDeleteFramebuffers(framebuffer);
			framebuffer = 0;
		}
		if (depthStencilBuffer != 0) {
			glDeleteRenderbuffers(depthStencilBuffer);
			depthStencilBuffer = 0;
		}
		if (colorTexture != 0) {
			glDeleteTextures(colorTexture);
			colorTexture = 0;
		}
	}
}
