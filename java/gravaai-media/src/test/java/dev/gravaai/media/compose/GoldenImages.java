package dev.gravaai.media.compose;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;

/** PNG reading/writing for the golden-frame harness (via Skija codecs). */
final class GoldenImages {

	private GoldenImages() {}

	/** Decodes a PNG/JPEG file into top-first straight RGBA. */
	static RgbaFrame read(Path file) throws IOException {
		byte[] encoded = Files.readAllBytes(file);
		Image image = Image.makeFromEncoded(encoded);
		if (image == null) {
			throw new IllegalArgumentException("cannot decode image: " + file);
		}
		try {
			int width = image.getWidth();
			int height = image.getHeight();
			ImageInfo info = new ImageInfo(width, height, ColorType.RGBA_8888,
					ColorAlphaType.UNPREMUL, ColorSpace.getSRGB());
			Bitmap bitmap = new Bitmap();
			try {
				if (!bitmap.allocPixels(info)) {
					throw new IllegalStateException("cannot allocate bitmap for " + file);
				}
				if (!image.readPixels(bitmap, 0, 0)) {
					throw new IllegalStateException("cannot read pixels from " + file);
				}
				byte[] pixels = bitmap.readPixels();
				var buffer = java.nio.ByteBuffer.allocateDirect(width * height * 4);
				buffer.put(pixels);
				buffer.position(0);
				return new RgbaFrame(width, height, buffer);
			} finally {
				bitmap.close();
			}
		} finally {
			image.close();
		}
	}

	/** Encodes top-first RGBA pixels as PNG. */
	static byte[] encodePng(RgbaFrame frame) {
		ImageInfo info = new ImageInfo(frame.width(), frame.height(), ColorType.RGBA_8888,
				ColorAlphaType.OPAQUE, ColorSpace.getSRGB());
		byte[] pixels = new byte[frame.height() * frame.strideBytes()];
		frame.pixelsTopFirstRgba().duplicate().get(pixels, 0, pixels.length);
		Image image = Image.makeRaster(info, pixels, frame.strideBytes());
		if (image == null) {
			throw new IllegalStateException("cannot make raster image for diff");
		}
		try {
			Data data = image.encodeToData(EncodedImageFormat.PNG);
			try {
				return data.getBytes();
			} finally {
				data.close();
			}
		} finally {
			image.close();
		}
	}

	/** Writes a frame as a PNG file. */
	static void writePng(RgbaFrame frame, Path file) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Files.write(file, encodePng(frame));
	}
}
