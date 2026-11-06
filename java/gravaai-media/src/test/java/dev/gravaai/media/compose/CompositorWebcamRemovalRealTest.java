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

import dev.gravaai.core.project.WebcamBackgroundEffect;
import dev.gravaai.media.gl.GlPreviewThread;
import dev.gravaai.media.gl.PboFormat;
import dev.gravaai.media.onnx.OnnxLibs;
import dev.gravaai.media.segmentation.ModnetSegmenter;

/**
 * End-to-end webcam background removal: the committed portrait, the real MODNet segmenter (the
 * slow path this effect exists for) and the real GL compositor in one pass. The person is pasted
 * over a saturated striped room and the scene behind the card is a flat dark recording over a flat
 * grey wallpaper, so "the camera frame is gone and the recording shows through where the room was
 * removed" is a direct pixel test.
 *
 * <p>Also covers the failure modes that must not regress: with no mask the card is drawn normally
 * (an unavailable model never blanks the webcam), and the stage outside the card is untouched.
 *
 * <p>Device-bound like {@code CompositorWebcamBlurRealTest}: aborts cleanly when the native
 * library, the model or a GL context is missing.</p>
 */
class CompositorWebcamRemovalRealTest {

	private static final int WIDTH = 400;
	private static final int HEIGHT = 200;
	private static final int CARD = 100;
	private static final int CARD_LEFT = 150;
	private static final int CARD_TOP = 50;
	/** Margin skipped when sampling the card: the edges blend with the stage. */
	private static final int CARD_INSET = 10;
	private static final int STRIPE = 60;
	/**
	 * The project wallpaper: a near-neutral grey. Deliberately low-chroma so "is this the wallpaper"
	 * and "is this a saturated room stripe" stay mutually exclusive; a saturated wallpaper colour
	 * would satisfy both predicates and make the test lie to itself.
	 */
	private static final int WALLPAPER_RED = 120;
	private static final int WALLPAPER_GREEN = 128;
	private static final int WALLPAPER_BLUE = 124;

	private static final Background BACKGROUND =
			new Background.Colour(0xFF000000 | (WALLPAPER_RED << 16) | (WALLPAPER_GREEN << 8)
					| WALLPAPER_BLUE);
	private static final RgbaFrame SOURCE = solid(WIDTH, HEIGHT, 20);

	private static Path modelFile;

	@BeforeAll
	static void assumeNativeAvailable() {
		try {
			modelFile = OnnxLibs.resolveModnetModelFile();
			OnnxLibs.ensureLoaded();
		} catch (RuntimeException unavailable) {
			modelFile = null;
		}
		Assumptions.assumeTrue(modelFile != null && Files.isRegularFile(modelFile),
				"modnet.onnx not found: run java/native/build-onnxruntime.sh");
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

	private static RgbaFrame portrait() throws Exception {
		try (var input = CompositorWebcamRemovalRealTest.class
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

	private static int maskAt(RgbaFrame mask, int x, int y) {
		return Byte.toUnsignedInt(mask.pixelsTopFirstRgba().get((y * mask.width() + x) * 4 + 3));
	}

	/** The portrait person composited over a synthetic striped room (the "webcam" frame). */
	private static RgbaFrame personInStripedRoom(RgbaFrame portrait, RgbaFrame portraitMask) {
		int width = portrait.width();
		int height = portrait.height();
		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
		ByteBuffer person = portrait.pixelsTopFirstRgba();
		for (int y = 0; y < height; y++) {
			int maskY = Math.min(portraitMask.height() - 1,
					(int) ((y + 0.5) * portraitMask.height() / height));
			for (int x = 0; x < width; x++) {
				int maskX = Math.min(portraitMask.width() - 1,
						(int) ((x + 0.5) * portraitMask.width() / width));
				int offset = (y * width + x) * 4;
				boolean brightStripe = (x / STRIPE) % 2 == 0;
				int red = brightStripe ? 230 : 30;
				int green = brightStripe ? 210 : 20;
				int blue = brightStripe ? 30 : 160;
				int alpha = maskAt(portraitMask, maskX, maskY);
				int mixRed = (Byte.toUnsignedInt(person.get(offset)) * alpha
						+ red * (255 - alpha)) / 255;
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

	private static WebcamSpec spec(WebcamBackgroundEffect effect) {
		return new WebcamSpec(true, "center", "bottom-right", 1, 1, 50, 50, 50, false, 0, 0, 0,
				false, LayoutCrop.FULL, List.of(), 0, java.util.Optional.empty(), effect);
	}

	private static FrameInput input(RgbaFrame webcam, WebcamBackgroundEffect effect) {
		return new FrameInput(0, 0.0, SOURCE, webcam, List.of(), BACKGROUND, 0,
				LayoutSpec.FULL_FRAME, ZoomSpec.NONE, FrameStyle.DEFAULT, CursorSpec.DEFAULT,
				spec(effect));
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

	/** True when the pixel is the flat project wallpaper (within tolerance). */
	private static boolean isWallpaper(int[] rgb) {
		return Math.abs(rgb[0] - WALLPAPER_RED) <= 8
				&& Math.abs(rgb[1] - WALLPAPER_GREEN) <= 8
				&& Math.abs(rgb[2] - WALLPAPER_BLUE) <= 8;
	}

	/** True when the pixel still carries the saturated striped room. */
	private static boolean isStripedRoom(int[] rgb) {
		int max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
		int min = Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
		return max - min > 100;
	}

	@Test
	@Timeout(180)
	@DisplayName("the scene behind the webcam shows through where the mask says background")
	void removesBackgroundToTheSceneBehind() throws Exception {
		RgbaFrame portrait = portrait();
		RgbaFrame scene;
		RgbaFrame mask;
		try (ModnetSegmenter segmenter = new ModnetSegmenter(modelFile)) {
			RgbaFrame portraitMask = segmenter.maskFor(portrait);
			scene = personInStripedRoom(portrait, portraitMask);
			mask = segmenter.maskFor(scene);
		}

		ByteBuffer plain = render(input(scene, WebcamBackgroundEffect.NONE));
		ByteBuffer removed = render(input(scene, WebcamBackgroundEffect.REMOVE)
				.withWebcamMask(mask));
		Rect card = cardRect(plain);

		// Compare the two renders inside the card. The assertions are on what a viewer sees, which
		// keeps them independent of the exact mask-to-stage mapping:
		//   * the room's striped pixels must be gone, replaced by whatever the scene shows behind
		//     the webcam — the recording, because the camera frame itself must not be drawn —
		//     except the narrow band where the soft mask edge blends the person (the silhouette
		//     runs through much of the card, so that band is a real share of the samples);
		//   * the person must survive: a large region must be byte-identical to the plain render;
		//   * none of the removed background may become the wallpaper: that was the old card-patch
		//     behaviour, a cut-out floating on a re-drawn wallpaper rectangle instead of the scene.
		int backgroundSamples = 0;
		int sceneSamples = 0;
		int wallpaperSamples = 0;
		int unchangedSamples = 0;
		for (int canvasY = card.top() + CARD_INSET; canvasY < card.bottom() - CARD_INSET;
				canvasY++) {
			for (int canvasX = card.left() + CARD_INSET; canvasX < card.right() - CARD_INSET;
					canvasX++) {
				int[] plainPixel = pixel(plain, canvasX, canvasY);
				int[] removedPixel = pixel(removed, canvasX, canvasY);
				if (java.util.Arrays.equals(plainPixel, removedPixel)) {
					unchangedSamples++;
					continue;
				}
				if (isStripedRoom(plainPixel)) {
					backgroundSamples++;
					if (isSource(removedPixel)) {
						sceneSamples++;
					} else if (isWallpaper(removedPixel)) {
						wallpaperSamples++;
					}
				}
			}
		}
		assertTrue(backgroundSamples > 300,
				"too few clearly-background pixels in the card: " + backgroundSamples);
		assertTrue(sceneSamples >= backgroundSamples * 0.8,
				"the scene behind the webcam must show through where the room was removed: "
						+ sceneSamples + "/" + backgroundSamples);
		assertEquals(0, wallpaperSamples,
				"the camera frame must be gone: no wallpaper patch may be painted under the"
						+ " cut-out");
		assertTrue(unchangedSamples > 300,
				"the person must keep rendering: only " + unchangedSamples
						+ " pixels matched the plain render");
		// Room structure deep in the background must be gone. The band right next to the person is
		// exempt: the removal and the plain card are two different draw paths (runtime-effect
		// shader vs. direct image fill) and sample the cover fit at slightly different phases, so
		// the silhouette moves by about a pixel. A stripe surviving *away* from the silhouette
		// would be a hole in the effect.
		int holes = 0;
		for (int canvasY = card.top() + CARD_INSET; canvasY < card.bottom() - CARD_INSET;
				canvasY++) {
			for (int canvasX = card.left() + CARD_INSET; canvasX < card.right() - CARD_INSET;
					canvasX++) {
				int[] plainPixel = pixel(plain, canvasX, canvasY);
				if (!isStripedRoom(plainPixel) || !isStripedRoom(pixel(removed, canvasX, canvasY))) {
					continue;
				}
				if (!nearPerson(plain, removed, canvasX, canvasY, EDGE_BAND_PX)) {
					holes++;
				}
			}
		}
		assertEquals(0, holes, "the room must be gone away from the silhouette");
	}

	/** Width in stage pixels of the resampling band along the silhouette. */
	private static final int EDGE_BAND_PX = 3;

	/**
	 * True when a person pixel (one the removal left byte-identical to the plain render) sits within
	 * {@code radius} stage pixels of (x,y).
	 */
	private static boolean nearPerson(ByteBuffer plain, ByteBuffer removed, int x, int y,
			int radius) {
		for (int dy = -radius; dy <= radius; dy++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int px = x + dx;
				int py = y + dy;
				if (px < 0 || py < 0 || px >= WIDTH || py >= HEIGHT) {
					continue;
				}
				if (java.util.Arrays.equals(pixel(plain, px, py), pixel(removed, px, py))) {
					return true;
				}
			}
		}
		return false;
	}

	@Test
	@Timeout(180)
	@DisplayName("no mask means the card is drawn normally, never blanked")
	void unavailableMaskDrawsTheCard() throws Exception {
		RgbaFrame portrait = portrait();
		RgbaFrame scene;
		try (ModnetSegmenter segmenter = new ModnetSegmenter(modelFile)) {
			RgbaFrame portraitMask = segmenter.maskFor(portrait);
			scene = personInStripedRoom(portrait, portraitMask);
		}
		// REMOVE on but the segmenter answered nothing (native payload or model missing): the card
		// must look exactly like the effect-off render in the middle of the room.
		ByteBuffer plain = render(input(scene, WebcamBackgroundEffect.NONE));
		ByteBuffer noMask = render(input(scene, WebcamBackgroundEffect.REMOVE));
		Rect card = cardRect(plain);
		int centreX = (card.left() + card.right()) / 2;
		int centreY = (card.top() + card.bottom()) / 2;
		for (int channel = 0; channel < 3; channel++) {
			assertEquals(pixel(plain, centreX, centreY)[channel],
					pixel(noMask, centreX, centreY)[channel],
					"an unavailable segmenter must not blank the webcam card");
		}
		boolean striped = false;
		for (int x = card.left() + CARD_INSET; x < card.right() - CARD_INSET; x++) {
			if (isStripedRoom(pixel(noMask, x, centreY))) {
				striped = true;
				break;
			}
		}
		assertTrue(striped, "the room must still be visible without a mask");
	}

	@Test
	@Timeout(180)
	@DisplayName("the stage outside the card is untouched by the removal pass")
	void leavesStageOutsideCardAlone() throws Exception {
		RgbaFrame portrait = portrait();
		RgbaFrame scene;
		RgbaFrame mask;
		try (ModnetSegmenter segmenter = new ModnetSegmenter(modelFile)) {
			RgbaFrame portraitMask = segmenter.maskFor(portrait);
			scene = personInStripedRoom(portrait, portraitMask);
			mask = segmenter.maskFor(scene);
		}
		ByteBuffer plain = render(input(scene, WebcamBackgroundEffect.NONE));
		ByteBuffer removed = render(input(scene, WebcamBackgroundEffect.REMOVE)
				.withWebcamMask(mask));
		Rect card = cardRect(plain);
		// The person pass draws over the full stage (a sub-rect runtime-effect draw renders
		// displaced in this stack) but is clipped to the card, so the stage outside must be
		// byte-identical to the effect-off render: the screen layer, the wallpaper corners and the
		// card border are all untouched.
		int changed = 0;
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				boolean insideCard = x >= card.left() && x < card.right() && y >= card.top()
						&& y < card.bottom();
				if (!insideCard && !java.util.Arrays.equals(pixel(plain, x, y),
						pixel(removed, x, y))) {
					changed++;
				}
			}
		}
		assertEquals(0, changed, "the removal pass must not paint outside the card");
	}

	/**
	 * The card rect as actually rendered: the bounding box of the webcam content, which is the only
	 * thing on the stage that is neither the flat wallpaper nor the flat screen video. Deriving it
	 * from the render keeps the test honest about the compositor's layout (the margin and the cover
	 * fit are its business, not the test's).
	 */
	private static Rect cardRect(ByteBuffer plain) {
		int left = Integer.MAX_VALUE;
		int top = Integer.MAX_VALUE;
		int right = -1;
		int bottom = -1;
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				int[] rgb = pixel(plain, x, y);
				if (!isWallpaper(rgb) && !isSource(rgb)) {
					left = Math.min(left, x);
					top = Math.min(top, y);
					right = Math.max(right, x);
					bottom = Math.max(bottom, y);
				}
			}
		}
		assertTrue(right > left && bottom > top, "the webcam card was not rendered");
		return new Rect(left, top, right + 1, bottom + 1);
	}

	/** True when the pixel is the flat screen-video layer the compositor draws under the card. */
	private static boolean isSource(int[] rgb) {
		return Math.abs(rgb[0] - 20) <= 4 && Math.abs(rgb[1] - 20) <= 4
				&& Math.abs(rgb[2] - 20) <= 4;
	}

	/** An inclusive-exclusive stage rectangle. */
	private record Rect(int left, int top, int right, int bottom) {
	}

	/**
	 * The mask byte for one stage point, read back through the card's cover fit: the webcam frame
	 * is cover-scaled into the card rect and centred, then the frame maps onto the mask's own grid.
	 */
	private static int maskAtFramePoint(RgbaFrame mask, RgbaFrame frame, Rect card, int canvasX,
			int canvasY) {
		double cardWidth = card.right() - card.left();
		double cardHeight = card.bottom() - card.top();
		double scale = Math.max(cardWidth / frame.width(), cardHeight / frame.height());
		double offsetX = card.left() + (cardWidth - frame.width() * scale) / 2;
		double offsetY = card.top() + (cardHeight - frame.height() * scale) / 2;
		double frameX = (canvasX + 0.5 - offsetX) / scale;
		double frameY = (canvasY + 0.5 - offsetY) / scale;
		int maskX = Math.min(mask.width() - 1,
				Math.max(0, (int) (frameX * mask.width() / frame.width())));
		int maskY = Math.min(mask.height() - 1,
				Math.max(0, (int) (frameY * mask.height() / frame.height())));
		return maskAt(mask, maskX, maskY);
	}
}
