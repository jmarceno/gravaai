package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Public filmstrip API: renders one composited frame per requested timeline time. */
class CompositorFrameAtTimeTest {

	private static final int WIDTH = 200;
	private static final int HEIGHT = 100;
	private static final Background BACKGROUND = new Background.Colour(0xFF000000);

	private static RgbaFrame solid(int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(WIDTH, HEIGHT, buffer);
	}

	private static int centre(RgbaFrame frame) {
		ByteBuffer pixels = frame.pixelsTopFirstRgba();
		int offset = ((HEIGHT / 2) * WIDTH + WIDTH / 2) * 4;
		return Byte.toUnsignedInt(pixels.get(offset));
	}

	@Test
	@Timeout(60)
	@DisplayName("renders the resolved input at each requested time")
	void rendersEachRequestedTime() {
		CompositorFrameAtTime renderer;
		try {
			renderer = new CompositorFrameAtTime(WIDTH, HEIGHT,
					time -> FrameInput.ofSource(0, time, solid(100), BACKGROUND));
		} catch (RuntimeException error) {
			Assumptions.abort("GL context unavailable: " + error);
			return;
		}
		try (renderer) {
			RgbaFrame first = renderer.frameAtTime(0.25);
			assertEquals(WIDTH, first.width());
			assertEquals(HEIGHT, first.height());
			assertEquals(100, centre(first));

			CompositorFrameAtTime secondRenderer = new CompositorFrameAtTime(WIDTH, HEIGHT,
					time -> FrameInput.ofSource(0, time, solid(200), BACKGROUND));
			try (secondRenderer) {
				RgbaFrame second = secondRenderer.frameAtTime(1.5);
				assertEquals(200, centre(second));
				assertNotEquals(centre(first), centre(second));
			}
		}
	}

	@Test
	@DisplayName("rejects a non-finite timeline time")
	void rejectsNonFiniteTime() {
		assertThrows(IllegalArgumentException.class,
				() -> new CompositorFrameAtTime(WIDTH, HEIGHT, time -> null).frameAtTime(Double.NaN));
	}
}
