package dev.gravaai.media.detection;

/**
 * One detected face in source-frame pixels: the bounding box plus the detection score (0..1).
 *
 * <p>{@code x}/{@code y} are the top-left corner and {@code width}/{@code height} the size, all in
 * whole source pixels with the origin at the frame's top-left. Auto-masking expands these into
 * percent-coordinate blur regions, so the pixel geometry stays here and the conversion to the
 * project's annotation space happens once, at the analysis-pass boundary.</p>
 */
public record FaceBox(double x, double y, double width, double height, float score) {

	public FaceBox {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("face box must have a positive size: " + width
					+ "x" + height);
		}
		if (score < 0 || score > 1) {
			throw new IllegalArgumentException("face score must be within [0,1]: " + score);
		}
	}

	/** The centre x, in source pixels. */
	public double centerX() {
		return x + width / 2;
	}

	/** The centre y, in source pixels. */
	public double centerY() {
		return y + height / 2;
	}

	/** The box area in square pixels. */
	public double area() {
		return width * height;
	}

	/** Intersection-over-union of two boxes; 0 when they do not overlap. */
	public double iou(FaceBox other) {
		double left = Math.max(x, other.x);
		double top = Math.max(y, other.y);
		double right = Math.min(x + width, other.x + other.width);
		double bottom = Math.min(y + height, other.y + other.height);
		double overlapWidth = right - left;
		double overlapHeight = bottom - top;
		if (overlapWidth <= 0 || overlapHeight <= 0) {
			return 0;
		}
		double intersection = overlapWidth * overlapHeight;
		return intersection / (area() + other.area() - intersection);
	}
}
