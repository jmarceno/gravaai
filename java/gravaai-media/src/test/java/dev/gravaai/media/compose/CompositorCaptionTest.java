package dev.gravaai.media.compose;

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

/** Stage 9 real-GL coverage: caption box/text visibility, timing, fade and rise animation. */
class CompositorCaptionTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 200;
	private static final Background BACKGROUND = Background.black();
	private static final RgbaFrame SOURCE = solid(WIDTH, HEIGHT, 100);

	private static RgbaFrame solid(int width, int height, int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int pixel = 0; pixel < width * height; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static CaptionSpec spec(boolean enabled, String animation) {
		List<CaptionSpec.Cue> cues = List.of(new CaptionSpec.Cue("cue-1", 0, 2_000,
				"Hello caption"));
		return new CaptionSpec(enabled, "sans-serif", 30, 3, 62, 1, animation, 17.5,
				"#FFFFFF", "#A3A3A3", 0.9, cues);
	}

	private static FrameInput input(double timeSeconds, CaptionSpec captions) {
		return new FrameInput(0, timeSeconds, SOURCE, null, List.of(), BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT,
				WebcamSpec.DISABLED, List.of(), captions);
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

	private static int darkPixels(ByteBuffer frame) {
		int count = 0;
		for (int y = HEIGHT - 80; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int offset = (y * WIDTH + x) * 4;
				int r = Byte.toUnsignedInt(frame.get(offset));
				int g = Byte.toUnsignedInt(frame.get(offset + 1));
				int b = Byte.toUnsignedInt(frame.get(offset + 2));
				if (r + g + b < 150) {
					count++;
				}
			}
		}
		return count;
	}

	private static double brightness(ByteBuffer frame) {
		long total = 0;
		int samples = 0;
		for (int y = HEIGHT - 80; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int offset = (y * WIDTH + x) * 4;
				total += Byte.toUnsignedInt(frame.get(offset))
						+ Byte.toUnsignedInt(frame.get(offset + 1))
						+ Byte.toUnsignedInt(frame.get(offset + 2));
				samples += 3;
			}
		}
		return total / (double) samples;
	}

	private static double darkCentroidY(ByteBuffer frame) {
		double sum = 0;
		int count = 0;
		for (int y = HEIGHT - 100; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int offset = (y * WIDTH + x) * 4;
				int r = Byte.toUnsignedInt(frame.get(offset));
				if (r < 90) {
					sum += y;
					count++;
				}
			}
		}
		return count == 0 ? -1 : sum / count;
	}

	@Test
	@Timeout(60)
	@DisplayName("draws the caption box and text when a cue is active")
	void drawsActiveCaption() throws Exception {
		ByteBuffer frame = render(input(0.5, spec(true, "none")));
		assertTrue(darkPixels(frame) > 100, "caption box missing");
	}

	@Test
	@Timeout(60)
	@DisplayName("hides captions when disabled or outside the cue")
	void hidesInactiveCaptions() throws Exception {
		ByteBuffer disabled = render(input(0.5, spec(false, "none")));
		assertTrue(darkPixels(disabled) == 0, "disabled captions drawn");
		ByteBuffer outside = render(input(3.0, spec(true, "none")));
		assertTrue(darkPixels(outside) == 0, "caption drawn outside its cue");
	}

	@Test
	@Timeout(60)
	@DisplayName("fades the caption in over the enter window")
	void fadesIn() throws Exception {
		ByteBuffer atStart = render(input(0.0, spec(true, "fade")));
		ByteBuffer settled = render(input(0.5, spec(true, "fade")));
		assertTrue(brightness(settled) < brightness(atStart),
				"fade caption did not darken toward full opacity: " + brightness(settled) + " vs "
						+ brightness(atStart));
	}

	@Test
	@Timeout(60)
	@DisplayName("rises the caption into place during the enter window")
	void risesIntoPlace() throws Exception {
		ByteBuffer staticFrame = render(input(0.02, spec(true, "none")));
		ByteBuffer rising = render(input(0.02, spec(true, "rise")));
		double staticY = darkCentroidY(staticFrame);
		double risingY = darkCentroidY(rising);
		assertTrue(staticY > 0 && risingY > 0, "caption box not found");
		assertTrue(risingY > staticY, "rise caption was not translated down: " + risingY + " vs "
				+ staticY);
	}
}
