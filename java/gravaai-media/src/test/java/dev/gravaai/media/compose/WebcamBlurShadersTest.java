package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

/**
 * Raster-level coverage of the person-through-mask shader: no GL required, so a mask or premul
 * regression here is separate from the texture path the GL tests cover.
 */
class WebcamBlurShadersTest {

	private static RgbaFrame frame(int width, int height, MaskFill fill) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				fill.pixel(buffer, x, y);
			}
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private interface MaskFill {
		void pixel(ByteBuffer buffer, int x, int y);
	}

	private static Image image(RgbaFrame frame, ColorAlphaType alphaType) {
		int stride = frame.strideBytes();
		byte[] pixels = new byte[frame.height() * stride];
		frame.pixelsTopFirstRgba().duplicate().get(pixels, 0, pixels.length);
		ImageInfo info = new ImageInfo(frame.width(), frame.height(), ColorType.RGBA_8888,
				alphaType, ColorSpace.getSRGB());
		return Image.makeRasterFromBytes(info, pixels, stride);
	}

	@Test
	@DisplayName("the full two-pass composite (blurred pass + masked person) behaves on raster")
	void twoPassComposite() {
		int width = 80;
		int height = 40;
		// Striped left half, solid green right half — the GL test's webcam frame.
		RgbaFrame webcam = frame(width, height, (buffer, x, y) -> {
			boolean left = x < width / 2;
			boolean blueStripe = (x / 4) % 2 == 0;
			buffer.put((byte) 0)
					.put((byte) (left ? (blueStripe ? 0 : 255) : 255))
					.put((byte) (left ? (blueStripe ? 255 : 0) : 0))
					.put((byte) 255);
		});
		RgbaFrame mask = frame(width, height, (buffer, x, y) -> {
			byte value = (byte) (x < width / 2 ? 0 : 255);
			buffer.put(value).put(value).put(value).put(value);
		});

		try (Image webcamImage = image(webcam, ColorAlphaType.OPAQUE);
				Image maskImage = image(mask, ColorAlphaType.UNPREMUL);
				Surface surface = Surface.makeRaster(new ImageInfo(width, height,
						ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.getSRGB()))) {
			surface.getCanvas().drawColor(0xFF141414);
			try (ImageFilter blur = ImageFilter.makeBlur(2.8f, 2.8f, FilterTileMode.DECAL)) {
				fillImageProxy(surface.getCanvas(), webcamImage, blur);
			}
			try (Shader shader = WebcamBlurShaders.personThroughMask(webcamImage, maskImage,
					Matrix33.IDENTITY, SamplingMode.LINEAR); Paint paint = new Paint()) {
				paint.setShader(shader);
				surface.getCanvas().drawRect(Rect.makeWH(width, height), paint);
			}
			Image snapshot = surface.makeImageSnapshot();
			try {
				ByteBuffer pixels = snapshot.peekPixels();
				int person = (width * 20 + 60) * 4;
				// σ2.8 at 30px from the nearest stripe boundary leaves the green half intact.
				assertTrue(Byte.toUnsignedInt(pixels.get(person + 1)) > 200,
						"person half must stay sharp after both passes: "
								+ Byte.toUnsignedInt(pixels.get(person)) + ","
								+ Byte.toUnsignedInt(pixels.get(person + 1)) + ","
								+ Byte.toUnsignedInt(pixels.get(person + 2)));
			} finally {
				snapshot.close();
			}
		}
	}

	/** The compositor's blurred pass, at raster scale (shader draw + filter). */
	private static void fillImageProxy(io.github.humbleui.skija.Canvas canvas, Image image,
			ImageFilter filter) {
		try (Shader shader = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP);
				Paint paint = new Paint()) {
			paint.setShader(shader);
			paint.setImageFilter(filter);
			canvas.drawRect(Rect.makeWH(image.getWidth(), image.getHeight()), paint);
		}
	}

	@Test
	@DisplayName("draws the person sharp and leaves the masked background transparent")
	void drawsPersonThroughMask() {
		int width = 80;
		int height = 40;
		// Solid green right half (person), solid blue left half (background).
		RgbaFrame webcam = frame(width, height, (buffer, x, y) -> buffer
				.put((byte) 0)
				.put((byte) (x < width / 2 ? 0 : 255))
				.put((byte) (x < width / 2 ? 255 : 0))
				.put((byte) 255));
		// Mask: left half 0, right half 255, value in every channel.
		RgbaFrame mask = frame(width, height, (buffer, x, y) -> {
			byte value = (byte) (x < width / 2 ? 0 : 255);
			buffer.put(value).put(value).put(value).put(value);
		});

		try (Image webcamImage = image(webcam, ColorAlphaType.OPAQUE);
				Image maskImage = image(mask, ColorAlphaType.UNPREMUL);
				Surface surface = Surface.makeRaster(new ImageInfo(width, height,
						ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.getSRGB()))) {
			surface.getCanvas().drawColor(0xFF141414);
			try (Shader shader = WebcamBlurShaders.personThroughMask(webcamImage, maskImage,
					Matrix33.IDENTITY, SamplingMode.LINEAR); Paint paint = new Paint()) {
				paint.setShader(shader);
				surface.getCanvas().drawRect(Rect.makeWH(width, height), paint);
			}
			Image snapshot = surface.makeImageSnapshot();
			try {
				ByteBuffer pixels = snapshot.peekPixels();
				int person = (width * 20 + 60) * 4;
				int background = (width * 20 + 20) * 4;
				assertTrue(Byte.toUnsignedInt(pixels.get(person + 1)) > 200
						&& Byte.toUnsignedInt(pixels.get(person + 2)) < 60,
						"person half must stay sharp: " + Byte.toUnsignedInt(pixels.get(person))
								+ "," + Byte.toUnsignedInt(pixels.get(person + 1)) + ","
								+ Byte.toUnsignedInt(pixels.get(person + 2)));
				assertEquals(20, Byte.toUnsignedInt(pixels.get(background + 1)),
						"masked-out background must show through untouched (green)");
				assertEquals(20, Byte.toUnsignedInt(pixels.get(background + 2)),
						"masked-out background must show through untouched (blue)");
			} finally {
				snapshot.close();
			}
		}
	}
}
