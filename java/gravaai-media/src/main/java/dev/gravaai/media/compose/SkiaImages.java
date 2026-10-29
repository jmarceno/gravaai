package dev.gravaai.media.compose;

import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;

/**
 * Converts decoded frames into the Skia raster images the compositor draws (plan §5.8:
 * "swscale to RGBA, then {@code Image.makeRasterFromBytes(...)}, drawn on the GPU surface (Skia uploads
 * it). Start with this.").
 *
 * <p>The pixels are copied into a byte[] because that is the allocation-free-ownership form
 * {@code Image.makeRaster} documents; the compositor flushes the surface before closing the
 * image, so Skia never reads freed pixels. A zero-copy {@code Pixmap}/{@code Data} path can
 * replace this later if the preview misses its frame budget (decision rule R2).</p>
 */
final class SkiaImages {

	private SkiaImages() {}

	/**
	 * Creates a Skia image for one frame.
	 *
	 * @param frame the decoded frame
	 * @param colorSpace the target colour space (the compositor's sRGB space)
	 * @return an image the caller must close after the surface flush
	 */
	static Image fromRgbaFrame(RgbaFrame frame, ColorSpace colorSpace) {
		int stride = frame.strideBytes();
		byte[] pixels = new byte[frame.height() * stride];
		frame.pixelsTopFirstRgba().duplicate().get(pixels, 0, pixels.length);
		ImageInfo info = new ImageInfo(frame.width(), frame.height(), ColorType.RGBA_8888,
				ColorAlphaType.OPAQUE, colorSpace);
		Image image = Image.makeRasterFromBytes(info, pixels, stride);
		if (image == null) {
			throw new IllegalStateException("Image.makeRaster returned null for "
					+ frame.width() + "x" + frame.height());
		}
		return image;
	}
}
