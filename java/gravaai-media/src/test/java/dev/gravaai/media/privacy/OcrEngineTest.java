package dev.gravaai.media.privacy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.onnx.OnnxLibs;

/**
 * Real-inference coverage for {@link OcrEngine} on a committed screen-shaped fixture: the bundled
 * PP-OCRv4 mobile detection and recognition graphs must find and read the four PII lines the image
 * carries. Device-bound, so it aborts cleanly when the library or models are missing.
 */
class OcrEngineTest {

	/** Recognition is allowed to be slightly fuzzy; these lines are rendered at a normal size. */
	private static final float MIN_CONFIDENCE = 0.5f;

	private static Path detModel;
	private static Path recModel;

	@BeforeAll
	static void assumeNativeAvailable() {
		try {
			detModel = OnnxLibs.resolveOcrDetModelFile();
			recModel = OnnxLibs.resolveOcrRecModelFile();
			OnnxLibs.ensureLoaded();
		} catch (RuntimeException unavailable) {
			detModel = null;
			recModel = null;
		}
		Assumptions.assumeTrue(detModel != null && recModel != null
						&& Files.isRegularFile(detModel) && Files.isRegularFile(recModel),
				"PP-OCR models not found: run java/native/build-onnxruntime.sh");
	}

	private static RgbaFrame screenFixture() throws Exception {
		try (var input = OcrEngineTest.class.getResourceAsStream("/privacy/screen_text.png")) {
			Assumptions.assumeTrue(input != null, "screen_text.png fixture missing");
			BufferedImage image = ImageIO.read(input);
			Assumptions.assumeTrue(image != null, "PNG decoder unavailable");
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

	/** Normalises a recognised line for comparison: lowercase, punctuation and spaces collapsed. */
	private static String compact(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9@.+ ]", "").replaceAll(" +", " ")
				.strip();
	}

	private static List<String> readAll(OcrEngine engine) throws Exception {
		return engine.read(screenFixture(), MIN_CONFIDENCE).stream()
				.map(line -> compact(line.text())).toList();
	}

	@Test
	@Timeout(300)
	@DisplayName("reads the PII lines off a screen-shaped fixture")
	void readsScreenText() throws Exception {
		try (OcrEngine engine = OcrEngine.openBundled()) {
			List<String> lines = readAll(engine);
			assertFalse(lines.isEmpty(), "the fixture must produce text lines");
			String joined = String.join(" | ", lines);
			assertTrue(joined.contains("john smith"), "the name line must be read: " + joined);
			assertTrue(joined.contains("john.smith@example.com"),
					"the email line must be read: " + joined);
			assertTrue(joined.contains("555 123 4567"),
					"the phone line must be read: " + joined);
			assertTrue(joined.contains("4532 0151 1283 0366"),
					"the card line must be read: " + joined);
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("text boxes land on the text they came from")
	void boxesAlignWithText() throws Exception {
		try (OcrEngine engine = OcrEngine.openBundled()) {
			List<OcrEngine.TextLine> lines = engine.read(screenFixture(), MIN_CONFIDENCE);
			assertFalse(lines.isEmpty(), "the fixture must produce text lines");
			RgbaFrame frame = screenFixture();
			for (OcrEngine.TextLine line : lines) {
				assertTrue(line.left() >= 0 && line.top() >= 0, "box must start inside the frame: "
						+ line);
				assertTrue(line.right() <= frame.width() && line.bottom() <= frame.height(),
						"box must stay inside the frame: " + line);
				assertTrue(line.height() > 0 && line.width() > 0, "box must not be empty: " + line);
			}
			// Reading order: the first line is the topmost one (the heading).
			assertTrue(lines.getFirst().top() < lines.getLast().top(),
					"lines must come back in top-to-bottom order: " + lines);
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("a blank frame yields no text")
	void blankFrameHasNoText() {
		int width = 640;
		int height = 360;
		ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
		for (int i = 0; i < width * height; i++) {
			pixels.put((byte) 30).put((byte) 30).put((byte) 30).put((byte) 0xff);
		}
		pixels.position(0);
		try (OcrEngine engine = OcrEngine.openBundled()) {
			assertTrue(engine.read(new RgbaFrame(width, height, pixels), MIN_CONFIDENCE).isEmpty(),
					"a featureless frame must not produce text lines");
		}
	}
}
