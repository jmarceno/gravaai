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

/** Stage 11 real-GL coverage: fade-through-black ramp, peak, hold and clear. */
class CompositorFadeTest {

	private static final int WIDTH = 200;
	private static final int HEIGHT = 100;
	private static final Background BACKGROUND = Background.black();
	private static final RgbaFrame SOURCE = solid(100);

	private static RgbaFrame solid(int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(WIDTH, HEIGHT, buffer);
	}

	private static ByteBuffer render(double timeSeconds, FadeSpec fade) throws Exception {
		FrameInput input = FrameInput.ofSource(0, timeSeconds, SOURCE, BACKGROUND)
				.withFade(fade);
		ByteBuffer snapshot = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		CountDownLatch received = new CountDownLatch(1);
		GlPreviewThread preview = new GlPreviewThread(WIDTH, HEIGHT, PboFormat.RGBA,
				(surface, frameIndex, time) -> {
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

	private static int centrePixel(ByteBuffer frame) {
		int offset = ((HEIGHT / 2) * WIDTH + WIDTH / 2) * 4;
		return Byte.toUnsignedInt(frame.get(offset));
	}

	@Test
	@Timeout(60)
	@DisplayName("paints the frame fully black at the fade peak")
	void peakIsBlack() throws Exception {
		FadeSpec fade = new FadeSpec(List.of(new FadeSpec.Window(0, 100, null, 200)));
		ByteBuffer frame = render(0.1, fade);
		assertEquals(0, centrePixel(frame), "peak frame is not black");
	}

	@Test
	@Timeout(60)
	@DisplayName("leaves the frame untouched outside the fade window")
	void outsideIsClear() throws Exception {
		FadeSpec fade = new FadeSpec(List.of(new FadeSpec.Window(0, 100, null, 200)));
		ByteBuffer frame = render(0.35, fade);
		assertEquals(100, centrePixel(frame), "frame changed outside the fade window");
	}

	@Test
	@Timeout(60)
	@DisplayName("ramps smoothly to black on the way in")
	void rampsIn() throws Exception {
		FadeSpec fade = new FadeSpec(List.of(new FadeSpec.Window(0, 100, null, 200)));
		ByteBuffer frame = render(0.05, fade);
		assertTrue(centrePixel(frame) > 5 && centrePixel(frame) < 95,
				"mid-fade pixel should be grey, was " + centrePixel(frame));
	}

	@Test
	@Timeout(60)
	@DisplayName("stays black across a timeline gap and fades back in")
	void holdsAcrossGap() throws Exception {
		FadeSpec fade = new FadeSpec(
				List.of(new FadeSpec.Window(0, 100, 200.0, 300)));
		assertEquals(0, centrePixel(render(0.15, fade)), "gap hold is not black");
		int mid = centrePixel(render(0.25, fade));
		assertTrue(mid > 5 && mid < 95, "fade-out pixel should be grey, was " + mid);
	}
}
