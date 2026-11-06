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
 * Real-inference coverage for the slow path: the committed MODNet model runs through the bundled
 * ONNX Runtime on the same portrait fixture the fast path is tested with.
 *
 * <p>Device-bound like {@code SelfieSegmenterTest}: aborts cleanly when the native library or the
 * model is missing. That includes a checkout where {@code build-onnxruntime.sh} has not staged the
 * model, which is exactly the deployment gap the lazy opener must survive.</p>
 */
class ModnetSegmenterTest {

	private static Path modelFile;

	@BeforeAll
	static void assumeNativeAvailable() {
		try {
			modelFile = OnnxLibs.resolveModnetModelFile();
			OnnxLibs.ensureLoaded();
		} catch (RuntimeException unavailable) {
			modelFile = null;
		}
		Assumptions.assumeTrue(modelFile != null && Files.isRegularFile(modelFile),
				"modnet.onnx not found: run java/native/build-onnxruntime.sh");
		Assumptions.assumeTrue(OnnxLibs.libraryDir() != null,
				"libonnxruntime.so not found: run java/native/build-onnxruntime.sh");
	}

	/** Decodes the committed portrait into an RGBA frame at its native size. */
	private static RgbaFrame portraitFrame() throws Exception {
		try (var input = ModnetSegmenterTest.class
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
	@DisplayName("segments the person on a real portrait through the bundled MODNet model")
	void segmentsPersonRegion() throws Exception {
		try (ModnetSegmenter segmenter = new ModnetSegmenter(modelFile)) {
			RgbaFrame frame = portraitFrame();
			RgbaFrame mask = segmenter.maskFor(frame);
			// The mask keeps the model's 256 px resolution; the compositor scales it onto the card.
			assertEquals(256, mask.width());
			assertEquals(256, mask.height());
			assertTrue(mask.width() <= frame.width() && mask.height() <= frame.height(),
					"the mask must not be upscaled past the frame: " + mask.width() + "x"
							+ mask.height() + " for " + frame.width() + "x" + frame.height());

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
		try (ModnetSegmenter segmenter = new ModnetSegmenter(modelFile)) {
			RgbaFrame frame = portraitFrame();
			RgbaFrame first = segmenter.maskFor(frame);
			RgbaFrame second = segmenter.maskFor(frame);
			// Identity, not value: the same content would compare equal, which is the trap.
			assertTrue(first.pixelsTopFirstRgba() != second.pixelsTopFirstRgba(),
					"mask buffers must not be reused in place (the queue snapshots them)");
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("edge controls reshape the MODNet mask like they reshape the fast one")
	void edgeControlsShapeTheMask() throws Exception {
		try (ModnetSegmenter segmenter = new ModnetSegmenter(modelFile)) {
			RgbaFrame frame = portraitFrame();
			RgbaFrame shipped = segmenter.maskFor(frame, MaskTuning.DEFAULT);
			RgbaFrame grown = segmenter.maskFor(frame, new MaskTuning(0.5, 4.0));
			// The same tuning reaches both paths, so a paused frame keeps matching the playing one
			// in how close the cut-out sits to the subject: a positive offset grows the mask.
			assertEquals(shipped.width(), grown.width(),
					"edge shaping must not change the mask resolution");
			assertEquals(shipped.height(), grown.height(),
					"edge shaping must not change the mask resolution");
			assertTrue(maskedPixels(grown) > maskedPixels(shipped),
					"a positive edge offset must grow the person: " + maskedPixels(shipped)
							+ " -> " + maskedPixels(grown));
		}
	}

	/** Pixels the mask classifies as person (the value sits in every channel; alpha is sampled). */
	private static int maskedPixels(RgbaFrame mask) {
		ByteBuffer pixels = mask.pixelsTopFirstRgba().duplicate();
		pixels.position(0);
		int count = 0;
		for (int i = 0; i < mask.width() * mask.height(); i++) {
			if (Byte.toUnsignedInt(pixels.get(i * 4 + 3)) >= 128) {
				count++;
			}
		}
		return count;
	}
}
