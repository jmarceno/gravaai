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

/** Stage 10 real-GL coverage: keystroke chips, timing/fade, disabled and position settings. */
class CompositorKeystrokeTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 200;
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

	private static List<KeystrokeSpec.Press> presses() {
		return List.of(
				new KeystrokeSpec.Press(300, List.of("Ctrl", "S")),
				new KeystrokeSpec.Press(700, List.of("Enter")),
				new KeystrokeSpec.Press(1_100, List.of("Shift", "Super", "A")),
				new KeystrokeSpec.Press(1_500, List.of("Escape")));
	}

	private static KeystrokeSpec spec(boolean enabled, String position) {
		return spec(enabled, position, presses());
	}

	private static KeystrokeSpec spec(boolean enabled, String position,
			List<KeystrokeSpec.Press> presses) {
		return new KeystrokeSpec(enabled, position, 200, 900, 150, 600, 12, presses);
	}

	private static FrameInput input(double timeSeconds, KeystrokeSpec keystrokes) {
		return new FrameInput(0, timeSeconds, SOURCE, null, List.of(), BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT,
				WebcamSpec.DISABLED, List.of(), CaptionSpec.DISABLED, keystrokes);
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

	private static int darkPixels(ByteBuffer frame, int fromY, int toY) {
		int count = 0;
		for (int y = fromY; y < toY; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int offset = (y * WIDTH + x) * 4;
				if (Byte.toUnsignedInt(frame.get(offset)) < 70) {
					count++;
				}
			}
		}
		return count;
	}

	private static double brightness(ByteBuffer frame, int fromY, int toY) {
		long total = 0;
		int samples = 0;
		for (int y = fromY; y < toY; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int offset = (y * WIDTH + x) * 4;
				total += Byte.toUnsignedInt(frame.get(offset));
				samples++;
			}
		}
		return total / (double) samples;
	}

	@Test
	@Timeout(60)
	@DisplayName("draws key chips for the active group")
	void drawsActiveChips() throws Exception {
		ByteBuffer frame = render(input(0.8, spec(true, "bottom-center")));
		assertTrue(darkPixels(frame, HEIGHT - 70, HEIGHT) > 50, "keystroke chips missing");
	}

	@Test
	@Timeout(60)
	@DisplayName("hides keystrokes when disabled or outside every group")
	void hidesInactiveKeystrokes() throws Exception {
		assertTrue(darkPixels(render(input(0.8, spec(false, "bottom-center"))),
				HEIGHT - 70, HEIGHT) == 0, "disabled keystrokes drawn");
		assertTrue(darkPixels(render(input(3.0, spec(true, "bottom-center"))),
				HEIGHT - 70, HEIGHT) == 0, "keystrokes drawn after the group ended");
	}

	@Test
	@Timeout(60)
	@DisplayName("fades the chips in over fadeMs")
	void fadesIn() throws Exception {
		ByteBuffer early = render(input(0.31, spec(true, "bottom-center")));
		ByteBuffer settled = render(input(0.8, spec(true, "bottom-center")));
		assertTrue(brightness(settled, HEIGHT - 70, HEIGHT) < brightness(early, HEIGHT - 70, HEIGHT),
				"keystroke fade in did not darken");
	}

	@Test
	@Timeout(60)
	@DisplayName("moves the chips to the configured position")
	void respectsPosition() throws Exception {
		ByteBuffer top = render(input(0.8, spec(true, "top-center",
				List.of(new KeystrokeSpec.Press(300, List.of("A"))))));
		assertTrue(darkPixels(top, 0, 80) > 50, "top-center keystrokes missing");
		assertTrue(darkPixels(top, HEIGHT - 70, HEIGHT) == 0, "bottom chips drawn for top-center");
	}
}
