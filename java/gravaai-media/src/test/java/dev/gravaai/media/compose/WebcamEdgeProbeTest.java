package dev.gravaai.media.compose;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/** Manual probe: renders the default webcam overlay on a light scene to PNG (-Dprobe.out=dir). */
class WebcamEdgeProbeTest {

	private static final int WIDTH = 1280;
	private static final int HEIGHT = 720;

	private static RgbaFrame pattern(int width, int height, int r, int g, int b, boolean checker) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean dark = checker && (((x / 8) + (y / 8)) % 2 == 0);
				buffer.put((byte) (dark ? 30 : r)).put((byte) (dark ? 30 : g))
						.put((byte) (dark ? 30 : b)).put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	@Test
	@Timeout(120)
	void renderProbe() throws Exception {
		String out = System.getProperty("probe.out");
		Assumptions.assumeTrue(out != null, "probe only");
		RgbaFrame source = pattern(WIDTH, HEIGHT, 235, 238, 242, false);
		RgbaFrame webcam = pattern(1280, 720, 200, 150, 120,
				Boolean.getBoolean("probe.checker"));
		WebcamSpec spec = new WebcamSpec(true, "bottom-right", "bottom-right", 1, 1, 40, 40, 40,
				false, 100, 0.3, 24, false, LayoutCrop.FULL, List.of(), 0);
		FrameInput input = new FrameInput(0, 0.0, source, webcam, List.of(),
				new Background.Colour(0xFFF0F0F0), 0, LayoutSpec.FULL_FRAME, ZoomSpec.NONE,
				FrameStyle.DEFAULT, CursorSpec.DEFAULT, spec);
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					try (Compositor compositor = new Compositor(WIDTH, HEIGHT)) {
						compositor.render(surface, input);
					}
				},
				(pixels, width, height, frameIndex) -> {
					ByteBuffer from = pixels.duplicate();
					from.position(0);
					from.limit(width * height * 4);
					snapshot.duplicate().put(from);
					received.countDown();
				}, Duration.ofSeconds(10));
		try {
			preview.start();
			if (!preview.awaitReady() || !received.await(15, TimeUnit.SECONDS)) {
				Assumptions.abort("GL unavailable: " + preview.failure());
			}
			System.out.println("PROBE GL: " + preview.glDescription());
		} finally {
			preview.close();
		}
		int x0 = 1000;
		int y0 = 500;
		double sum = 0;
		double sumSq = 0;
		int n = 0;
		for (int y = y0; y < y0 + 100; y++) {
			for (int x = x0; x < x0 + 100; x++) {
				double v = Byte.toUnsignedInt(snapshot.get((y * WIDTH + x) * 4));
				sum += v;
				sumSq += v * v;
				n++;
			}
		}
		double mean = sum / n;
		System.out.println("PROBE interior red mean=" + mean + " std="
				+ Math.sqrt(sumSq / n - mean * mean));
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int o = (y * WIDTH + x) * 4;
				image.setRGB(x, y, (Byte.toUnsignedInt(snapshot.get(o)) << 16)
						| (Byte.toUnsignedInt(snapshot.get(o + 1)) << 8)
						| Byte.toUnsignedInt(snapshot.get(o + 2)));
			}
		}
		ImageIO.write(image, "png", new File(out, "webcam-probe.png"));
	}
}
