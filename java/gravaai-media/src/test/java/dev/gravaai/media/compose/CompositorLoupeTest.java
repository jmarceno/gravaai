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
				AnnotationSpec.defaultTextStyle(), 1, null, null, null, magnification,
				AnnotationSpec.Spotlight.DEFAULT);
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
		// Mid-region, past the grow-in, so the lens is fully present.
		ByteBuffer plain = render(input(0.5, detailed, List.of()));
		ByteBuffer loupes = render(input(0.5, detailed, List.of(loupe(0, 1000, 2.0))));

		int difference = difference(plain, loupes);
		assertTrue(difference > 10_000,
				"loupe did not change the frame (difference " + difference + ")");
	}

	@Test
	@Timeout(60)
	@DisplayName("follows the recorded pointer even when the cursor is hidden")
	void magnifiesWithHiddenCursor() throws Exception {
		RgbaFrame detailed = stripes();
		FrameInput hiddenPlain = new FrameInput(0, 0.5, detailed, null,
				List.of(new CursorSample(0.0, 0.5, 0.5)), BACKGROUND, 0, LayoutSpec.FULL_FRAME,
				ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT, WebcamSpec.DISABLED,
				List.of());
		FrameInput hiddenLoupe = new FrameInput(0, 0.5, detailed, null,
				List.of(new CursorSample(0.0, 0.5, 0.5)), BACKGROUND, 0, LayoutSpec.FULL_FRAME,
				ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT, WebcamSpec.DISABLED,
				List.of(loupe(0, 1000, 2.0)));

		int difference = difference(render(hiddenPlain), render(hiddenLoupe));
		assertTrue(difference > 10_000,
				"loupe vanished with the cursor hidden (difference " + difference + ")");
	}

	@Test
	@DisplayName("the lens sits beside the cursor tip with its rim on the tip, inside the frame")
	void lensRimTouchesTheTip() {
		double distance = 440;
		double inset = 435;
		double[][] tips = {{960, 540}, {1500, 900}, {200, 100}, {1700, 300}, {870, 540}};
		for (double[] tip : tips) {
			double[] centre = LoupeRenderer.besideTip(tip[0], tip[1], distance, inset, 1920,
					1080);
			assertEquals(distance, Math.hypot(centre[0] - tip[0], centre[1] - tip[1]), 1e-6,
					"rim misses the tip at " + tip[0] + "," + tip[1]);
			assertTrue(centre[0] >= inset && centre[0] <= 1920 - inset,
					"lens leaves the frame horizontally at " + tip[0]);
			assertTrue(centre[1] >= inset && centre[1] <= 1080 - inset,
					"lens leaves the frame vertically at " + tip[1]);
		}
		// Room on the left: the lens is up-left of the tip, never covering the arrow body.
		double[] middle = LoupeRenderer.besideTip(1500, 900, distance, inset, 1920, 1080);
		assertTrue(middle[0] < 1500 && middle[1] < 900);
	}

	@Test
	@Timeout(60)
	@DisplayName("near the frame edge the lens slides inward instead of being cut off")
	void lensStaysInsideTheFrame() throws Exception {
		// Cursor 8 px from the right edge; the 100 px lens (50% of the 200 px short side) would
		// span x 342..400 if centred on it, but slides in to centre near x 347, reaching x 298.
		RgbaFrame detailed = stripes();
		List<CursorSample> edge = List.of(new CursorSample(0.0, 0.98, 0.5));
		FrameInput plain = new FrameInput(0, 0.5, detailed, null, edge, BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CURSOR,
				WebcamSpec.DISABLED, List.of());
		FrameInput lens = new FrameInput(0, 0.5, detailed, null, edge, BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CURSOR,
				WebcamSpec.DISABLED, List.of(loupe(0, 1000, 2.0)));
		ByteBuffer before = render(plain);
		ByteBuffer after = render(lens);

		int changed = 0;
		for (int x = 300; x < 340; x++) {
			int index = (100 * WIDTH + x) * 4;
			if (before.get(index) != after.get(index)) {
				changed++;
			}
		}
		assertTrue(changed > 10, "lens did not slide inside the frame (" + changed + " px)");
	}

	@Test
	@DisplayName("the lens grows in and shrinks out at the region edges instead of popping")
	void presenceEasesAtRegionEdges() {
		assertEquals(0, LoupeRenderer.presence(0, 0, 1000), 1e-9);
		assertTrue(LoupeRenderer.presence(50, 0, 1000) > 0);
		assertTrue(LoupeRenderer.presence(50, 0, 1000) < 1);
		assertEquals(1, LoupeRenderer.presence(500, 0, 1000), 1e-9);
		assertTrue(LoupeRenderer.presence(950, 0, 1000) < 1);
		assertEquals(0, LoupeRenderer.presence(1000, 0, 1000), 1e-9);
		assertEquals(0, LoupeRenderer.presence(1500, 0, 1000), 1e-9);
		// A region shorter than the two transitions still peaks at full size in its middle.
		assertEquals(1, LoupeRenderer.presence(100, 0, 200), 1e-9);
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
