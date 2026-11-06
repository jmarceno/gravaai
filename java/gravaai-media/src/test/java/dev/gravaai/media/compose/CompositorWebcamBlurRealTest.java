package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.segmentation.SelfieSegmenter;

/**
 * End-to-end webcam background blur: the committed MediaPipe portrait, the real ONNX segmenter
 * and the real GL compositor in one pass. The person is pasted over a synthetic striped room so
 * the blur has background structure to destroy — a plain studio backdrop would hide it.
 *
 * <p>Device-bound like {@code SelfieSegmenterTest}: aborts cleanly when the native library, the
 * model or a GL context is missing.</p>
 */
class CompositorWebcamBlurRealTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 200;
	private static final int CARD = 100;
	private static final int CARD_LEFT = 150;
	private static final int CARD_TOP = 50;
	/** Margin skipped when measuring the card: the blur fades out over the border. */
	private static final int CARD_INSET = 10;
	private static final int STRIPE = 60;

	private static final Background BACKGROUND = new Background.Colour(0xFF000000);
	private static final RgbaFrame SOURCE = solid(WIDTH, HEIGHT, 20);

	private static Path modelFile;

	@BeforeAll
	static void assumeNativeAvailable() {
		try {
			modelFile = OnnxLibs.resolveModelFile();
			OnnxLibs.ensureLoaded();
		} catch (RuntimeException unavailable) {
			modelFile = null;
		}
		Assumptions.assumeTrue(modelFile != null && Files.isRegularFile(modelFile),
				"selfie-segmenter.onnx not found: run java/native/build-onnxruntime.sh");
		Assumptions.assumeTrue(OnnxLibs.libraryDir() != null,
				"libonnxruntime.so not found: run java/native/build-onnxruntime.sh");
	}

	private static RgbaFrame solid(int width, int height, int value) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		for (int pixel = 0; pixel < width * height; pixel++) {
			buffer.put((byte) value).put((byte) value).put((byte) value).put((byte) 255);
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	/** The portrait fixture as an RGBA frame at its native size. */
	private static RgbaFrame portrait() throws Exception {
		try (var input = CompositorWebcamBlurRealTest.class
				.getResourceAsStream("/segmentation/portrait.jpg")) {
			Assumptions.assumeTrue(input != null, "portrait fixture missing");
			BufferedImage image = ImageIO.read(input);
			Assumptions.assumeTrue(image != null, "JPEG decoder unavailable");
			int width = image.getWidth();
			int height = image.getHeight();
			int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
			ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
			for (int pixel : pixels) {
				buffer.put((byte) ((pixel >> 16) & 0xff)).put((byte) ((pixel >> 8) & 0xff))
						.put((byte) (pixel & 0xff)).put((byte) 255);
			}
			buffer.position(0);
			return new RgbaFrame(width, height, buffer);
		}
	}

	/** Mask byte at (x,y) — the segmenter writes the value to every channel. */
	private static int maskAt(RgbaFrame mask, int x, int y) {
		return Byte.toUnsignedInt(mask.pixelsTopFirstRgba().get((y * mask.width() + x) * 4 + 3));
	}

	/**
	 * Mask byte for a point given in frame coordinates: the mask spans the frame but is at model
	 * resolution, so callers that reason in frame pixels read it through this.
	 */
	private static int maskAtFramePoint(RgbaFrame mask, int frameX, int frameY, int frameWidth,
			int frameHeight) {
		int maskX = Math.min(mask.width() - 1,
				(int) ((frameX + 0.5) * mask.width() / frameWidth));
		int maskY = Math.min(mask.height() - 1,
				(int) ((frameY + 0.5) * mask.height() / frameHeight));
		return maskAt(mask, maskX, maskY);
	}

	/**
	 * The portrait person composited over a synthetic striped room: the mask keeps the person
	 * opaque and the stripes show everywhere else. The mask covers the whole portrait, so it is
	 * read in portrait coordinates here (it is at model resolution, not portrait resolution).
	 */
	private static RgbaFrame personInStripedRoom(RgbaFrame portrait, RgbaFrame portraitMask) {
		int width = portrait.width();
		int height = portrait.height();
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		ByteBuffer person = portrait.pixelsTopFirstRgba();
		for (int y = 0; y < height; y++) {
			// The mask spans the frame by definition: map this pixel onto its grid.
			int maskY = Math.min(portraitMask.height() - 1,
					(int) ((y + 0.5) * portraitMask.height() / height));
			for (int x = 0; x < width; x++) {
				int maskX = Math.min(portraitMask.width() - 1,
						(int) ((x + 0.5) * portraitMask.width() / width));
				int offset = (y * width + x) * 4;
				// Two saturated stripes with a wide luminance gap: the saturation sample
				// survives either one, the luminance deviation sees the structure.
				boolean brightStripe = (x / STRIPE) % 2 == 0;
				int red = brightStripe ? 230 : 30;
				int green = brightStripe ? 210 : 20;
				int blue = brightStripe ? 30 : 160;
				int alpha = maskAt(portraitMask, maskX, maskY);
				int mixRed = (Byte.toUnsignedInt(person.get(offset)) * alpha + red * (255 - alpha))
						/ 255;
				int mixGreen = (Byte.toUnsignedInt(person.get(offset + 1)) * alpha
						+ green * (255 - alpha)) / 255;
				int mixBlue = (Byte.toUnsignedInt(person.get(offset + 2)) * alpha
						+ blue * (255 - alpha)) / 255;
				buffer.put((byte) mixRed).put((byte) mixGreen).put((byte) mixBlue).put((byte) 255);
			}
		}
		buffer.position(0);
		return new RgbaFrame(width, height, buffer);
	}

	private static WebcamSpec spec() {
		return new WebcamSpec(true, "center", "bottom-right", 1, 1, 50, 50, 50, false, 0, 0, 0,
				false, LayoutCrop.FULL, List.of(), 0);
	}

	private static FrameInput input(RgbaFrame webcam) {
		return new FrameInput(0, 0.0, SOURCE, webcam, List.of(), BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT,
				spec());
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

	private static int[] pixel(ByteBuffer frame, int x, int y) {
		int offset = (y * WIDTH + x) * 4;
		return new int[] {
				Byte.toUnsignedInt(frame.get(offset)),
				Byte.toUnsignedInt(frame.get(offset + 1)),
				Byte.toUnsignedInt(frame.get(offset + 2))};
	}

	private static int saturation(int[] rgb) {
		int max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
		int min = Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
		return max - min;
	}

	/**
	 * True when the mask calls the canvas point background and stays background around it. The
	 * canvas point is mapped back to frame coordinates and then onto the mask's own grid.
	 */
	private static boolean backgroundAround(RgbaFrame mask, int canvasX, int canvasY,
			double offsetX, double offsetY, double scale, int frameWidth, int frameHeight) {
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				int sourceX = (int) Math.round((canvasX + dx - offsetX) / scale);
				int sourceY = (int) Math.round((canvasY + dy - offsetY) / scale);
				if (sourceX < 0 || sourceY < 0 || sourceX >= frameWidth
						|| sourceY >= frameHeight
						|| maskAtFramePoint(mask, sourceX, sourceY, frameWidth,
								frameHeight) >= 15) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Luminance standard deviation over the card pixels the mask calls background, measured on a
	 * window inset from the card edge (the blur fades to transparent at the card border under
	 * DECAL tiling and would add structure of its own) and kept one canvas pixel clear of the
	 * person so the mask's soft edge cannot leak into the sample. The room is a two-level signal
	 * with a wide luminance gap, so the plain render deviates strongly and the blurred one
	 * collapses towards the mean colour.
	 */
	private static double backgroundLuminanceDeviation(ByteBuffer frame, RgbaFrame mask,
			double offsetX, double offsetY, double scale, int frameWidth, int frameHeight) {
		double sum = 0;
		double sumOfSquares = 0;
		int count = 0;
		for (int canvasY = CARD_TOP + CARD_INSET; canvasY < CARD_TOP + CARD - CARD_INSET;
				canvasY++) {
			for (int canvasX = CARD_LEFT + CARD_INSET; canvasX < CARD_LEFT + CARD - CARD_INSET;
					canvasX++) {
				if (!backgroundAround(mask, canvasX, canvasY, offsetX, offsetY, scale, frameWidth,
						frameHeight)) {
					continue;
				}
				int offset = (canvasY * WIDTH + canvasX) * 4;
				double luminance = 0.2126 * Byte.toUnsignedInt(frame.get(offset))
						+ 0.7152 * Byte.toUnsignedInt(frame.get(offset + 1))
						+ 0.0722 * Byte.toUnsignedInt(frame.get(offset + 2));
				sum += luminance;
				sumOfSquares += luminance * luminance;
				count++;
			}
		}
		assertTrue(count > 200, "too few confident background pixels in the card: " + count);
		double mean = sum / count;
		return Math.sqrt(sumOfSquares / count - mean * mean);
	}

	@Test
	@Timeout(180)
	@DisplayName("real segmenter + real compositor blurs the room and keeps the person sharp")
	void blursRealPersonBackground() throws Exception {
		RgbaFrame portrait = portrait();
		RgbaFrame scene;
		RgbaFrame mask;
		try (SelfieSegmenter segmenter = new SelfieSegmenter(modelFile)) {
			// Two passes: the first builds a scene with room structure behind the person, the
			// second is the mask the compositor actually receives for that scene.
			RgbaFrame portraitMask = segmenter.maskFor(portrait);
			scene = personInStripedRoom(portrait, portraitMask);
			mask = segmenter.maskFor(scene);
		}

		// Card at center 50%: a 100x100 box at (150,50). The cover fit scales the 820x1024 frame
		// by max(100/820, 100/1024) and crops the top/bottom, so source (sx,sy) lands at
		// canvas (150 + sx*scale, 50 + (sy - crop)*scale).
		double scale = Math.max((double) CARD / scene.width(), (double) CARD / scene.height());
		double offsetX = CARD_LEFT + (CARD - scene.width() * scale) / 2;
		double offsetY = CARD_TOP + (CARD - scene.height() * scale) / 2;

		// Pick the samples from the mask itself: the centroid of the confident person region and
		// the clearest background pixel, both landing well inside the card.
		long personSumX = 0;
		long personSumY = 0;
		long personCount = 0;
		int backgroundX = -1;
		int backgroundY = -1;
		int faintest = Integer.MAX_VALUE;
		for (int y = 0; y < scene.height(); y++) {
			int canvasY = (int) Math.round(offsetY + y * scale);
			if (canvasY < CARD_TOP + CARD_INSET || canvasY > CARD_TOP + CARD - CARD_INSET) {
				continue;
			}
			for (int x = 0; x < scene.width(); x++) {
				int canvasX = (int) Math.round(offsetX + x * scale);
				if (canvasX < CARD_LEFT + CARD_INSET
						|| canvasX > CARD_LEFT + CARD - CARD_INSET) {
					continue;
				}
				int value = maskAtFramePoint(mask, x, y, scene.width(), scene.height());
				if (value > 250) {
					personSumX += canvasX;
					personSumY += canvasY;
					personCount++;
				}
				if (value < faintest) {
					faintest = value;
					backgroundX = canvasX;
					backgroundY = canvasY;
				}
			}
		}
		assertTrue(personCount > 500, "no confident person region in the card: " + personCount);
		assertTrue(faintest < 15, "no confident background pixel in the card: " + faintest);
		int personX = (int) (personSumX / personCount);
		int personY = (int) (personSumY / personCount);

		ByteBuffer plain = render(input(scene));
		ByteBuffer blurred = render(input(scene).withWebcamMask(mask));

		// The person stays sharp: the 3x3 mean of the centroid matches the unblurred render
		// (the raster copy resamples at a slightly different phase than the GL texture).
		for (int channel = 0; channel < 3; channel++) {
			double plainPerson = patchMean(plain, personX, personY, channel);
			double blurredPerson = patchMean(blurred, personX, personY, channel);
			assertTrue(Math.abs(plainPerson - blurredPerson) <= 16,
					"the person must stay sharp (channel " + channel + "): " + plainPerson + " -> "
							+ blurredPerson);
		}

		// The striped room is visibly blurred: the sampled stripe loses most of its saturation
		// and the background as a whole loses most of its luminance structure.
		int[] plainBackground = pixel(plain, backgroundX, backgroundY);
		int[] blurredBackground = pixel(blurred, backgroundX, backgroundY);
		assertTrue(saturation(plainBackground) > 100,
				"the background sample should be a saturated stripe: " + saturation(plainBackground));
		assertTrue(saturation(blurredBackground) < saturation(plainBackground) / 2,
				"the striped background must blur: " + saturation(plainBackground) + " -> "
						+ saturation(blurredBackground));

		double plainDeviation = backgroundLuminanceDeviation(plain, mask, offsetX, offsetY, scale,
				scene.width(), scene.height());
		double blurredDeviation = backgroundLuminanceDeviation(blurred, mask, offsetX, offsetY,
				scale, scene.width(), scene.height());
		assertTrue(blurredDeviation < plainDeviation * 0.5,
				"the background structure must be destroyed: " + plainDeviation + " -> "
						+ blurredDeviation);

		// And the card edge stays crisp: the stage pixel just outside the card is untouched.
		assertEquals(pixel(plain, 148, 100)[0], pixel(blurred, 148, 100)[0],
				"the card edge must not bleed into the stage");
	}

	/** Mean of the 3x3 patch around (x,y) for one channel. */
	private static double patchMean(ByteBuffer frame, int x, int y, int channel) {
		double sum = 0;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				sum += Byte.toUnsignedInt(frame.get(((y + dy) * WIDTH + (x + dx)) * 4 + channel));
			}
		}
		return sum / 9.0;
	}
}
