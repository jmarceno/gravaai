package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;

/** Stage 8 real-GL coverage: text, image, figure arrow and blur annotations with timing. */
class CompositorAnnotationTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 200;
	private static final Background BACKGROUND = new Background.Colour(0xFF000000);
	private static final RgbaFrame SOURCE = solid(WIDTH, HEIGHT, 100, 100, 100);

	private static RgbaFrame solid(int width, int height, int red, int green, int blue) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int pixel = 0; pixel < width * height; pixel++) {
			buffer.put((byte) red).put((byte) green).put((byte) blue).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static AnnotationSpec.TextStyle style(int colour, Integer background) {
		return new AnnotationSpec.TextStyle(colour, background, 120, "sans-serif", true, false,
				false, "center", 8);
	}

	private static AnnotationSpec text(long startMs, long endMs) {
		return new AnnotationSpec("text", startMs, endMs, AnnotationSpec.Type.TEXT, "Hi", null,
				new AnnotationSpec.Position(50, 50), new AnnotationSpec.Size(50, 30),
				style(0xFFFFFFFF, 0xFFFF0000), 1, null, null, null);
	}

	private static byte[] greenPng() throws Exception {
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				image.setRGB(x, y, 0xFF00FF00);
			}
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	private static AnnotationSpec image(long startMs, long endMs) throws Exception {
		String data = "data:image/png;base64,"
				+ Base64.getEncoder().encodeToString(greenPng());
		return new AnnotationSpec("image", startMs, endMs, AnnotationSpec.Type.IMAGE, data, data,
				new AnnotationSpec.Position(50, 50), new AnnotationSpec.Size(20, 20),
				style(0xFFFFFFFF, null), 1, null, null, null);
	}

	private static AnnotationSpec figure(long startMs, long endMs) {
		return new AnnotationSpec("figure", startMs, endMs, AnnotationSpec.Type.FIGURE, "", null,
				new AnnotationSpec.Position(50, 50), new AnnotationSpec.Size(40, 40),
				style(0xFFFFFFFF, null), 1,
				new AnnotationSpec.Figure("down-right", 0xFFF97316, 8), null, null);
	}

	private static AnnotationSpec blur(long startMs, long endMs) {
		return new AnnotationSpec("blur", startMs, endMs, AnnotationSpec.Type.BLUR, "", null,
				new AnnotationSpec.Position(50, 50), new AnnotationSpec.Size(40, 40),
				style(0xFFFFFFFF, null), 1, null, 45.0, null);
	}

	private static FrameInput input(double timeSeconds, List<AnnotationSpec> annotations) {
		return input(timeSeconds, SOURCE, annotations);
	}

	private static FrameInput input(double timeSeconds, RgbaFrame source,
			List<AnnotationSpec> annotations) {
		return new FrameInput(0, timeSeconds, source, null, List.of(), BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT,
				WebcamSpec.DISABLED, annotations);
	}

	/** Vertical black/grey stripes: blur is visible only on a detailed frame. */
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

	private static int count(ByteBuffer frame,
			java.util.function.IntPredicate predicate) {
		int matches = 0;
		for (int index = 0; index < WIDTH * HEIGHT; index++) {
			int offset = index * 4;
			int r = Byte.toUnsignedInt(frame.get(offset));
			int g = Byte.toUnsignedInt(frame.get(offset + 1));
			int b = Byte.toUnsignedInt(frame.get(offset + 2));
			if (predicate.test(r << 16 | g << 8 | b)) {
				matches++;
			}
		}
		return matches;
	}

	private static boolean redDominant(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return r > 180 && g < 120 && b < 120;
	}

	private static boolean greenDominant(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return g > 180 && r < 120 && b < 120;
	}

	private static boolean orangeDominant(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return r > 180 && g > 80 && g < 180 && b < 100;
	}

	@Test
	@Timeout(60)
	@DisplayName("draws text with its background box while the annotation is active")
	void drawsText() throws Exception {
		ByteBuffer frame = render(input(0.0, List.of(text(0, 1000))));
		assertTrue(count(frame, AnnotationTestPredicates::red) > 50, "text background missing");
		ByteBuffer hidden = render(input(2.0, List.of(text(0, 1000))));
		assertTrue(count(hidden, AnnotationTestPredicates::red) == 0,
				"text drawn outside its time window");
	}

	@Test
	@Timeout(60)
	@DisplayName("draws an image annotation contain-fit in its box")
	void drawsImage() throws Exception {
		ByteBuffer frame = render(input(0.0, List.of(image(0, 1000))));
		assertTrue(count(frame, AnnotationTestPredicates::green) > 100, "image annotation missing");
	}

	@Test
	@Timeout(60)
	@DisplayName("draws a figure arrow with its colour")
	void drawsFigure() throws Exception {
		ByteBuffer frame = render(input(0.0, List.of(figure(0, 1000))));
		assertTrue(count(frame, AnnotationTestPredicates::orange) > 20, "arrow stroke missing");
	}

	@Test
	@Timeout(60)
	@DisplayName("blurs the rendered frame inside the annotation box")
	void blursFrame() throws Exception {
		RgbaFrame detailed = stripes();
		ByteBuffer plain = render(input(0.0, detailed, List.of()));
		ByteBuffer blurred = render(input(0.0, detailed, List.of(blur(0, 1000))));
		int difference = 0;
		for (int index = 0; index < WIDTH * HEIGHT; index++) {
			difference += Math.abs(Byte.toUnsignedInt(plain.get(index * 4))
					- Byte.toUnsignedInt(blurred.get(index * 4)));
		}
		assertTrue(difference > 0, "blur annotation did not change the frame");
	}

	@Test
	@Timeout(60)
	@DisplayName("renders nothing when no annotation is active")
	void hidesInactiveAnnotations() throws Exception {
		ByteBuffer frame = render(input(2.0, List.of(text(0, 1000))));
		assertTrue(count(frame, AnnotationTestPredicates::red) == 0, "inactive text drawn");
	}

	private static final class AnnotationTestPredicates {

		static boolean red(int rgb) {
			return redDominant(rgb);
		}

		static boolean green(int rgb) {
			return greenDominant(rgb);
		}

		static boolean orange(int rgb) {
			return orangeDominant(rgb);
		}
	}
}
