package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;
import dev.gravaai.media.superres.SuperResolutionEngine;
import dev.gravaai.media.superres.SuperResolutionTestModel;

/**
 * The acceptance harness for the deep-zoom upscaler: renders the same 1080p deep-zoom frame with
 * the sharpening pass alone and with the ONNX upscaler, writes both PNGs plus a stacked crop, and
 * reports the compositing cost of each.
 *
 * <p>Opt-in, because it needs a GL context and the staged model: {@code -Dgravaai.superres.artifacts
 * =<dir>}. That keeps the fast suite fast while the card's evidence is reproducible with one
 * command.</p>
 */
class SuperResolutionArtifactsTest {

	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;
	/** 8x7 px cells: the fine grid of a code editor or a table, at 5x it is unreadable. */
	private static final int CELL = 7;
	private static final int FRAMES = 12;

	private static final ZoomRegionSpec MANUAL_HOLD = new ZoomRegionSpec("zoom", 0, 4000,
			ZoomDepth.D6, new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL);
	private static final ZoomSpec ZOOM = new ZoomSpec(List.of(MANUAL_HOLD), 0.5, false, true, null,
			null, 1, 1, 1, 0, true);

	@Test
	@Timeout(600)
	@DisplayName("renders the before/after deep-zoom frames and reports the cost of each")
	void rendersArtifacts() throws Exception {
		String dir = System.getProperty("gravaai.superres.artifacts");
		Assumptions.assumeTrue(dir != null && !dir.isBlank(),
				"set -Dgravaai.superres.artifacts=<dir> to render the acceptance frames");
		Path model = modelFile();
		Assumptions.assumeTrue(Files.isRegularFile(model),
				"super-resolution model not staged at " + model);
		Path out = Path.of(dir);
		Files.createDirectories(out);

		RgbaFrame source = textLikeFrame();

		// Warm up both paths first (GL program compilation, ORT kernels), then measure: the first
		// frame of either path pays one-off costs that say nothing about steady-state export time.
		render(source, null);
		render(source, model);
		Timed sharpened = render(source, null);
		Timed upscaled = render(source, model);

		assertTrue(upscaled.inferences() > 0, "the engine never ran");
		write(out.resolve("superres-before.png"), sharpened.pixels());
		write(out.resolve("superres-after.png"), upscaled.pixels());
		writeSideBySide(out.resolve("superres-before-after-crop.png"), sharpened.pixels(),
				upscaled.pixels());

		// The measurement the card asks for: compositing cost per deep-zoomed frame, split into
		// the frames that run an inference and the frames that reuse the cached window (a held or
		// slowly panning zoom is almost entirely the latter).
		System.out.printf("SUPERRES-COST  sharpening-only median %.2f ms/frame, p90 %.2f%n",
				sharpened.medianMs(), sharpened.p90Ms());
		System.out.printf("SUPERRES-COST  upscaled       median %.2f ms/frame, p90 %.2f%n",
				upscaled.medianMs(), upscaled.p90Ms());
		System.out.printf("SUPERRES-COST  inference      %.0f ms each, %d over %d frames%n",
				upscaled.meanInferenceMs(), upscaled.inferences(), FRAMES);
		System.out.printf("SUPERRES-COST  ratio          %.2fx per cached frame%n",
				upscaled.medianMs() / sharpened.medianMs());

		// The realistic case: a zoom gesture eases in over ~1 s and then holds. The camera moves
		// every frame, so the padded window is what decides how many inferences the whole gesture
		// costs. This is the number a timeline with several deep zooms actually pays.
		Timed gesture = renderGesture(source, model);
		Timed gesturePlain = renderGesture(source, null);
		System.out.printf("SUPERRES-GESTURE inferences %d over %d frames (%.2f per frame)%n",
				gesture.inferences(), FRAMES,
				gesture.inferences() / (double) FRAMES);
		System.out.printf("SUPERRES-GESTURE median %.2f ms/frame vs sharpening-only %.2f ms%n",
				gesture.medianMs(), gesturePlain.medianMs());

		// Contrast must survive: the upscaled frame keeps the fine grid's high-frequency energy
		// that bicubic magnification flattens. Measured as mean absolute horizontal gradient.
		double before = edgeEnergy(sharpened.pixels());
		double after = edgeEnergy(upscaled.pixels());
		System.out.printf("SUPERRES-CONTRAST sharpening-only %.3f, upscaled %.3f%n", before, after);
		assertTrue(after > before * 1.15,
				"the upscaled frame must carry more fine detail: " + after + " vs " + before);
	}

	/**
	 * A zoom gesture: the camera eases from 1x to 5x over the rendered frames and holds. Unlike
	 * {@link #render}, the input differs every frame, which is what a real easing zoom does, so the
	 * engine's padding policy is actually exercised.
	 */
	private static Timed renderGesture(RgbaFrame source, Path model) throws InterruptedException {
		AtomicReference<Compositor> compositorRef = new AtomicReference<>();
		SuperResolutionEngine[] engine = new SuperResolutionEngine[1];
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		java.util.List<Double> frameMs = java.util.Collections.synchronizedList(
				new java.util.ArrayList<>());
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					Compositor compositor = compositorRef.get();
					if (compositor == null) {
						compositor = new Compositor(WIDTH, HEIGHT);
						if (model != null) {
							engine[0] = SuperResolutionEngine.open(model);
							compositor.setSuperResolution(engine[0]);
						}
						compositorRef.set(compositor);
					}
					// The 0-2200 ms region eases the camera from 1x to 5x across these frames, so
					// the upscaler engages mid-gesture and the camera moves every frame.
					long timeMs = 1000 + frameIndex * 1000L / FRAMES;
					FrameInput input = FrameInput.ofSource(frameIndex, timeMs / 1000.0, source,
							Background.black(), 0, LayoutSpec.FULL_FRAME,
							new ZoomSpec(List.of(new ZoomRegionSpec("zoom", 0, 2200,
									ZoomDepth.D5, new ZoomFocus(0.35, 0.45), ZoomMode.MANUAL)),
									0.5, false, false, null, null, 1, 1, 1, 0, true));
					long started = System.nanoTime();
					compositor.render(surface, input);
					frameMs.add((System.nanoTime() - started) / 1_000_000.0);
				},
				(pixels, width, height, frameIndex) -> {
					ByteBuffer from = pixels.duplicate();
					from.position(0);
					from.limit(width * height * 4);
					snapshot.duplicate().put(from);
					received.countDown();
				}, Duration.ofSeconds(30));
		try {
			preview.start();
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!received.await(180, TimeUnit.SECONDS)) {
				throw new IllegalStateException("no frame read back; failure=" + preview.failure());
			}
			while (preview.framesRendered() < FRAMES) {
				Thread.sleep(20);
			}
		} finally {
			preview.close();
		}
		long inferences = engine[0] == null ? 0 : engine[0].inferenceCount();
		double meanInferenceMs = engine[0] == null || inferences == 0 ? 0
				: engine[0].totalInferenceNanos() / 1_000_000.0 / inferences;
		double[] measured;
		synchronized (frameMs) {
			measured = frameMs.stream().skip(1).mapToDouble(Double::doubleValue).toArray();
		}
		if (measured.length == 0) {
			measured = new double[] {0};
		}
		return new Timed(snapshot, measured, inferences, meanInferenceMs);
	}

	/**
	 * One measured render: the frame pixels, every frame's compositing time, and the engine's
	 * counters. The median is the steady-state cost; p90 catches a frame that ran an inference.
	 */
	private record Timed(ByteBuffer pixels, double[] frameMs, long inferences,
			double meanInferenceMs) {

		double medianMs() {
			double[] sorted = frameMs.clone();
			java.util.Arrays.sort(sorted);
			return sorted[sorted.length / 2];
		}

		double p90Ms() {
			double[] sorted = frameMs.clone();
			java.util.Arrays.sort(sorted);
			return sorted[(int) Math.min(sorted.length - 1, Math.round(sorted.length * 0.9))];
		}
	}

	private static Path modelFile() {
		String configured = System.getProperty("gravaai.superres.model");
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return SuperResolutionTestModel.resolve();
	}

	private static Timed render(RgbaFrame source, Path model) throws InterruptedException {
		AtomicReference<Compositor> compositorRef = new AtomicReference<>();
		SuperResolutionEngine[] engine = new SuperResolutionEngine[1];
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		java.util.List<Double> frameMs = java.util.Collections.synchronizedList(
				new java.util.ArrayList<>());
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					Compositor compositor = compositorRef.get();
					if (compositor == null) {
						compositor = new Compositor(WIDTH, HEIGHT);
						if (model != null) {
							engine[0] = SuperResolutionEngine.open(model);
							compositor.setSuperResolution(engine[0]);
						}
						compositorRef.set(compositor);
					}
					long started = System.nanoTime();
					compositor.render(surface, FrameInput.ofSource(frameIndex, 2.5, source,
							Background.black(), 0, LayoutSpec.FULL_FRAME, ZOOM));
					frameMs.add((System.nanoTime() - started) / 1_000_000.0);
				},
				(pixels, width, height, frameIndex) -> {
					ByteBuffer from = pixels.duplicate();
					from.position(0);
					from.limit(width * height * 4);
					snapshot.duplicate().put(from);
					received.countDown();
				}, Duration.ofSeconds(30));
		try {
			preview.start();
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!received.await(120, TimeUnit.SECONDS)) {
				throw new IllegalStateException("no frame read back; failure=" + preview.failure());
			}
			// Keep rendering the same held zoom so the engine's window cache is exercised the way a
			// real held zoom exercises it (one inference, then cached frames).
			while (preview.framesRendered() < FRAMES) {
				Thread.sleep(20);
			}
		} finally {
			preview.close();
		}
		long inferences = engine[0] == null ? 0 : engine[0].inferenceCount();
		double meanInferenceMs = engine[0] == null || inferences == 0 ? 0
				: engine[0].totalInferenceNanos() / 1_000_000.0 / inferences;
		double[] measured;
		synchronized (frameMs) {
			// Drop the first frame: it is where the compositor builds its offscreen surfaces.
			measured = frameMs.stream().skip(1).mapToDouble(Double::doubleValue).toArray();
		}
		if (measured.length == 0) {
			measured = new double[] {frameMs.isEmpty() ? 0 : frameMs.getFirst()};
		}
		return new Timed(snapshot, measured, inferences, meanInferenceMs);
	}

	/**
	 * A UI-like 1080p frame: fine grid lines, a bold title, body text and a hairline. The grid and
	 * the text are what a deep zoom destroys.
	 */
	private static RgbaFrame textLikeFrame() {
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		var g = image.createGraphics();
		g.setColor(new java.awt.Color(250, 250, 251));
		g.fillRect(0, 0, WIDTH, HEIGHT);
		g.setColor(new java.awt.Color(214, 216, 220));
		for (int x = 0; x < WIDTH; x += CELL) {
			g.drawLine(x, 0, x, HEIGHT);
		}
		for (int y = 0; y < HEIGHT; y += CELL) {
			g.drawLine(0, y, WIDTH, y);
		}
		g.setColor(new java.awt.Color(32, 33, 36));
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 34));
		g.drawString("Export settings  H.264 high  CRF 18", 80, 120);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 20));
		g.drawString("Recording 1920x1080 60 fps   Zoom D6 5.0x   Cursor smooth spring 0.6",
				80, 170);
		g.drawString("frame 00000  0.00 s  ok      frame 00173  2.88 s  ok", 80, 210);
		g.dispose();

		ByteBuffer pixels = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int rgb = image.getRGB(x, y);
				pixels.put((byte) ((rgb >> 16) & 0xFF));
				pixels.put((byte) ((rgb >> 8) & 0xFF));
				pixels.put((byte) (rgb & 0xFF));
				pixels.put((byte) 255);
			}
		}
		pixels.flip();
		return new RgbaFrame(WIDTH, HEIGHT, pixels);
	}

	/** Mean absolute horizontal luma gradient over the frame: high-frequency detail in one number. */
	private static double edgeEnergy(ByteBuffer pixels) {
		double sum = 0;
		long count = 0;
		for (int y = 0; y < HEIGHT; y++) {
			int previous = -1;
			for (int x = 0; x < WIDTH; x++) {
				int offset = (y * WIDTH + x) * 4;
				int luma = (Byte.toUnsignedInt(pixels.get(offset)) * 299
						+ Byte.toUnsignedInt(pixels.get(offset + 1)) * 587
						+ Byte.toUnsignedInt(pixels.get(offset + 2)) * 114) / 1000;
				if (previous >= 0) {
					sum += Math.abs(luma - previous);
					count++;
				}
				previous = luma;
			}
		}
		return sum / count;
	}

	private static void write(Path file, ByteBuffer pixels) throws Exception {
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		ByteBuffer view = pixels.duplicate();
		view.position(0);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int r = Byte.toUnsignedInt(view.get());
				int gg = Byte.toUnsignedInt(view.get());
				int b = Byte.toUnsignedInt(view.get());
				view.get();
				image.setRGB(x, y, (r << 16) | (gg << 8) | b);
			}
		}
		ImageIO.write(image, "png", file.toFile());
	}

	/** A 1:1 stacked crop with a red divider: before above, after below. */
	private static void writeSideBySide(Path file, ByteBuffer before, ByteBuffer after)
			throws Exception {
		int cropX = 640;
		int cropY = 380;
		int cropW = 640;
		int cropH = 300;
		BufferedImage stacked = new BufferedImage(cropW, cropH * 2 + 4, BufferedImage.TYPE_INT_RGB);
		copyCrop(before, stacked, cropX, cropY, cropW, cropH, 0);
		var g = stacked.createGraphics();
		g.setColor(java.awt.Color.RED);
		g.fillRect(0, cropH, cropW, 4);
		g.dispose();
		copyCrop(after, stacked, cropX, cropY, cropW, cropH, cropH + 4);
		ImageIO.write(stacked, "png", new File(file.toString()));
	}

	private static void copyCrop(ByteBuffer pixels, BufferedImage target, int cropX, int cropY,
			int cropW, int cropH, int destY) {
		ByteBuffer view = pixels.duplicate();
		view.position(0);
		for (int y = 0; y < cropH; y++) {
			for (int x = 0; x < cropW; x++) {
				int offset = ((cropY + y) * WIDTH + cropX + x) * 4;
				int r = Byte.toUnsignedInt(view.get(offset));
				int gg = Byte.toUnsignedInt(view.get(offset + 1));
				int b = Byte.toUnsignedInt(view.get(offset + 2));
				target.setRGB(x, destY + y, (r << 16) | (gg << 8) | b);
			}
		}
	}
}
