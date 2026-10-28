package dev.gravaai.services.extensions;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Data;

import dev.gravaai.services.extensions.canvas.Canvas2D;
import dev.gravaai.services.extensions.canvas.CanvasImage;

/**
 * A headless Skija raster surface with a {@link Canvas2D} on top, for the pixel assertions of the
 * extension canvas and render-hook tests (no GL context needed).
 */
final class RasterCanvas implements AutoCloseable {

	private final Surface surface;
	private final Canvas2D canvas;
	private final int width;
	private final int height;

	RasterCanvas(int width, int height) {
		this.width = width;
		this.height = height;
		this.surface = Surface.makeRasterN32Premul(width, height);
		this.surface.getCanvas().clear(0);
		this.canvas = new Canvas2D(surface.getCanvas(), width, height);
	}

	Canvas2D canvas() {
		return canvas;
	}

	/** The raw Skija canvas, for seam adapters that receive the compositor canvas. */
	io.github.humbleui.skija.Canvas skijaCanvas() {
		return surface.getCanvas();
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	/** Unpremultiplied ARGB of one pixel of the current surface. */
	int argb(int x, int y) {
		try (Image snapshot = surface.makeImageSnapshot();
				Bitmap bitmap = new Bitmap()) {
			bitmap.allocN32Pixels(width, height);
			if (!snapshot.readPixels(bitmap, 0, 0)) {
				throw new IllegalStateException("readPixels failed");
			}
			return bitmap.getColor(x, y);
		}
	}

	int alpha(int x, int y) {
		return (argb(x, y) >>> 24) & 0xFF;
	}

	int red(int x, int y) {
		return (argb(x, y) >>> 16) & 0xFF;
	}

	int green(int x, int y) {
		return (argb(x, y) >>> 8) & 0xFF;
	}

	int blue(int x, int y) {
		return argb(x, y) & 0xFF;
	}

	boolean transparent(int x, int y) {
		return alpha(x, y) == 0;
	}

	@Override
	public void close() {
		canvas.close();
		surface.close();
	}

	/** A solid image for {@code drawImage} tests. */
	static CanvasImage solidImage(int width, int height, int argb) {
		try (Surface imageSurface = Surface.makeRasterN32Premul(width, height)) {
			imageSurface.getCanvas().clear(argb);
			// CanvasImage takes ownership of the snapshot and closes it.
			return CanvasImage.of(imageSurface.makeImageSnapshot());
		}
	}

	/** PNG bytes for {@code CanvasImage.fromEncoded} tests. */
	static byte[] pngBytes(int width, int height, int argb) {
		try (Surface imageSurface = Surface.makeRasterN32Premul(width, height)) {
			imageSurface.getCanvas().clear(argb);
			try (Image image = imageSurface.makeImageSnapshot();
					Data data = image.encodeToData(EncodedImageFormat.PNG)) {
				return data.getBytes();
			}
		}
	}

	/** An image info for tests that need one directly. */
	static ImageInfo imageInfo(int width, int height) {
		return new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE, ColorSpace.getSRGB());
	}
}
