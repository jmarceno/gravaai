package dev.gravaai.media.compose;

import static org.lwjgl.opengl.GL33C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33C.GL_LINEAR;
import static org.lwjgl.opengl.GL33C.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_RGBA8;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL33C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL33C.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL33C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL33C.glBindBuffer;
import static org.lwjgl.opengl.GL33C.glBindTexture;
import static org.lwjgl.opengl.GL33C.glDeleteTextures;
import static org.lwjgl.opengl.GL33C.glGenTextures;
import static org.lwjgl.opengl.GL33C.glPixelStorei;
import static org.lwjgl.opengl.GL33C.glTexImage2D;
import static org.lwjgl.opengl.GL33C.glTexParameteri;
import static org.lwjgl.opengl.GL33C.glTexSubImage2D;
import static org.lwjgl.system.MemoryUtil.memAddress;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

import dev.gravaai.media.gl.SkiaGlSurface;
import io.github.humbleui.skija.BackendTexture;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.GLTextureInfo;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.SurfaceOrigin;

/**
 * Persistent GL textures for the per-frame video layers (screen and webcam).
 *
 * <p>Each frame is uploaded straight from the decoder's direct buffer into a texture that lives as
 * long as the compositor ({@code glTexSubImage2D}, no Java copies), and Skia borrows it. The old
 * path copied every frame into a fresh Java array, again into a raster image, and Skia then
 * allocated and uploaded a new texture for it: about 8 MB of copies and a texture allocation per
 * 1080p layer per frame. Frames in heap buffers fall back to that raster path.</p>
 *
 * <p>GL thread only. Each slot is drawn at most once per frame, and the compositor flushes every
 * frame, so the next upload never races a pending draw of the previous contents.</p>
 */
final class GpuFrameTextures implements AutoCloseable {

	/** One texture per video layer. */
	enum Slot { SOURCE, WEBCAM }

	private record Texture(int id, int width, int height) {}

	private final Map<Slot, Texture> textures = new EnumMap<>(Slot.class);

	/**
	 * Uploads {@code frame} into the slot's texture and returns a Skia image borrowing it; the
	 * caller closes the image (the texture stays). Returns a raster image for heap frames.
	 */
	Image image(SkiaGlSurface target, Slot slot, RgbaFrame frame, ColorSpace colorSpace) {
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		if (!pixels.isDirect() || frame.strideBytes() % 4 != 0) {
			return SkiaImages.fromRgbaFrame(frame, colorSpace);
		}
		Texture texture = textures.get(slot);
		if (texture == null || texture.width() != frame.width()
				|| texture.height() != frame.height()) {
			if (texture != null) {
				glDeleteTextures(texture.id());
			}
			texture = allocate(frame.width(), frame.height());
			textures.put(slot, texture);
		}
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, texture.id());
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		glPixelStorei(GL_UNPACK_ROW_LENGTH, frame.strideBytes() / 4);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, frame.width(), frame.height(), GL_RGBA,
				GL_UNSIGNED_BYTE, memAddress(pixels, 0));
		glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
		// Raw GL changed bindings Skia tracks.
		target.resetGlState();
		BackendTexture backend = BackendTexture.makeGL(frame.width(), frame.height(), false,
				new GLTextureInfo(GL_TEXTURE_2D, texture.id(), GL_RGBA8));
		Image image = Image.borrowTextureFrom(target.directContext(), backend,
				SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888, ColorAlphaType.OPAQUE, colorSpace, null);
		backend.close();
		if (image == null) {
			return SkiaImages.fromRgbaFrame(frame, colorSpace);
		}
		return image;
	}

	private static Texture allocate(int width, int height) {
		int id = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, id);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
				(ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		return new Texture(id, width, height);
	}

	/** Deletes the textures; GL thread only. */
	@Override
	public void close() {
		for (Texture texture : textures.values()) {
			glDeleteTextures(texture.id());
		}
		textures.clear();
	}
}
