package dev.gravaai.media.superres;

import java.nio.ByteBuffer;

import org.jspecify.annotations.Nullable;

import io.github.humbleui.types.Rect;

/**
 * The pure half of the deep-zoom upscaler ({@link SuperResolutionEngine}): which source window a
 * magnified frame actually shows, how much of it to replace with upscaled pixels, and how that
 * output maps back onto the screen.
 *
 * <p>The compositor draws the source stretched onto the video box, then scales the box around the
 * zoom focus. At 5&times; it therefore reads only {@code box / 5} of the source and stretches those
 * few texels over the whole screen. That visible window is what the upscaler replaces: running the
 * network over the whole 1080p source would cost ~16&times; the pixels for output the camera never
 * shows.</p>
 *
 * <p>No ONNX and no GL here, so the crop math and the quality policy are unit-testable on plain
 * values.</p>
 */
public final class SuperResolution {

	/** The scale of the pinned model: a {@code w x h} window comes back as {@code 4w x 4h}. */
	public static final int SCALE = 4;

	/**
	 * The camera magnification from which the export path upscales: {@code D5} (3.5&times;). This
	 * is where the card says a 1080p source turns to mush, and it is also the depth at which one
	 * padded inference ({@link #PAD_FRACTION}) still covers the whole gesture up to {@code D6}
	 * (5&times;): 5&times;'s window is 384x216, its padded window 576x324, and the visible window at
	 * 3.5&times; is 549x309, which fits. An eased zoom therefore pays for exactly one inference and
	 * re-uses it all the way in.
	 */
	public static final double MIN_MAGNIFICATION = 3.5;

	/**
	 * The largest source window one inference runs on, in pixels. The 5&times; window of a 1080p
	 * source is ~92k px and runs in about half a second on this machine's CPU; this cap keeps a 4K
	 * export's window (4&times; the pixels) from turning into a multi-second frame, and is well
	 * inside what the fully-convolutional network handles smoothly.
	 */
	public static final int MAX_INFERENCE_PIXELS = 640 * 640;

	/**
	 * How far the upscaled window is grown past the visible one, per side, as a fraction of the
	 * visible window. One inference then covers a stretch of camera movement, so panning and
	 * zooming reuse it instead of paying per frame; the padding shrinks when the padded window
	 * would exceed {@link #MAX_INFERENCE_PIXELS}.
	 */
	public static final double PAD_FRACTION = 0.25;

	/** The source window a magnified camera actually shows, in source pixel space. */
	public record Crop(int x, int y, int width, int height) {

		public Crop {
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException(
						"crop must be positive: " + width + "x" + height);
			}
			if (x < 0 || y < 0) {
				throw new IllegalArgumentException("crop origin must not be negative: " + x + ","
						+ y);
			}
		}

		public int right() {
			return x + width;
		}

		public int bottom() {
			return y + height;
		}

		/** Area in source pixels (what the inference cost is proportional to). */
		public int pixels() {
			return width * height;
		}

		/** Whether {@code other} lies entirely inside this window. */
		public boolean contains(Crop other) {
			return other.x >= x && other.y >= y && other.right() <= right()
					&& other.bottom() <= bottom();
		}
	}

	private SuperResolution() {}

	/**
	 * The camera magnification of one frame: screen pixels per source pixel, the same measure the
	 * compositor's sharpening pass gates on.
	 */
	public static double magnification(Rect source, Rect destination,
			dev.gravaai.media.compose.ZoomTransform.AppliedTransform transform) {
		if (source.getWidth() <= 0 || destination.getWidth() <= 0) {
			return Double.NaN;
		}
		return transform.scale() * destination.getWidth() / source.getWidth();
	}

	/** Whether a frame at this magnification is deep enough to pay for the network. */
	public static boolean shouldUpscale(double magnification) {
		return Double.isFinite(magnification) && magnification >= MIN_MAGNIFICATION;
	}

	/**
	 * The source window the camera shows for one frame, or {@code null} when the frame is not
	 * deep enough to upscale (or the window degenerates).
	 *
	 * <p>The compositor draws the source through a matrix that stretches it onto the video box and
	 * then applies {@code canvas.translate(t); canvas.scale(s)} around it. A canvas point
	 * {@code c} therefore came from box point {@code (c - t) / s}, and this resolves the whole
	 * canvas that way: it is the render window, not the frame, that the network has to cover.</p>
	 *
	 * @param source      the decoded frame's extent in pixels
	 * @param destination the video box on the output canvas, before the camera transform
	 * @param transform   the camera transform (scale + translation) applied around the box
	 * @param canvasWidth  output canvas width in pixels
	 * @param canvasHeight output canvas height in pixels
	 * @return the visible window, clipped to the source, or {@code null} when not applicable
	 */
	public static @Nullable Crop cropFor(Rect source, Rect destination,
			dev.gravaai.media.compose.ZoomTransform.AppliedTransform transform,
			int canvasWidth, int canvasHeight) {
		if (source.getWidth() <= 0 || source.getHeight() <= 0 || destination.getWidth() <= 0
				|| destination.getHeight() <= 0 || canvasWidth <= 0 || canvasHeight <= 0) {
			return null;
		}
		double scale = transform.scale();
		if (!Double.isFinite(scale) || !shouldUpscale(magnification(source, destination,
				transform))) {
			return null;
		}
		// Canvas -> box (invert translate/scale) -> source (invert the stretch onto the box).
		double boxLeft = -transform.x() / scale;
		double boxTop = -transform.y() / scale;
		double sourcePerBoxX = source.getWidth() / destination.getWidth();
		double sourcePerBoxY = source.getHeight() / destination.getHeight();
		// The source rect is often a crop into the frame (layout crop), so it has an origin: the
		// inverse of the draw matrix has to add it back, and the clamp is to the rect, not the
		// frame. The returned crop stays in absolute frame pixels, which is what the window copy
		// indexes.
		double sx = source._left + (boxLeft - destination._left) * sourcePerBoxX;
		double sy = source._top + (boxTop - destination._top) * sourcePerBoxY;
		double sw = (canvasWidth / scale) * sourcePerBoxX;
		double sh = (canvasHeight / scale) * sourcePerBoxY;
		int x0 = (int) Math.max(source._left, Math.floor(sx));
		int y0 = (int) Math.max(source._top, Math.floor(sy));
		int x1 = (int) Math.min(source.getRight(), Math.ceil(sx + sw));
		int y1 = (int) Math.min(source.getBottom(), Math.ceil(sy + sh));
		if (x1 - x0 < 2 || y1 - y0 < 2) {
			return null;
		}
		return new Crop(x0, y0, x1 - x0, y1 - y0);
	}

	/**
	 * Grows a visible window by {@link #PAD_FRACTION} per side, clamped to the source and capped at
	 * {@link #MAX_INFERENCE_PIXELS}. The return value always contains {@code visible}, so the frame
	 * that triggered the inference is fully covered.
	 *
	 * <p>Padding is what makes a moving camera cheap: the extra pixels are inferred once and then
	 * reused until the view walks out of the window. When the visible window is already near the cap
	 * the padding shrinks toward nothing rather than splitting the frame into tiles (a split would
	 * put seams on exactly the straight edges text is made of).</p>
	 */
	public static Crop padded(Crop visible, io.github.humbleui.types.Rect source) {
		int padX = (int) Math.round(visible.width() * PAD_FRACTION);
		int padY = (int) Math.round(visible.height() * PAD_FRACTION);
		int sourceLeft = (int) Math.floor(source._left);
		int sourceTop = (int) Math.floor(source._top);
		int sourceRight = (int) Math.ceil(source.getRight());
		int sourceBottom = (int) Math.ceil(source.getBottom());
		// Shrink the padding until the window fits the cap and stays inside the drawn source rect.
		// Clamping to the rect (not the frame) is what keeps the upscale inside the video box: a
		// padded window that reached past a layout crop would map outside the box and paint over
		// the background. The loop is bounded by padX + padY, both only ever decrease.
		while (padX > 0 || padY > 0) {
			int x = Math.max(sourceLeft, visible.x() - padX);
			int y = Math.max(sourceTop, visible.y() - padY);
			int right = Math.min(sourceRight, visible.right() + padX);
			int bottom = Math.min(sourceBottom, visible.bottom() + padY);
			int width = right - x;
			int height = bottom - y;
			if (width >= visible.width() && height >= visible.height()
					&& (long) width * height <= MAX_INFERENCE_PIXELS) {
				return new Crop(x, y, width, height);
			}
			padX = padX > 0 ? padX - 1 : 0;
			padY = padY > 0 ? padY - 1 : 0;
		}
		// No padding fits (an already huge window): the visible window is the window.
		return visible;
	}

	/**
	 * Where an upscaled window lands in the video box, in box coordinates: exactly where the source
	 * window would have been drawn, so the upscaled pixels are a drop-in replacement.
	 */
	public static Rect destinationOf(Crop crop, Rect source, Rect destination) {
		double scaleX = destination.getWidth() / source.getWidth();
		double scaleY = destination.getHeight() / source.getHeight();
		// The crop is in frame pixels; the draw matrix maps the source rect's own origin onto the
		// box, so the origin has to come back off before the round trip.
		return Rect.makeXYWH(
				(float) (destination._left + (crop.x() - source._left) * scaleX),
				(float) (destination._top + (crop.y() - source._top) * scaleY),
				(float) (crop.width() * scaleX), (float) (crop.height() * scaleY));
	}

	/**
	 * Copies one RGBA window out of a decoded frame, top-first and without row padding.
	 *
	 * <p>The decoded buffer is only valid until the decoder produces the next frame, so the
	 * upscaler copies the window before handing the producer thread back. The read honours the
	 * buffer's current position: a decoder may hand out a pointer at the edge inside a shared
	 * allocation, and the frame's first row is then at that offset.</p>
	 */
	public static ByteBuffer copyWindow(ByteBuffer pixelsTopFirstRgba, int frameWidth,
			Crop crop) {
		int stride = frameWidth * 4;
		int base = pixelsTopFirstRgba.position();
		ByteBuffer out = ByteBuffer.allocateDirect(crop.pixels() * 4);
		for (int row = 0; row < crop.height(); row++) {
			int start = base + (crop.y() + row) * stride + crop.x() * 4;
			ByteBuffer slice = pixelsTopFirstRgba.duplicate();
			slice.position(start);
			slice.limit(start + crop.width() * 4);
			out.put(slice);
		}
		out.flip();
		return out;
	}

	/**
	 * Whether {@code crop} of a decoded frame still holds exactly the pixels of {@code window} (a
	 * {@link #copyWindow} result for the same crop). This is what lets the upscaler reuse a window
	 * across frames only while the picture under it has not changed.
	 *
	 * @return {@code false} when any pixel differs or the crop does not fit the frame
	 */
	public static boolean windowMatches(ByteBuffer pixelsTopFirstRgba, int frameWidth,
			int frameHeight, Crop crop, ByteBuffer window) {
		if (crop.right() > frameWidth || crop.bottom() > frameHeight
				|| window.remaining() != crop.pixels() * 4) {
			return false;
		}
		int stride = frameWidth * 4;
		int rowBytes = crop.width() * 4;
		int base = pixelsTopFirstRgba.position();
		int windowBase = window.position();
		for (int row = 0; row < crop.height(); row++) {
			int start = base + (crop.y() + row) * stride + crop.x() * 4;
			if (start + rowBytes > pixelsTopFirstRgba.limit()) {
				return false;
			}
			ByteBuffer frameRow = pixelsTopFirstRgba.slice(start, rowBytes);
			ByteBuffer windowRow = window.slice(windowBase + row * rowBytes, rowBytes);
			if (frameRow.mismatch(windowRow) != -1) {
				return false;
			}
		}
		return true;
	}

	/** Packs an RGBA window into the network's NCHW float input (0-1, RGB). */
	public static float[] toChw(ByteBuffer rgbaWindow, int width, int height) {
		if (rgbaWindow.remaining() < width * height * 4) {
			throw new IllegalArgumentException("window holds " + rgbaWindow.remaining()
					+ " bytes, need " + (width * height * 4) + " for " + width + "x" + height);
		}
		float[] chw = new float[3 * width * height];
		int plane = width * height;
		int base = rgbaWindow.position();
		for (int i = 0; i < plane; i++) {
			int offset = base + i * 4;
			chw[i] = Byte.toUnsignedInt(rgbaWindow.get(offset)) / 255f;
			chw[plane + i] = Byte.toUnsignedInt(rgbaWindow.get(offset + 1)) / 255f;
			chw[2 * plane + i] = Byte.toUnsignedInt(rgbaWindow.get(offset + 2)) / 255f;
		}
		return chw;
	}

	/**
	 * Packs the network's NCHW float output (0-1 RGB at {@code 4w x 4h}) back into an opaque RGBA
	 * window, alpha 255. The buffer is direct: it becomes an {@link dev.gravaai.media.compose.RgbaFrame},
	 * which the compositor uploads straight to a GL texture without a Java copy.
	 */
	public static ByteBuffer fromChw(float[] chw, int upWidth, int upHeight) {
		if (chw.length < 3 * upWidth * upHeight) {
			throw new IllegalArgumentException("output holds " + chw.length + " values, need "
					+ (3 * upWidth * upHeight) + " for " + upWidth + "x" + upHeight);
		}
		ByteBuffer out = ByteBuffer.allocateDirect(upWidth * upHeight * 4);
		int plane = upWidth * upHeight;
		for (int i = 0; i < plane; i++) {
			out.put(toByte(chw[i]));
			out.put(toByte(chw[plane + i]));
			out.put(toByte(chw[2 * plane + i]));
			out.put((byte) 0xFF);
		}
		out.flip();
		return out;
	}

	private static byte toByte(float value) {
		// Clamp before scaling: a super-resolution network can overshoot the unit range on hard
		// edges, and a wrap-around would show as a bright speck.
		return (byte) Math.round(Math.clamp(value, 0f, 1f) * 255f);
	}
}
