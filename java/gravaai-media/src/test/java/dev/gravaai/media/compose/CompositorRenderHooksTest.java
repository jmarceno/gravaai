package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/**
 * Media-side coverage of the extension render-hook seam (plan §5.18, {@code EXTENSIONS.md}
 * "Render Pipeline"): the {@link Compositor} dispatches the phases at the documented stages, the
 * inside-transform phases follow the camera transform, a throwing hook is skipped, and no hooks
 * leaves the frame byte-identical.
 */
class CompositorRenderHooksTest {

	private static final int WIDTH = 200;
	private static final int HEIGHT = 100;
	private static final Background BACKGROUND = Background.black();
	private static final RgbaFrame SOURCE = solid(WIDTH, HEIGHT, 100);
	private static final ZoomRegionSpec ZOOM_HOLD = new ZoomRegionSpec("zoom", 0, 4000,
			ZoomDepth.D2, new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL);
	/** Classic mode D2 = 1.5x centred, so scene (40,20) maps to screen (10,5). */
	private static final ZoomSpec ZOOM = new ZoomSpec(List.of(ZOOM_HOLD), 0.5, false, true,
			null, null, 1, 1, 1, 0);

	@Test
	@Timeout(60)
	@DisplayName("dispatch order matches the EXTENSIONS.md pipeline and background is never dispatched")
	void dispatchOrder() throws Exception {
		List<FrameOverlayHooks.Phase> phases = new ArrayList<>();
		FrameOverlayHooks recording = (phase, canvas, frame) -> phases.add(phase);
		FrameInput input = FrameInput.ofSource(0, 1.0, SOURCE, BACKGROUND);
		render(input, recording);
		// The GL loop may draw more than one frame before the readback latch releases; every
		// frame must dispatch the same pipeline order exactly once.
		List<FrameOverlayHooks.Phase> expected = List.of(FrameOverlayHooks.Phase.POST_VIDEO,
				FrameOverlayHooks.Phase.POST_ZOOM,
				FrameOverlayHooks.Phase.POST_CURSOR,
				FrameOverlayHooks.Phase.POST_WEBCAM,
				FrameOverlayHooks.Phase.POST_ANNOTATIONS,
				FrameOverlayHooks.Phase.FINAL);
		assertFalse(phases.isEmpty(), "no frame was rendered");
		assertEquals(0, phases.size() % expected.size(),
				"every frame dispatches each phase once: " + phases);
		for (int index = 0; index < phases.size(); index += expected.size()) {
			assertEquals(expected, phases.subList(index, index + expected.size()),
					"phases must dispatch in pipeline order");
		}
		assertFalse(phases.contains(FrameOverlayHooks.Phase.BACKGROUND),
				"background is reserved and must never be dispatched");
	}

	@Test
	@Timeout(60)
	@DisplayName("installing NONE hooks leaves the composited frame byte-identical")
	void noHooksIsByteIdentical() throws Exception {
		FrameInput input = FrameInput.ofSource(0, 1.0, SOURCE, BACKGROUND);
		byte[] withoutSeam = bytes(render(input, null));
		byte[] withNone = bytes(render(input, FrameOverlayHooks.NONE));
		assertArrayEquals(withoutSeam, withNone,
				"a NONE hook installation must not change a single pixel");
	}

	@Test
	@Timeout(60)
	@DisplayName("hook drawing is composited into the rendered frame")
	void hookDrawingIsVisible() throws Exception {
		FrameOverlayHooks hooks = (phase, canvas, frame) -> {
			if (phase == FrameOverlayHooks.Phase.FINAL) {
				fill(canvas, 0xFF00FF00, 30, 20, 20, 10);
			}
		};
		FrameInput input = FrameInput.ofSource(0, 1.0, SOURCE, BACKGROUND);
		ByteBuffer baseline = render(input, null);
		ByteBuffer hooked = render(input, hooks);
		assertPixel(baseline, 40, 25, 100, 100, 100, "baseline frame");
		assertPixel(hooked, 40, 25, 0, 255, 0, "hook overlay pixel");
		assertPixel(hooked, 5, 5, 100, 100, 100, "pixel outside the overlay");
	}

	@Test
	@Timeout(60)
	@DisplayName("post-video/post-zoom/post-cursor draw inside the scene transform, later phases outside")
	void insideTransformPhasesFollowTheCamera() throws Exception {
		List<FrameOverlayHooks.Phase> phases = new ArrayList<>();
		FrameOverlayHooks hooks = (phase, canvas, frame) -> {
			if (phase == FrameOverlayHooks.Phase.POST_VIDEO) {
				// Scene space: with the 1.5x centred camera this lands at screen (10,5).
				fill(canvas, 0xFFFF00FF, 40, 20, 8, 8);
			}
			if (phase == FrameOverlayHooks.Phase.FINAL) {
				// No transform: this lands at screen (40,20).
				fill(canvas, 0xFF00FFFF, 40, 20, 8, 8);
			}
			phases.add(phase);
		};
		FrameInput input = FrameInput.ofSource(0, 2.5, SOURCE, BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZOOM);
		ByteBuffer frame = render(input, hooks);
		assertPixel(frame, 12, 7, 255, 0, 255, "post-video pixel in scene space");
		assertPixel(frame, 42, 22, 0, 255, 255, "final pixel in screen space");
		assertPixel(frame, 42, 7, 100, 100, 100, "post-video did not follow the camera");
		assertTrue(phases.contains(FrameOverlayHooks.Phase.POST_ZOOM));
	}

	@Test
	@Timeout(60)
	@DisplayName("a throwing hook is skipped and the frame still renders")
	void throwingHookIsSkipped() throws Exception {
		FrameOverlayHooks broken = (phase, canvas, frame) -> {
			throw new IllegalStateException("broken extension");
		};
		FrameInput input = FrameInput.ofSource(0, 1.0, SOURCE, BACKGROUND);
		byte[] baseline = bytes(render(input, null));
		byte[] withBroken = bytes(render(input, broken));
		assertArrayEquals(baseline, withBroken,
				"a throwing hook must not change the frame or break rendering");
	}

	private static void fill(Canvas canvas, int argb, float x, float y, float width, float height) {
		try (Paint paint = new Paint()) {
			paint.setColor(argb);
			canvas.drawRect(Rect.makeXYWH(x, y, width, height), paint);
		}
	}

	private static byte[] bytes(ByteBuffer frame) {
		ByteBuffer copy = frame.duplicate();
		copy.position(0);
		byte[] bytes = new byte[WIDTH * HEIGHT * 4];
		copy.get(bytes);
		return bytes;
	}

	private static void assertPixel(ByteBuffer frame, int x, int y, int red, int green, int blue,
			String message) {
		int offset = (y * WIDTH + x) * 4;
		assertEquals(red, Byte.toUnsignedInt(frame.get(offset)), message + " (red)");
		assertEquals(green, Byte.toUnsignedInt(frame.get(offset + 1)), message + " (green)");
		assertEquals(blue, Byte.toUnsignedInt(frame.get(offset + 2)), message + " (blue)");
		assertEquals(255, Byte.toUnsignedInt(frame.get(offset + 3)), message + " (alpha)");
	}

	/** Renders one frame through a fresh compositor on the offscreen GL thread. */
	private static ByteBuffer render(FrameInput input, FrameOverlayHooks hooks) throws Exception {
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, timeSeconds) -> {
					try (Compositor compositor = new Compositor(WIDTH, HEIGHT)) {
						compositor.setOverlayHooks(hooks);
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
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!received.await(15, TimeUnit.SECONDS)) {
				fail("no frame read back; failure=" + preview.failure());
			}
		} finally {
			preview.close();
		}
		return snapshot;
	}

	private static RgbaFrame solid(int width, int height, int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int pixel = 0; pixel < width * height; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}
}
