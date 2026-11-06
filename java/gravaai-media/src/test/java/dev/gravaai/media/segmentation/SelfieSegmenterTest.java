package dev.gravaai.media.segmentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxLibs;

/**
 * Real-inference coverage: the committed selfie-segmenter model runs through the bundled ONNX
 * Runtime on a real portrait (the MediaPipe sample image, committed as a fixture). Device-bound:
 * aborts cleanly when the native library or the model is missing.
 */
class SelfieSegmenterTest {

	private static Path modelFile;

	@BeforeAll
	static void assumeNativeAvailable() {
		try {
			modelFile = OnnxLibs.resolveModelFile();
			OnnxLibs.ensureLoaded();
		} catch (RuntimeException unavailable) {
			modelFile = null;
		}
		Assumptions.assumeTrue(modelFile != null && Files.isRegularFile(modelFile),
				"selfie-segmenter.onnx not found: run java/native/build-onnxruntime.sh");
		Assumptions.assumeTrue(OnnxLibs.libraryDir() != null,
				"libonnxruntime.so not found: run java/native/build-onnxruntime.sh");
	}

	/** Decodes the committed portrait into an RGBA frame at its native size. */
	private static RgbaFrame portraitFrame() throws Exception {
		try (var input = SelfieSegmenterTest.class
				.getResourceAsStream("/segmentation/portrait.jpg")) {
			Assumptions.assumeTrue(input != null, "portrait fixture missing");
			BufferedImage image = ImageIO.read(input);
			Assumptions.assumeTrue(image != null, "JPEG decoder unavailable");
			int width = image.getWidth();
			int height = image.getHeight();
			int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
			ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
			for (int pixel : pixels) {
				buffer.put((byte) ((pixel >> 16) & 0xff)).put((byte) ((pixel >> 8) & 0xff))
						.put((byte) (pixel & 0xff)).put((byte) 255);
			}
			buffer.position(0);
			return new RgbaFrame(width, height, buffer);
		}
	}

	private static float maskValueAt(RgbaFrame mask, double xRatio, double yRatio) {
		int x = (int) Math.round(xRatio * (mask.width() - 1));
		int y = (int) Math.round(yRatio * (mask.height() - 1));
		// The mask value sits in every channel; the alpha channel is the sampled one.
		int offset = (y * mask.width() + x) * 4;
		return Byte.toUnsignedInt(mask.pixelsTopFirstRgba().get(offset + 3)) / 255f;
	}

	@Test
	@Timeout(120)
	@DisplayName("separates the person from the background on a real portrait")
	void segmentsPersonRegion() throws Exception {
		try (SelfieSegmenter segmenter = new SelfieSegmenter(modelFile)) {
			RgbaFrame frame = portraitFrame();
			RgbaFrame mask = segmenter.maskFor(frame);
			// The mask keeps the model's resolution: the compositor scales it onto the card, so
			// a per-frame full-frame upscale here would be pure waste.
			assertTrue(mask.width() <= frame.width() && mask.height() <= frame.height(),
					"the mask must not be upscaled past the frame: " + mask.width() + "x"
							+ mask.height() + " for " + frame.width() + "x" + frame.height());
			assertTrue(mask.width() > 0 && mask.height() > 0, "the mask must not be empty");

			// The portrait fills most of the frame with the person centre-frame; the top corners
			// are plain studio background.
			float face = maskValueAt(mask, 0.5, 0.25);
			float torso = maskValueAt(mask, 0.5, 0.7);
			float corner = maskValueAt(mask, 0.03, 0.03);
			assertTrue(face > 0.7f, "the face must be inside the person mask: " + face);
			assertTrue(torso > 0.7f, "the torso must be inside the person mask: " + torso);
			assertTrue(corner < 0.3f, "the plain corner must be outside the mask: " + corner);
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("mask buffers are immutable once handed out (fresh allocation per frame)")
	void handsOutFreshBuffers() throws Exception {
		try (SelfieSegmenter segmenter = new SelfieSegmenter(modelFile)) {
			RgbaFrame frame = portraitFrame();
			RgbaFrame first = segmenter.maskFor(frame);
			RgbaFrame second = segmenter.maskFor(frame);
			assertTrue(first.pixelsTopFirstRgba() != second.pixelsTopFirstRgba(),
					"mask buffers must not be reused in place (the queue snapshots them)");
		}
	}
}
