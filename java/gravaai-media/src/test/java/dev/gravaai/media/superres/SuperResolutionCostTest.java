package dev.gravaai.media.superres;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * Measures one inference at the window sizes a 1080p deep zoom actually produces, so the export
 * cost of the optional pass can be reported per zoom depth instead of guessed.
 *
 * <p>Opt-in like the artifacts test: {@code -Dgravaai.superres.artifacts=<dir>}. The numbers go to
 * stdout as {@code SUPERRES-SIZE} lines.</p>
 */
class SuperResolutionCostTest {

	private static Path modelFile() {
		String configured = System.getProperty("gravaai.superres.model");
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return SuperResolutionTestModel.resolve();
	}

	@Test
	@Timeout(600)
	@DisplayName("reports inference cost per zoom-window size")
	void reportsCostPerWindow() {
		Assumptions.assumeTrue(System.getProperty("gravaai.superres.artifacts") != null,
				"set -Dgravaai.superres.artifacts=<dir> to measure the upscaler");
		Path model = modelFile();
		Assumptions.assumeTrue(Files.isRegularFile(model), "model not staged at " + model);

		// A 1080p source stretched onto a 1080p canvas: the visible window is canvas / camera
		// scale, and the engine's padding grows it before inference. These are the zoom depths a
		// real timeline runs through (D3 1.8x up to D6 5x).
		RgbaFrame frame = noiseFrame(1920, 1080);
		io.github.humbleui.types.Rect source = io.github.humbleui.types.Rect.makeXYWH(0, 0, 1920, 1080);
		io.github.humbleui.types.Rect box = source;
		double[] scales = {5.0, 4.0, 3.5, 3.0};
		try (SuperResolutionEngine engine = SuperResolutionEngine.open(model)) {
			for (double scale : scales) {
				// Anchor the zoom on the frame centre so the window is fully inside the source.
				var transform = new dev.gravaai.media.compose.ZoomTransform.AppliedTransform(scale,
						-(scale - 1) * 960, -(scale - 1) * 540);
				long started = System.nanoTime();
				var upscaled = engine.upscaleFor(frame, source, box, transform, 1920, 1080);
				long elapsed = (System.nanoTime() - started) / 1_000_000;
				assertTrue(upscaled != null, "no upscale at " + scale + "x");
				System.out.printf(
						"SUPERRES-SIZE scale %.1fx  visible %3dx%-3d  inferred %3dx%-3d"
								+ " (%6d px)  %5d ms%n",
						scale, (int) (1920 / scale), (int) (1080 / scale),
						upscaled.crop().width(), upscaled.crop().height(),
						upscaled.crop().pixels(), elapsed);
				engine.reset();
			}
		}
	}

	private static RgbaFrame noiseFrame(int width, int height) {
		ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
		java.util.Random random = new java.util.Random(7);
		for (int i = 0; i < width * height; i++) {
			int value = random.nextInt(256);
			pixels.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
		}
		pixels.flip();
		return new RgbaFrame(width, height, pixels);
	}
}
