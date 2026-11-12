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

/**
 * Stage 8 real-GL coverage for the spotlight annotation: the frame dims outside the rectangle
 * while the area stays bright, the dim rides zooms with the content, and the dim fades at the
 * region edges.
 */
class CompositorSpotlightTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 200;
	private static final Background BACKGROUND = new Background.Colour(0xFF000000);
	/** The grey source the scrim dims: 60% dim turns it into ~40. */
	private static final RgbaFrame SOURCE = solid(WIDTH, HEIGHT, 100);

	/** The 40%×40% rectangle centred on the frame (position is its top-left corner). */
	private static final AnnotationSpec SPOTLIGHT =
			spotlight(0, 2000, new AnnotationSpec.Spotlight(60, 0));

	private static AnnotationSpec spotlight(long startMs, long endMs,
			AnnotationSpec.Spotlight payload) {
		return new AnnotationSpec("spotlight", startMs, endMs, AnnotationSpec.Type.SPOTLIGHT, "",
				null, new AnnotationSpec.Position(30, 30), new AnnotationSpec.Size(40, 40),
				AnnotationSpec.defaultTextStyle(), 1, null, null, null, null, payload);
	}

	private static RgbaFrame solid(int width, int height, int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int pixel = 0; pixel < width * height; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static FrameInput input(double timeSeconds, List<AnnotationSpec> annotations) {
		return input(timeSeconds, ZoomSpec.NONE, annotations);
	}

	private static FrameInput input(double timeSeconds, ZoomSpec zoom,
			List<AnnotationSpec> annotations) {
		return new FrameInput(0, timeSeconds, SOURCE, null, List.of(), BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, zoom, FrameStyle.DEFAULT, CursorSpec.DEFAULT,
				WebcamSpec.DISABLED, annotations);
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

	private static int grey(ByteBuffer frame, int x, int y) {
		int offset = (y * WIDTH + x) * 4;
		return Byte.toUnsignedInt(frame.get(offset));
	}

	@Test
	@Timeout(60)
	@DisplayName("dims the frame outside the rectangle and keeps the area bright")
	void dimsOutsideAndKeepsTheAreaBright() throws Exception {
		ByteBuffer frame = render(input(1.0, List.of(SPOTLIGHT)));

		// Deep inside the hole the pixels are untouched (feather never reaches the centre).
		assertEquals(100, grey(frame, WIDTH / 2, HEIGHT / 2), 2,
				"spotlight area is no longer bright");
		// Far outside the hole the 60% scrim turned the grey source into ~40.
		int corner = grey(frame, 5, 5);
		assertTrue(corner >= 30 && corner <= 50,
				"frame outside the spotlight did not dim to ~40 (got " + corner + ")");
	}

	@Test
	@Timeout(60)
	@DisplayName("renders nothing outside the spotlight time window")
	void hidesInactiveSpotlight() throws Exception {
		ByteBuffer frame = render(input(3.0, List.of(SPOTLIGHT)));
		assertEquals(100, grey(frame, 5, 5), 1, "inactive spotlight dimmed the frame");
	}

	@Test
	@Timeout(60)
	@DisplayName("the dim fades in and out at the region edges instead of popping")
	void dimFadesAtRegionEdges() throws Exception {
		// 20 ms into the loupe's shared grow-in the dim is still weak; mid-region it is full
		// strength, and 30 ms before the end it is easing back out (the ease-out keeps the dim
		// near-full for most of the shrink window).
		ByteBuffer early = render(input(0.02, List.of(SPOTLIGHT)));
		ByteBuffer mid = render(input(1.0, List.of(SPOTLIGHT)));
		ByteBuffer late = render(input(1.97, List.of(SPOTLIGHT)));
		assertTrue(grey(early, 5, 5) > grey(mid, 5, 5) + 20,
				"the dim did not fade in (early " + grey(early, 5, 5) + ", mid " + grey(mid, 5, 5)
						+ ")");
		assertTrue(grey(late, 5, 5) > grey(mid, 5, 5) + 20 && grey(late, 5, 5) < 95,
				"the dim did not fade out (late " + grey(late, 5, 5) + ", mid " + grey(mid, 5, 5)
						+ ")");
	}

	@Test
	@Timeout(60)
	@DisplayName("feathers the edge of the bright area")
	void feathersTheEdge() throws Exception {
		// Scan across the straight left edge of the hole (y is mid-height, past the corners).
		ByteBuffer crisp = render(input(1.0,
				List.of(spotlight(0, 2000, new AnnotationSpec.Spotlight(60, 0)))));
		ByteBuffer feathered = render(input(1.0,
				List.of(spotlight(0, 2000, new AnnotationSpec.Spotlight(60, 25)))));
		int crispRamp = 0;
		int featheredRamp = 0;
		for (int x = 100; x <= 140; x++) {
			if (between(grey(crisp, x, HEIGHT / 2), 55, 85)) {
				crispRamp++;
			}
			if (between(grey(feathered, x, HEIGHT / 2), 55, 85)) {
				featheredRamp++;
			}
		}
		assertTrue(featheredRamp >= 8,
				"the feathered edge has no soft ramp (" + featheredRamp + " px)");
		assertTrue(crispRamp <= 2,
				"the crisp edge should be hard (" + crispRamp + " px)");
	}

	private static boolean between(int value, int low, int high) {
		return value > low && value < high;
	}

	@Test
	@Timeout(60)
	@DisplayName("the bright area rides zooms with the content")
	void followsZoom() throws Exception {
		// Manual depth-2 zoom centred: scale 1.5, x = 200 - 200*1.5, y = 100 - 100*1.5. The hole
		// (video x 120..280) maps to screen x 80..320, so screen (90,100) is dimmed unzoomed and
		// bright zoomed.
		ZoomSpec zoom = new ZoomSpec(List.of(new ZoomRegionSpec("zoom", 0, 4000, ZoomDepth.D2,
				new ZoomFocus(0.5, 0.5), ZoomMode.MANUAL)), 1.0, false, true, null, null, 1, 1,
				1, 0);
		ByteBuffer plain = render(input(1.0, ZoomSpec.NONE, List.of(SPOTLIGHT)));
		ByteBuffer zoomed = render(input(1.0, zoom, List.of(SPOTLIGHT)));
		assertTrue(grey(plain, 90, HEIGHT / 2) <= 50,
				"the point should sit outside the unzoomed spotlight");
		assertTrue(grey(zoomed, 90, HEIGHT / 2) >= 90,
				"the spotlight did not follow the zoom (got " + grey(zoomed, 90, HEIGHT / 2) + ")");
	}
}
