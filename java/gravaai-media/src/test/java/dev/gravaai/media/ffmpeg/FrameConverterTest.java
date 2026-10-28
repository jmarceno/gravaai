package dev.gravaai.media.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;

/** {@link FrameConverter} and the Skija PNG thumbnail path (plan §5.7, §7.1 P1.5). */
class FrameConverterTest {

	// The fixtures draw a white 240x160 box on a 0x101820 background; at t=0 the box sits at
	// x=0, y=(height-160)/2.
	private static final int BACKGROUND_R = 0x10;
	private static final int BACKGROUND_G = 0x18;
	private static final int BACKGROUND_B = 0x20;

	@Test
	@DisplayName("converts a frame to RGBA at the requested size (bilinear preview)")
	void convertsToRgbaPreview() {
		ColourInfo colour = MediaProbe.probe(MediaFixtures.h264()).video().orElseThrow().colour();
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264());
				FrameConverter converter = new FrameConverter(1920, 1080,
						FrameConverter.ScaleMode.PREVIEW, colour)) {
			DecodedFrame frame = decoder.next();
			assertNotNull(frame);
			ByteBuffer rgba = converter.convert(frame);
			assertEquals(1920 * 1080 * 4, rgba.remaining());
			assertPixelsMatchLayout(rgba, 1920, 1080);
		}
	}

	@Test
	@DisplayName("converts a half-size frame to RGBA at the requested size (bicubic export)")
	void convertsToRgbaExport() {
		ColourInfo colour = MediaProbe.probe(MediaFixtures.h264()).video().orElseThrow().colour();
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264());
				FrameConverter converter = new FrameConverter(960, 540,
						FrameConverter.ScaleMode.EXPORT, colour)) {
			DecodedFrame frame = decoder.next();
			assertNotNull(frame);
			ByteBuffer rgba = converter.convert(frame);
			assertEquals(960 * 540 * 4, rgba.remaining());
			assertPixelsMatchLayout(rgba, 960, 540);
		}
	}

	@Test
	@DisplayName("conversion reuses one direct buffer")
	void reusesOneDirectBuffer() {
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264());
				FrameConverter converter = FrameConverter.preview(640, 360)) {
			ByteBuffer first = converter.convert(decoder.next());
			ByteBuffer second = converter.convert(decoder.next());
			assertSame(first, second);
			assertTrue(first.isDirect());
			assertPixelsMatchLayout(second, 640, 360);
		}
	}

	@Test
	@DisplayName("writes a PNG thumbnail through Skija")
	void writesPngThumbnailThroughSkija() {
		try (VideoDecoder decoder = VideoDecoder.open(MediaFixtures.h264());
				FrameConverter converter = FrameConverter.preview(640, 360)) {
			ByteBuffer rgba = converter.convert(decoder.next());
			byte[] rgbaBytes = new byte[rgba.remaining()];
			rgba.duplicate().get(rgbaBytes);
			// Skija's N32 is BGRA8888 on little-endian hosts: swap the red and blue channels.
			byte[] bgra = new byte[rgbaBytes.length];
			for (int i = 0; i + 3 < rgbaBytes.length; i += 4) {
				bgra[i] = rgbaBytes[i + 2];
				bgra[i + 1] = rgbaBytes[i + 1];
				bgra[i + 2] = rgbaBytes[i];
				bgra[i + 3] = rgbaBytes[i + 3];
			}
			Image image = Image.makeRaster(ImageInfo.makeN32Premul(640, 360), bgra, 640 * 4L);
			try {
				Data png = image.encodeToData(EncodedImageFormat.PNG);
				try {
					assertNotNull(png);
					byte[] encoded = png.getBytes();
					assertTrue(encoded.length > 1000, "suspiciously small PNG: " + encoded.length);
					assertEquals((byte) 0x89, encoded[0]);
					assertEquals((byte) 'P', encoded[1]);
					assertEquals((byte) 'N', encoded[2]);
					assertEquals((byte) 'G', encoded[3]);
					Image decoded = Image.makeFromEncoded(encoded);
					try {
						assertEquals(640, decoded.getWidth());
						assertEquals(360, decoded.getHeight());
					} finally {
						decoded.close();
					}
				} finally {
					png.close();
				}
			} finally {
				image.close();
			}
		}
	}

	private static void assertPixelsMatchLayout(ByteBuffer rgba, int width, int height) {
		int boxX = 100 * width / 1920 + 10;
		int boxY = 500 * height / 1080;
		int[] box = pixel(rgba, width, boxX, boxY);
		assertTrue(box[0] > 200 && box[1] > 200 && box[2] > 200,
				"expected the white box at (" + boxX + "," + boxY + "), got "
						+ java.util.Arrays.toString(box));
		int[] background = pixel(rgba, width, width / 2, height / 5);
		assertTrue(background[0] < 80 && background[1] < 80 && background[2] < 80,
				"expected the dark background, got " + java.util.Arrays.toString(background));
	}

	private static int[] pixel(ByteBuffer rgba, int width, int x, int y) {
		int offset = (y * width + x) * 4;
		return new int[] {
				Byte.toUnsignedInt(rgba.get(offset)),
				Byte.toUnsignedInt(rgba.get(offset + 1)),
				Byte.toUnsignedInt(rgba.get(offset + 2)),
				Byte.toUnsignedInt(rgba.get(offset + 3))};
	}
}
