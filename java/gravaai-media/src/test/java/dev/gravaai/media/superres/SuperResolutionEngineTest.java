package dev.gravaai.media.superres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.compose.RgbaFrame;
import dev.gravaai.media.compose.ZoomTransform;
import dev.gravaai.media.superres.SuperResolution.Crop;
import io.github.humbleui.types.Rect;

/**
 * The engine against the real pinned network: it opens the installed model, upscales exactly the
 * window it is given at 4&times;, and a held camera reuses its cached window instead of inferring
 * every frame.
 *
 * <p>Skipped unless a model is present: the test runs against {@code
 * -Dgravaai.superres.model=<file>} when set, otherwise the staged copy under
 * {@code java/native/build}, so a checkout without the 4.9 MB asset does not fail the suite.</p>
 */
class SuperResolutionEngineTest {

	private static final Rect SOURCE = Rect.makeXYWH(0, 0, 1920, 1080);
	private static final Rect BOX = Rect.makeXYWH(0, 0, 1920, 1080);

	private static Path modelFile() {
		String configured = System.getProperty("gravaai.superres.model");
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return SuperResolutionTestModel.resolve();
	}

	/** A frame with a checkerboard and a hard vertical edge: SR must preserve both at 4x. */
	private static RgbaFrame testFrame(int width, int height) {
		ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean light = ((x / 8) + (y / 8)) % 2 == 0;
				int base = light ? 210 : 40;
				// A hard dark edge in the middle holds text-like high contrast.
				int value = x > width / 2 ? Math.min(255, base + 30) : base;
				pixels.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
			}
		}
		pixels.flip();
		return new RgbaFrame(width, height, pixels);
	}

	@Test
	@Timeout(300)
	@DisplayName("the real model upscales the visible window at 4x and caches a held camera")
	void upscalesWindowAndCaches() {
		Path model = modelFile();
		Assumptions.assumeTrue(Files.isRegularFile(model),
				"super-resolution model not staged at " + model);
		RgbaFrame frame = testFrame(960, 540);

		try (SuperResolutionEngine engine = SuperResolutionEngine.open(model)) {
			// The source is 960x540 stretched onto a 1920x1080 box: 2 screen px per source px.
			Crop expected = SuperResolution.cropFor(
					Rect.makeXYWH(0, 0, 960, 540), BOX,
					new ZoomTransform.AppliedTransform(5, -4 * 960, -4 * 540), 1920, 1080);
			assertNotNull(expected, "a 5x centred zoom on a half-size source must upscale");

			var upscaled = engine.upscaleFor(frame, Rect.makeXYWH(0, 0, 960, 540), BOX,
					new ZoomTransform.AppliedTransform(5, -4 * 960, -4 * 540), 1920, 1080);
			assertNotNull(upscaled, "the engine returned no window for a deep zoom");
			assertTrue(upscaled.crop().contains(expected),
					"the upscaled window must cover the visible one");
			// The network is a 4x upscaler: the output is exactly four times the window.
			assertEquals(upscaled.crop().width() * SuperResolution.SCALE,
					upscaled.upscaled().width());
			assertEquals(upscaled.crop().height() * SuperResolution.SCALE,
					upscaled.upscaled().height());
			assertTrue(upscaled.upscaled().pixelsTopFirstRgba().isDirect(),
					"the compositor uploads the result straight to GL");
			assertEquals(1, engine.inferenceCount());

			// The same camera again must not run the network twice.
			var again = engine.upscaleFor(frame, Rect.makeXYWH(0, 0, 960, 540), BOX,
					new ZoomTransform.AppliedTransform(5, -4 * 960, -4 * 540), 1920, 1080);
			assertNotNull(again);
			assertEquals(1, engine.inferenceCount(), "a held zoom must reuse the cached window");
			assertEquals(1, engine.cacheHits());
			assertEquals(upscaled.upscaled(), again.upscaled(), "the same buffer is handed out");
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("a mild zoom never runs the network")
	void mildZoomRunsNoInference() {
		Path model = modelFile();
		Assumptions.assumeTrue(Files.isRegularFile(model),
				"super-resolution model not staged at " + model);
		RgbaFrame frame = testFrame(960, 540);
		try (SuperResolutionEngine engine = SuperResolutionEngine.open(model)) {
			assertNull(engine.upscaleFor(frame, Rect.makeXYWH(0, 0, 960, 540), BOX,
					new ZoomTransform.AppliedTransform(1, 0, 0), 1920, 1080));
			assertEquals(0, engine.inferenceCount());
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("the upscale preserves the frame's structure instead of blurring it away")
	void preservesStructure() {
		Path model = modelFile();
		Assumptions.assumeTrue(Files.isRegularFile(model),
				"super-resolution model not staged at " + model);
		RgbaFrame frame = testFrame(960, 540);
		try (SuperResolutionEngine engine = SuperResolutionEngine.open(model)) {
			var upscaled = engine.upscaleFor(frame, Rect.makeXYWH(0, 0, 960, 540), BOX,
					new ZoomTransform.AppliedTransform(5, -4 * 960, -4 * 540), 1920, 1080);
			assertNotNull(upscaled);
			// The checkerboard's light and dark tiles must still be far apart after the network:
			// an all-grey output would pass a size assertion but is exactly the mush this pass
			// exists to fix. Measured over the whole window (individual pixels near a tile edge
			// legitimately overshoot), the dark and light populations stay separated.
			int width = upscaled.upscaled().width();
			int height = upscaled.upscaled().height();
			ByteBuffer pixels = upscaled.upscaled().pixelsTopFirstRgba();
			long darkCount = 0;
			long lightCount = 0;
			double sum = 0;
			long total = (long) width * height;
			for (int i = 0; i < total; i++) {
				int value = Byte.toUnsignedInt(pixels.get(i * 4));
				sum += value;
				if (value < 110) {
					darkCount++;
				} else if (value > 170) {
					lightCount++;
				}
			}
			double mean = sum / total;
			assertTrue(mean > 60 && mean < 200, "the window must not collapse to black or white: "
					+ mean);
			assertTrue(darkCount > total / 10, "dark tiles must survive: " + darkCount + "/" + total);
			assertTrue(lightCount > total / 10,
					"light tiles must survive: " + lightCount + "/" + total);
		}
	}
}
