package dev.gravaai.media.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxLibs;

/**
 * Real-inference coverage for {@link FaceDetector}: the committed YuNet model runs through the
 * bundled ONNX Runtime on the MediaPipe sample portrait already used by the segmenter test.
 * Device-bound, so it aborts cleanly when the native library or the model is missing.
 */
class FaceDetectorTest {

	private static Path modelFile;

	@BeforeAll
	static void assumeNativeAvailable() {
		try {
			modelFile = OnnxLibs.resolveFaceModelFile();
			OnnxLibs.ensureLoaded();
		} catch (RuntimeException unavailable) {
			modelFile = null;
		}
		Assumptions.assumeTrue(modelFile != null && Files.isRegularFile(modelFile),
				"face_detection_yunet_2023mar.onnx not found: run java/native/build-onnxruntime.sh");
		Assumptions.assumeTrue(OnnxLibs.libraryDir() != null,
				"libonnxruntime.so not found: run java/native/build-onnxruntime.sh");
	}

	private static RgbaFrame portraitFrame() throws Exception {
		return fixtureFrame("/segmentation/portrait.jpg");
	}

	private static RgbaFrame fixtureFrame(String resource) throws Exception {
		try (var input = FaceDetectorTest.class.getResourceAsStream(resource)) {
			Assumptions.assumeTrue(input != null, resource + " fixture missing");
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

	@Test
	@Timeout(120)
	@DisplayName("finds the face in a real portrait, inside the source frame")
	void findsFaceInPortrait() throws Exception {
		try (FaceDetector detector = new FaceDetector(modelFile,
				FaceDetector.DEFAULT_INPUT_SIZE)) {
			RgbaFrame frame = portraitFrame();
			List<FaceBox> faces = detector.detect(frame);
			assertFalse(faces.isEmpty(), "the portrait must produce at least one face");
			FaceBox face = faces.getFirst();
			assertTrue(face.score() >= 0.6f, "kept faces must clear the score threshold: "
					+ face.score());
			assertTrue(face.x() >= 0 && face.y() >= 0, "face box must start inside the frame: "
					+ face);
			assertTrue(face.x() + face.width() <= frame.width() + 1,
					"face box must not extend past the frame width: " + face);
			assertTrue(face.y() + face.height() <= frame.height() + 1,
					"face box must not extend past the frame height: " + face);
			// The portrait is a head-and-shoulders shot: the face is centred horizontally and in
			// the upper half of the frame. A box anywhere else means the decode is misaligned.
			double relativeX = face.centerX() / frame.width();
			double relativeY = face.centerY() / frame.height();
			assertTrue(relativeX > 0.25 && relativeX < 0.75,
					"face must be horizontally centred, was at " + relativeX + ": " + face);
			assertTrue(relativeY > 0.05 && relativeY < 0.6,
					"face must be in the upper half, was at " + relativeY + ": " + face);
			// A face occupies a meaningful share of a portrait, not a few stray pixels.
			assertTrue(face.area() > frame.width() * frame.height() * 0.01,
					"face box looks too small: " + face);
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("a flat blank frame yields no faces")
	void flatFrameHasNoFaces() {
		int width = 320;
		int height = 240;
		ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
		for (int i = 0; i < width * height; i++) {
			pixels.put((byte) 0x20).put((byte) 0x20).put((byte) 0x20).put((byte) 0xff);
		}
		pixels.position(0);
		try (FaceDetector detector = new FaceDetector(modelFile,
				FaceDetector.DEFAULT_INPUT_SIZE)) {
			assertEquals(List.of(), detector.detect(new RgbaFrame(width, height, pixels)),
					"a featureless frame must not produce detections");
		}
	}

	@Test
	@DisplayName("NMS keeps the higher-scoring box of an overlapping pair")
	void nmsSuppressesOverlaps() {
		FaceBox strong = new FaceBox(10, 10, 100, 100, 0.9f);
		FaceBox weakOverlap = new FaceBox(12, 12, 100, 100, 0.7f);
		FaceBox separate = new FaceBox(300, 300, 100, 100, 0.8f);
		List<FaceBox> kept = FaceDetector.nonMaximumSuppression(
				List.of(weakOverlap, separate, strong));
		assertEquals(2, kept.size(), "the overlapping weak box must be suppressed: " + kept);
		assertEquals(strong, kept.get(0), "the highest score must come first: " + kept);
		assertTrue(kept.contains(separate), "a non-overlapping box must survive: " + kept);
	}
}
