package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Deterministic contract of the compositor-backed export frame provider (plan §5.10). */
class CompositorExportFrameProviderTest {

	private static final int WIDTH = 200;
	private static final int HEIGHT = 100;
	private static final int FRAME_RATE = 30;
	private static final Background BACKGROUND = new Background.Colour(0xFF000000);

	private static RgbaFrame solid(int red, int green, int blue) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
			buffer.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(WIDTH, HEIGHT, buffer);
	}

	private static RgbaFrame split() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				boolean left = x < WIDTH / 2;
				buffer.put((byte) (left ? 255 : 0)).put((byte) 0)
						.put((byte) (left ? 0 : 255)).put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(WIDTH, HEIGHT, buffer);
	}

	private static int centre(ByteBuffer frame) {
		int offset = ((HEIGHT / 2) * WIDTH + WIDTH / 2) * 4;
		return Byte.toUnsignedInt(frame.get(offset));
	}

	private static ByteBuffer buffer() {
		return ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
	}

	private static byte[] bytes(ByteBuffer buffer) {
		byte[] bytes = new byte[WIDTH * HEIGHT * 4];
		ByteBuffer from = buffer.duplicate();
		from.position(0);
		from.get(bytes);
		return bytes;
	}

	private static ByteBuffer bytesOf(ByteBuffer buffer) {
		ByteBuffer copy = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		ByteBuffer from = buffer.duplicate();
		from.position(0);
		from.limit(WIDTH * HEIGHT * 4);
		copy.put(from);
		copy.position(0);
		return copy;
	}

	private static void assumeGl() {
		try {
			try (CompositorFrameAtTime renderer = new CompositorFrameAtTime(8, 8,
					time -> FrameInput.ofSource(0, time, solid(1, 2, 3), BACKGROUND))) {
				renderer.frameAtTime(0);
			}
		} catch (RuntimeException error) {
			Assumptions.abort("GL context unavailable: " + error);
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("renders each export frame into the caller's RGBA buffer")
	void rendersIntoCallerBuffer() {
		assumeGl();
		AtomicInteger calls = new AtomicInteger();
		try (CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
				WIDTH, HEIGHT, FRAME_RATE, time -> {
					calls.incrementAndGet();
					int frame = (int) Math.round(time * FRAME_RATE);
					return FrameInput.ofSource(frame, time, solid(40 + frame * 20, 80, 120),
							BACKGROUND);
				})) {
			ByteBuffer first = buffer();
			provider.renderFrame(0, 0, first);
			assertEquals(40, centre(first));
			ByteBuffer second = buffer();
			provider.renderFrame(1, 1.0 / FRAME_RATE, second);
			assertEquals(60, centre(second));
			ByteBuffer third = buffer();
			provider.renderFrame(2, 2.0 / FRAME_RATE, third);
			assertEquals(80, centre(third));
			// Three frames plus the decode-ahead of frame 3.
			assertEquals(4, calls.get());
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("renders the same input deterministically")
	void deterministic() {
		assumeGl();
		try (CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
				WIDTH, HEIGHT, FRAME_RATE,
				time -> FrameInput.ofSource(0, time, split(), BACKGROUND))) {
			ByteBuffer first = buffer();
			provider.renderFrame(5, 5.0 / FRAME_RATE, first);
			ByteBuffer second = buffer();
			provider.renderFrame(5, 5.0 / FRAME_RATE, second);
			assertArrayEquals(bytes(first), bytes(second),
					"same frameIndex/time must render identical pixels");
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("reset clears springs/motion history so GIF passes match")
	void resetMakesPassesIdentical() {
		assumeGl();
		ZoomRegionSpec hold = new ZoomRegionSpec("zoom", 0, 4000, ZoomDepth.D2,
				new ZoomFocus(0.4, 0.5), ZoomMode.MANUAL);
		ZoomSpec zoom = new ZoomSpec(List.of(hold), 0.6, false, false, null, null, 1, 1, 1, 0.5);
		FrameInput[] input = new FrameInput[6];
		for (int frame = 0; frame < input.length; frame++) {
			double time = frame / (double) FRAME_RATE;
			input[frame] = FrameInput.ofSource(frame, time, split(), BACKGROUND, 0,
					LayoutSpec.FULL_FRAME, zoom);
		}
		try (CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
				WIDTH, HEIGHT, FRAME_RATE, time -> input[Math.min(input.length - 1,
						(int) Math.round(time * FRAME_RATE))])) {
			ByteBuffer[] firstPass = new ByteBuffer[input.length];
			for (int frame = 0; frame < input.length; frame++) {
				firstPass[frame] = bytesOf(render(provider, frame));
			}
			provider.reset();
			for (int frame = 0; frame < input.length; frame++) {
				ByteBuffer secondPass = bytesOf(render(provider, frame));
				assertArrayEquals(bytes(firstPass[frame]), bytes(secondPass),
						"pass 2 frame " + frame + " differs after reset()");
			}
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("GPU I420 output matches a BT.709 limited-range conversion of the RGBA frame")
	void yuvMatchesBt709OfRgba() {
		assumeGl();
		RgbaFrame gradient = gradient();
		try (CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
				WIDTH, HEIGHT, FRAME_RATE,
				time -> FrameInput.ofSource(0, time, gradient, BACKGROUND))) {
			assertTrue(provider.supportsYuv420p());
			ByteBuffer rgba = buffer();
			provider.renderFrame(3, 3.0 / FRAME_RATE, rgba);
			ByteBuffer yuv = ByteBuffer.allocate(WIDTH * HEIGHT * 3 / 2);
			provider.renderFrameYuv420p(3, 3.0 / FRAME_RATE, yuv);
			byte[] expected = cpuI420(rgba);
			int worst = 0;
			int worstAt = -1;
			for (int i = 0; i < expected.length; i++) {
				int diff = Math.abs(Byte.toUnsignedInt(expected[i]) - Byte.toUnsignedInt(yuv.get(i)));
				if (diff > worst) {
					worst = diff;
					worstAt = i;
				}
			}
			assertTrue(worst <= 2, "max difference " + worst + " at byte " + worstAt + " of "
					+ expected.length + " (Y plane " + (WIDTH * HEIGHT) + ")");
		}
	}

	@Test
	@DisplayName("I420 needs an even width and a height divisible by four")
	void yuvSizeSupport() {
		assertTrue(new CompositorExportFrameProvider(WIDTH, HEIGHT, FRAME_RATE,
				time -> null).supportsYuv420p());
		assertTrue(!new CompositorExportFrameProvider(WIDTH, 102, FRAME_RATE,
				time -> null).supportsYuv420p());
	}

	private static RgbaFrame gradient() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				buffer.put((byte) (x * 255 / (WIDTH - 1))).put((byte) (y * 255 / (HEIGHT - 1)))
						.put((byte) ((x + y) % 256)).put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(WIDTH, HEIGHT, buffer);
	}

	/** Reference BT.709 limited-range I420 with 2x2 chroma averaging. */
	private static byte[] cpuI420(ByteBuffer rgba) {
		byte[] out = new byte[WIDTH * HEIGHT * 3 / 2];
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				double[] c = rgbAt(rgba, x, y);
				double luma = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
				out[y * WIDTH + x] = (byte) Math.round(16 + 219 * luma);
			}
		}
		int chromaWidth = WIDTH / 2;
		int chromaHeight = HEIGHT / 2;
		int uOffset = WIDTH * HEIGHT;
		int vOffset = uOffset + chromaWidth * chromaHeight;
		for (int cy = 0; cy < chromaHeight; cy++) {
			for (int cx = 0; cx < chromaWidth; cx++) {
				double[] sum = new double[3];
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						double[] c = rgbAt(rgba, cx * 2 + dx, cy * 2 + dy);
						for (int k = 0; k < 3; k++) {
							sum[k] += c[k] / 4;
						}
					}
				}
				double u = -0.1146 * sum[0] - 0.3854 * sum[1] + 0.5 * sum[2];
				double v = 0.5 * sum[0] - 0.4542 * sum[1] - 0.0458 * sum[2];
				out[uOffset + cy * chromaWidth + cx] = (byte) Math.round(128 + 224 * u);
				out[vOffset + cy * chromaWidth + cx] = (byte) Math.round(128 + 224 * v);
			}
		}
		return out;
	}

	private static double[] rgbAt(ByteBuffer rgba, int x, int y) {
		int offset = (y * WIDTH + x) * 4;
		return new double[] {Byte.toUnsignedInt(rgba.get(offset)) / 255.0,
				Byte.toUnsignedInt(rgba.get(offset + 1)) / 255.0,
				Byte.toUnsignedInt(rgba.get(offset + 2)) / 255.0};
	}

	private static ByteBuffer render(CompositorExportFrameProvider provider, int frame) {
		ByteBuffer output = buffer();
		provider.renderFrame(frame, frame / (double) FRAME_RATE, output);
		return output;
	}

	@Test
	@DisplayName("rejects invalid frame arguments and undersized buffers")
	void rejectsInvalidArguments() {
		CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
				WIDTH, HEIGHT, FRAME_RATE, time -> FrameInput.ofSource(0, time, split(), BACKGROUND));
		assertThrows(IllegalArgumentException.class,
				() -> provider.renderFrame(-1, 0, buffer()));
		assertThrows(IllegalArgumentException.class,
				() -> provider.renderFrame(0, Double.NaN, buffer()));
		assertThrows(IllegalArgumentException.class,
				() -> provider.renderFrame(0, 0, ByteBuffer.allocateDirect(16)));
		assertTrue(provider.width() == WIDTH && provider.height() == HEIGHT
				&& provider.frameRate() == FRAME_RATE);
		provider.close();
	}
}
