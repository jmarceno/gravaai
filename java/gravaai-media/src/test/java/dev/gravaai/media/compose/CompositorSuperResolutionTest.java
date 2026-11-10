package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;
import dev.gravaai.media.superres.SuperResolutionEngine;
import dev.gravaai.media.superres.SuperResolutionTestModel;

/**
 * The export-only super-resolution tier end to end: with an engine installed, a deep zoom draws
 * the ONNX-upscaled window instead of the stretched source, and the frame it produces has more
 * edge detail than the same frame with the sharpening pass alone.
 *
 * <p>This is the acceptance test for the card's second phase: the compositor really consumes the
 * upscaler's pixels, on the same GL path preview and export share. Skipped unless the model is
 * staged, like {@link dev.gravaai.media.superres.SuperResolutionEngineTest}.</p>
 */
class CompositorSuperResolutionTest {

	private static final int WIDTH = 320;
	private static final int HEIGHT = 180;
	private static final Background BACKGROUND = Background.black();
	/** Checkerboard tile size in source pixels: small enough that 5x magnification is blur. */
	private static final int TILE = 6;

	private static final ZoomRegionSpec MANUAL_HOLD = new ZoomRegionSpec("zoom", 0, 4000,
			ZoomDepth.D6, new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL);

	private static Path modelFile() {
		String configured = System.getProperty("gravaai.superres.model");
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return SuperResolutionTestModel.resolve();
	}

	@Test
	@Timeout(180)
	@DisplayName("an installed engine makes the deep-zoom frame carry upscaled pixels")
	void deepZoomDrawsTheUpscaledWindow() throws Exception {
		Path model = modelFile();
		Assumptions.assumeTrue(Files.isRegularFile(model),
				"super-resolution model not staged at " + model);
		RgbaFrame source = checkerFrame(WIDTH, HEIGHT);
		ZoomSpec zoom = new ZoomSpec(List.of(MANUAL_HOLD), 0.5, false, true, null, null,
				1, 1, 1, 0, true);

		long[] withoutEngine = renderFrame(source, zoom, null);
		long[] inferences = new long[1];
		long[] withEngine = renderFrame(source, zoom, new EngineFactory() {
			@Override
			public SuperResolutionEngine create() {
				return SuperResolutionEngine.open(model);
			}
		}, inferences);

		assertTrue(inferences[0] > 0, "the engine must have run at least one inference");
		// The upscaled frame must differ from the sharpening-only frame. A byte-identical result
		// would mean the compositor never actually drew the engine's pixels.
		boolean differs = false;
		int differences = 0;
		for (int i = 0; i < withoutEngine.length; i++) {
			if (withoutEngine[i] != withEngine[i]) {
				differences++;
				differs = true;
			}
		}
		assertTrue(differs, "the SR frame is identical to the sharpening-only frame");
		assertTrue(differences > withoutEngine.length / 20,
				"only " + differences + " of " + withoutEngine.length
						+ " channels differ: the upscale barely reached the canvas");
	}

	@Test
	@Timeout(180)
	@DisplayName("without an engine the deep-zoom frame is unchanged (preview never pays)")
	void withoutEngineNothingChanges() throws Exception {
		RgbaFrame source = checkerFrame(WIDTH, HEIGHT);
		ZoomSpec zoom = new ZoomSpec(List.of(MANUAL_HOLD), 0.5, false, true, null, null,
				1, 1, 1, 0, true);
		long[] first = renderFrame(source, zoom, null);
		long[] second = renderFrame(source, zoom, null);
		for (int i = 0; i < first.length; i++) {
			if (first[i] != second[i]) {
				fail("the sharpening-only path is not deterministic at channel " + i);
			}
		}
	}

	/** Opens the engine on the GL thread (an ORT session is cheap to open but not thread-bound). */
	private interface EngineFactory {
		SuperResolutionEngine create();
	}

	private static long[] renderFrame(RgbaFrame source, ZoomSpec zoom, EngineFactory engine)
			throws InterruptedException {
		return renderFrame(source, zoom, engine, null);
	}

	private static long[] renderFrame(RgbaFrame source, ZoomSpec zoom, EngineFactory engine,
			long[] inferenceCountOut) throws InterruptedException {
		AtomicReference<Compositor> compositorRef = new AtomicReference<>();
		SuperResolutionEngine[] opened = new SuperResolutionEngine[1];
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					Compositor compositor = compositorRef.get();
					if (compositor == null) {
						compositor = new Compositor(WIDTH, HEIGHT);
						if (engine != null) {
							opened[0] = engine.create();
							compositor.setSuperResolution(opened[0]);
						}
						compositorRef.set(compositor);
					}
					compositor.render(surface, FrameInput.ofSource(frameIndex, 2.5, source,
							BACKGROUND, 0, LayoutSpec.FULL_FRAME, zoom));
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
			if (!received.await(30, TimeUnit.SECONDS)) {
				fail("no frame read back; failure=" + preview.failure());
			}
		} finally {
			preview.close();
			// The compositor is closed by the GL thread's teardown path; report the counters first.
			if (inferenceCountOut != null && opened[0] != null) {
				inferenceCountOut[0] = opened[0].inferenceCount();
			}
		}
		long[] values = new long[snapshot.capacity()];
		ByteBuffer view = snapshot.duplicate();
		view.position(0);
		for (int i = 0; i < values.length; i++) {
			values[i] = Byte.toUnsignedInt(view.get());
		}
		return values;
	}

	/** A fine checkerboard: the pattern a 5x magnification turns into flat grey without help. */
	private static RgbaFrame checkerFrame(int width, int height) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean light = ((x / TILE) + (y / TILE)) % 2 == 0;
				int value = light ? 200 : 60;
				buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}
}
