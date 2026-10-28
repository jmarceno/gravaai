package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
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

/**
 * Stage 3 wiring: the zoom camera transform is resolved from the zoom regions, the content-time
 * spring state and {@link ZoomTransform}, and applied around the video layer only.
 */
class CompositorZoomTest {

	private static final int WIDTH = 200;
	private static final int HEIGHT = 100;
	private static final Background BACKGROUND = Background.black();

	private static final ZoomRegionSpec MANUAL_HOLD = new ZoomRegionSpec("zoom", 0, 4000,
			ZoomDepth.D2, new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL);

	@Test
	@Timeout(60)
	@DisplayName("classic mode snaps the camera to the projected zoom transform")
	void classicModeSnapsCamera() throws Exception {
		RgbaFrame source = splitFrame(WIDTH, HEIGHT);
		ZoomSpec zoom = new ZoomSpec(List.of(MANUAL_HOLD), 0.5, false, true, null, null, 1, 1, 1, 0);
		FrameInput input = FrameInput.ofSource(0, 2.5, source, BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, zoom);
		AtomicReference<Compositor> compositorRef = new AtomicReference<>();
		ByteBuffer frame = renderFrame(input, 1, compositorRef, null);
		// Manual D2 = 1.5x, focus centred: scale 1.5, x = 100 - 100*1.5 = -50, y = 50 - 50*1.5 = -25.
		ZoomTransform.AppliedTransform transform = compositorRef.get().lastAppliedTransform();
		assertEquals(1.5, transform.scale(), 1e-6);
		assertEquals(-50.0, transform.x(), 1e-6);
		assertEquals(-25.0, transform.y(), 1e-6);
		// Screen (5,50) samples source ((5+50)/1.5, (50+25)/1.5) = (36.7, 50) -> left half (red).
		assertPixel(frame, 5, 50, 255, 0, 0, "zoomed-in left edge");
		assertPixel(frame, 5, 5, 255, 0, 0, "zoomed-in top-left");
		// Screen (195,50) samples source (163.3, 50) -> right half (blue).
		assertPixel(frame, WIDTH - 5, 50, 0, 0, 255, "zoomed-in right edge");
		assertPixel(frame, WIDTH - 5, HEIGHT - 5, 0, 0, 255, "zoomed-in bottom-right");
	}

	@Test
	@Timeout(60)
	@DisplayName("spring mode eases the camera toward the projected transform over frames")
	void springModeEasesCamera() throws Exception {
		RgbaFrame source = splitFrame(WIDTH, HEIGHT);
		ZoomSpec zoom = new ZoomSpec(List.of(MANUAL_HOLD), 1.0, false, false, null, null, 1, 1, 1, 0);
		double[][] scaleSamples = new double[240][1];
		AtomicReference<Compositor> compositorRef = new AtomicReference<>();
		renderSpringFrames(source, zoom, 240, compositorRef, scaleSamples);
		ZoomTransform.AppliedTransform transform = compositorRef.get().lastAppliedTransform();
		assertEquals(1.5, transform.scale(), 0.01);
		assertEquals(-50.0, transform.x(), 1.0);
		assertEquals(-25.0, transform.y(), 1.0);
		// The spring starts at the identity and eases in (no overshoot for this damping).
		assertTrue(scaleSamples[0][0] < scaleSamples[60][0],
				"scale did not ease in: " + scaleSamples[0][0] + " -> " + scaleSamples[60][0]);
		assertTrue(scaleSamples[60][0] < scaleSamples[239][0]);
		assertTrue(scaleSamples[239][0] <= 1.5 + 1e-3, "camera overshot the target");
	}

	private static void renderSpringFrames(RgbaFrame source, ZoomSpec zoom, int frames,
			AtomicReference<Compositor> compositorRef, double[][] scaleSamples)
			throws InterruptedException {
		CountDownLatch done = new CountDownLatch(1);
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					Compositor compositor = compositorRef.get();
					if (compositor == null) {
						compositor = new Compositor(WIDTH, HEIGHT);
						compositorRef.set(compositor);
					}
					double contentTime = frameIndex / 60.0;
					compositor.render(surface, FrameInput.ofSource(frameIndex, contentTime, source,
							BACKGROUND, 0, LayoutSpec.FULL_FRAME, zoom));
				},
				(pixels, width, height, frameIndex) -> {
					if (frameIndex < scaleSamples.length) {
						Compositor compositor = compositorRef.get();
						if (compositor != null) {
							scaleSamples[(int) frameIndex][0] =
									compositor.lastAppliedTransform().scale();
						}
					}
					if (frameIndex >= frames - 1) {
						done.countDown();
					}
				}, Duration.ofSeconds(10));
		try {
			preview.start();
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!done.await(30, TimeUnit.SECONDS)) {
				fail("spring frames did not complete; failure=" + preview.failure());
			}
		} finally {
			preview.close();
		}
	}

	private static ByteBuffer renderFrame(FrameInput input, int frames,
			AtomicReference<Compositor> compositorRef, CountDownLatch unused)
			throws InterruptedException {
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(frames);
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					Compositor compositor = compositorRef.get();
					if (compositor == null) {
						compositor = new Compositor(WIDTH, HEIGHT);
						compositorRef.set(compositor);
					}
					compositor.render(surface, input);
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
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!received.await(10, TimeUnit.SECONDS)) {
				fail("no frame read back; failure=" + preview.failure());
			}
		} finally {
			preview.close();
		}
		return snapshot;
	}

	private static RgbaFrame splitFrame(int width, int height) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				boolean leftHalf = x < width / 2;
				buffer.put((byte) (leftHalf ? 255 : 0))
						.put((byte) 0)
						.put((byte) (leftHalf ? 0 : 255))
						.put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static void assertPixel(ByteBuffer frame, int x, int y, int red, int green, int blue,
			String message) {
		int offset = (y * WIDTH + x) * 4;
		assertEquals(red, Byte.toUnsignedInt(frame.get(offset)), message + " (red)");
		assertEquals(green, Byte.toUnsignedInt(frame.get(offset + 1)), message + " (green)");
		assertEquals(blue, Byte.toUnsignedInt(frame.get(offset + 2)), message + " (blue)");
		assertEquals(255, Byte.toUnsignedInt(frame.get(offset + 3)), message + " (alpha)");
	}
}
