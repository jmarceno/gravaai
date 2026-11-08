package dev.gravaai.core.privacy;

/**
 * One detected region on one sampled frame, in source-frame pixels.
 *
 * <p>The analysis pass samples a recording at ~1 fps and hands these to {@link RegionTracker},
 * which turns a stream of per-frame boxes into a small set of timed blur regions. Keeping the
 * geometry in pixels here means the tracker and its tests never deal with the project's
 * percent-of-frame annotation space; the conversion happens once, when the regions are written.</p>
 *
 * @param left   left edge in source pixels
 * @param top    top edge in source pixels
 * @param right  right edge in source pixels (exclusive)
 * @param bottom bottom edge in source pixels (exclusive)
 * @param source what produced the box ({@code FACE} or {@code TEXT}); regions of the two kinds
 *               never merge, so the user can toggle them independently
 */
public record DetectedRegion(double left, double top, double right, double bottom, Source source) {

	/** Which detector produced a region. */
	public enum Source {
		/** A face box from the YuNet detector. */
		FACE,
		/** A text-line box from the OCR + PII pipeline. */
		TEXT
	}

	public DetectedRegion {
		if (right <= left || bottom <= top) {
			throw new IllegalArgumentException("region must have a positive size: "
					+ left + "," + top + ".." + right + "," + bottom);
		}
		if (source == null) {
			throw new IllegalArgumentException("region needs a source");
		}
	}

	public double width() {
		return right - left;
	}

	public double height() {
		return bottom - top;
	}

	public double area() {
		return width() * height();
	}

	public double centerX() {
		return (left + right) / 2;
	}

	public double centerY() {
		return (top + bottom) / 2;
	}

	/** Intersection-over-union with another region; 0 when they do not overlap. */
	public double iou(DetectedRegion other) {
		double interLeft = Math.max(left, other.left);
		double interTop = Math.max(top, other.top);
		double interRight = Math.min(right, other.right);
		double interBottom = Math.min(bottom, other.bottom);
		double interWidth = interRight - interLeft;
		double interHeight = interBottom - interTop;
		if (interWidth <= 0 || interHeight <= 0) {
			return 0;
		}
		double intersection = interWidth * interHeight;
		return intersection / (area() + other.area() - intersection);
	}

	/** This region grown by {@code padding} pixels on every side. */
	public DetectedRegion padded(double padding) {
		return new DetectedRegion(left - padding, top - padding, right + padding,
				bottom + padding, source);
	}

	/** The smallest region covering both; the source of {@code this} wins. */
	public DetectedRegion union(DetectedRegion other) {
		return new DetectedRegion(Math.min(left, other.left), Math.min(top, other.top),
				Math.max(right, other.right), Math.max(bottom, other.bottom), source);
	}

	/** Clamps the region into a frame of {@code frameWidth x frameHeight} pixels. */
	public DetectedRegion clampedTo(double frameWidth, double frameHeight) {
		double newLeft = Math.clamp(left, 0, frameWidth - 1);
		double newTop = Math.clamp(top, 0, frameHeight - 1);
		double newRight = Math.clamp(right, newLeft + 1, frameWidth);
		double newBottom = Math.clamp(bottom, newTop + 1, frameHeight);
		return new DetectedRegion(newLeft, newTop, newRight, newBottom, source);
	}
}
