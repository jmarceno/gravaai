package dev.gravaai.media.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL30C.GL_RGBA8;
import static org.lwjgl.opengl.GL33C.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL33C.glActiveTexture;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.system.MemoryUtil.memAlloc;

/**
 * The YUV converter draws with raw GL after Skia, which may leave sampler objects bound to the
 * texture unit (its trilinear sampler after drawing a mipmapped layer, for example the webcam).
 * A mipmap min filter turns every mipmap-less texture into an incomplete one, which samples as
 * black — the export showed black frames except when a later Skia draw (text) replaced the
 * sampler. {@link GlYuv420Converter#convert} must therefore unbind the samplers of the units it
 * reads through.
 */
class GlYuv420ConverterTest {

	private static final int WIDTH = 8;
	private static final int HEIGHT = 8;
	/** Solid red through the BT.709 limited-range shader: Y≈62.6, U≈102.3, V≈240. */
	private static final int EXPECTED_Y = 63;
	private static final int EXPECTED_U = 102;
	private static final int EXPECTED_V = 240;

	@Test
	@Timeout(60)
	@DisplayName("a sampler with a mipmap filter left on the unit must not turn the frame black")
	void convertIgnoresLeftoverSampler() {
		try (GlContext context = glOrAbort(); GlYuv420Converter converter =
				new GlYuv420Converter(WIDTH, HEIGHT)) {
			assertTrue(context.renderer() != null, "context reports a renderer");
			int source = solidRedTexture();
			int sampler = glGenSamplers();
			try {
				glSamplerParameteri(sampler, GlYuv420ConverterTest.minFilter(),
						GL_LINEAR_MIPMAP_LINEAR);
				glSamplerParameteri(sampler, GlYuv420ConverterTest.magFilter(), GL_LINEAR);
				glActiveTexture(GL_TEXTURE0);
				glBindSampler(0, sampler);
				assertEquals(sampler, org.lwjgl.opengl.GL33C.glGetInteger(GL_SAMPLER_BINDING),
						"the sampler must be bound for this test to be meaningful");
				ByteBuffer frame = memAlloc(converter.frameBytes());
				try {
					converter.convert(source, frame);
					assertYuv(frame, "with a mipmap sampler left bound");
				} finally {
					org.lwjgl.system.MemoryUtil.memFree(frame);
				}
			} finally {
				glBindSampler(0, 0);
				glDeleteSamplers(sampler);
				glDeleteTextures(source);
			}
		}
	}

	@Test
	@Timeout(60)
	@DisplayName("a clean unit converts solid red to BT.709 limited-range YUV")
	void convertSolidRed() {
		try (GlContext context = glOrAbort(); GlYuv420Converter converter =
				new GlYuv420Converter(WIDTH, HEIGHT)) {
			assertTrue(context.renderer() != null, "context reports a renderer");
			int source = solidRedTexture();
			try {
				ByteBuffer frame = memAlloc(converter.frameBytes());
				try {
					converter.convert(source, frame);
					assertYuv(frame, "on a clean unit");
				} finally {
					org.lwjgl.system.MemoryUtil.memFree(frame);
				}
			} finally {
				glDeleteTextures(source);
			}
		}
	}

	private static int minFilter() {
		return org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
	}

	private static int magFilter() {
		return org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
	}

	private static GlContext glOrAbort() {
		try {
			GlContext context = GlContext.create();
			context.makeCurrent();
			return context;
		} catch (IllegalStateException | UnsatisfiedLinkError unavailable) {
			Assumptions.abort("GL unavailable: " + unavailable.getMessage());
			return null; // not reached
		}
	}

	/** An RGBA8 texture of solid red; bound to unit 0 while it exists. */
	private static int solidRedTexture() {
		int texture = glGenTextures();
		ByteBuffer pixels = memAlloc(WIDTH * HEIGHT * 4);
		try {
			while (pixels.hasRemaining()) {
				pixels.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 255);
			}
			pixels.clear();
			glBindTexture(GL_TEXTURE_2D, texture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE,
					pixels);
			glBindTexture(GL_TEXTURE_2D, 0);
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(pixels);
		}
		// no mipmap pyramid on purpose: the mipmap sampler must not make it incomplete
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexParameteriMinFilterLinear();
		glBindTexture(GL_TEXTURE_2D, 0);
		return texture;
	}

	private static void glTexParameteriMinFilterLinear() {
		org.lwjgl.opengl.GL11C.glTexParameteri(GL_TEXTURE_2D,
				org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		org.lwjgl.opengl.GL11C.glTexParameteri(GL_TEXTURE_2D,
				org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	}

	private static void assertYuv(ByteBuffer frame, String when) {
		int yCentre = frame.get((HEIGHT / 2) * WIDTH + WIDTH / 2) & 0xFF;
		int uFirst = frame.get(HEIGHT * WIDTH) & 0xFF;
		int vFirst = frame.get(HEIGHT * WIDTH + (HEIGHT / 4) * WIDTH) & 0xFF;
		assertTrue(yCentre > 32 && Math.abs(yCentre - EXPECTED_Y) <= 4, "Y " + when + ": " + yCentre
				+ " (black would be 16)");
		assertEquals(EXPECTED_U, uFirst, 4, "U " + when);
		assertEquals(EXPECTED_V, vFirst, 4, "V " + when);
	}
}
