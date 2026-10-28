package dev.gravaai.media.gl;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

/**
 * A Skija GPU surface over a {@link GlFramebuffer} (plan §5.8): the compositor draws with Skia
 * into the FBO and the preview/export paths read the FBO back with {@link PboDoubleBuffer}.
 *
 * <p>The surface origin is {@link SurfaceOrigin#TOP_LEFT}, so Skia's coordinate system is
 * top-left like Canvas 2D, and the FBO's physical memory is arranged so that
 * {@code glReadPixels} returns row 0 = top row. That convention is asserted by
 * {@code GlOrientationTest} and is what both the JavaFX PixelBuffer and the YUV encoder path
 * require.</p>
 *
 * <p>{@link #resetGlState()} must be called after any raw GL call that touches bindings Skia
 * tracks (the PBO readback does), before Skia draws again (plan §5.8).</p>
 */
public final class SkiaGlSurface implements AutoCloseable {

	private final DirectContext directContext;
	private final BackendRenderTarget renderTarget;
	private final ColorSpace colorSpace;
	private final Surface surface;
	private final int width;
	private final int height;
	private boolean closed;

	private SkiaGlSurface(DirectContext directContext, BackendRenderTarget renderTarget,
			ColorSpace colorSpace, Surface surface, int width, int height) {
		this.directContext = directContext;
		this.renderTarget = renderTarget;
		this.colorSpace = colorSpace;
		this.surface = surface;
		this.width = width;
		this.height = height;
	}

	/**
	 * Creates the Skia GL context and wraps the framebuffer. Requires the {@link GlContext} to be
	 * current on this thread and the framebuffer to be complete.
	 */
	public static SkiaGlSurface create(GlFramebuffer framebuffer) {
		if (framebuffer == null) {
			throw new IllegalArgumentException("framebuffer must not be null");
		}
		DirectContext context = DirectContext.makeGL();
		if (context == null) {
			throw new IllegalStateException("DirectContext.makeGL() returned null");
		}
		ColorSpace srgb = ColorSpace.getSRGB();
		BackendRenderTarget target = BackendRenderTarget.makeGL(
				framebuffer.width(), framebuffer.height(),
				0, 8, framebuffer.framebufferId(), FramebufferFormat.GR_GL_RGBA8);
		if (target == null) {
			context.close();
			throw new IllegalStateException("BackendRenderTarget.makeGL() returned null");
		}
		Surface surface = Surface.makeFromBackendRenderTarget(context, target,
				SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888, srgb);
		if (surface == null) {
			target.close();
			context.close();
			throw new IllegalStateException("Surface.makeFromBackendRenderTarget() returned null");
		}
		return new SkiaGlSurface(context, target, srgb, surface, framebuffer.width(),
				framebuffer.height());
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** The Skia canvas of this surface; valid until {@link #close()}. */
	public Canvas canvas() {
		return surface.getCanvas();
	}

	/** The Skia GPU context, for texture-backed images. */
	public DirectContext directContext() {
		return directContext;
	}

	/** A GPU offscreen surface compatible with this one (stays on the GPU, unlike a raster one). */
	public Surface makeOffscreen(int offscreenWidth, int offscreenHeight) {
		return surface.makeSurface(offscreenWidth, offscreenHeight);
	}

	/** Submits the queued Skia draws to the GL driver. */
	public void flush() {
		directContext.flush(surface);
	}

	/**
	 * Flushes and returns a snapshot of the current surface contents. Used by stage 8 blur
	 * annotations, which sample already-rendered scene pixels; the caller closes the image.
	 */
	public Image snapshot() {
		if (closed) {
			throw new IllegalStateException("surface is closed");
		}
		flush();
		return surface.makeImageSnapshot();
	}

	/** Invalidates Skia's cached GL state after a raw GL call (PBO readback). */
	public void resetGlState() {
		directContext.resetGLAll();
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			surface.close();
		} finally {
			try {
				renderTarget.close();
			} finally {
				try {
					colorSpace.close();
				} finally {
					directContext.close();
				}
			}
		}
	}
}
