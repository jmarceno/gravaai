package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/** Stage 8.5 real-GL coverage: the glass loupe magnifies the frame around the cursor. */
class CompositorLoupeTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 200;
	private static final Background BACKGROUND = new Background.Colour(0xFF000000);
	/** The cursor shown by the loupe test (CursorSpec.DEFAULT hides the cursor). */
	private static final CursorSpec CURSOR = new CursorSpec(true, CursorStyle.TAHOE, 28, 0.18,
			1, 1, 1, 0, CursorClickEffect.NONE, 0xFF2563EB, 1, 1, 600, 1, 350, 0);

	private static AnnotationSpec loupe(long startMs, long endMs, double magnification) {
		return new AnnotationSpec("loupe", startMs, endMs, AnnotationSpec.Type.LOUPE, "", null,
				new AnnotationSpec.Position(50, 50), new AnnotationSpec.Size(50, 50),
				AnnotationSpec.defaultTextStyle(), 1, null, null, null, magnification);
	}

	private static FrameInput input(double timeSeconds, RgbaFrame source,
			List<AnnotationSpec> annotations) {
		return new FrameInput(0, timeSeconds, source, null,
				List.of(new CursorSample(0.0, 0.5, 0.5)), BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CURSOR,
				WebcamSpec.DISABLED, annotations);
	}

	/**
	 * Vertical black/grey 4px stripes: the magnified lens shows a stretched pattern that cannot
	 * match the plain render.
	 */
	private static RgbaFrame stripes() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int value = (x / 4) % 2 == 0 ? 20 : 220;
				buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(WIDTH, HEIGHT, buffer);
	}

	private static ByteBuffer render(FrameInput input) throws Exception {
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
			if (!preview.awaitReady()) {
				Assumptions.abort("GL context unavailable: " + preview.failure());
			}
			if (!received.await(15, TimeUnit.SECONDS)) {
				Assumptions.abort("no frame read back: " + preview.failure());
			}
		} finally {
			preview.close();
		}
		return snapshot;
	}

	/** Sum of the absolute red-channel deltas between two frames. */
	private static int difference(ByteBuffer left, ByteBuffer right) {
		int total = 0;
		for (int index = 0; index < WIDTH * HEIGHT; index++) {
			total += Math.abs(Byte.toUnsignedInt(left.get(index * 4))
					- Byte.toUnsignedInt(right.get(index * 4)));
		}
		return total;
	}

	@Test
	@Timeout(60)
	@DisplayName("magnifies the rendered scene inside the lens while the loupe is active")
	void magnifiesAroundTheCursor() throws Exception {
		RgbaFrame detailed = stripes();
		ByteBuffer plain = render(input(0.0, detailed, List.of()));
		ByteBuffer loupes = render(input(0.0, detailed, List.of(loupe(0, 1000, 2.0))));

		int difference = difference(plain, loupes);
		assertTrue(difference > 10_000,
				"loupe did not change the frame (difference " + difference + ")");
	}

	@Test
	@Timeout(60)
	@DisplayName("draws nothing outside the loupe time window")
	void hidesInactiveLoupe() throws Exception {
		RgbaFrame detailed = stripes();
		ByteBuffer plain = render(input(2.0, detailed, List.of()));
		ByteBuffer inactive = render(input(2.0, detailed, List.of(loupe(0, 1000, 2.0))));

		assertEquals(0, difference(plain, inactive), "inactive loupe drawn");
	}
}
