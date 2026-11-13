package dev.gravaai.ui.overlays.ink;

/**
 * The geometry of the two-point ink tools: the box of a rectangle or ellipse and the head of an
 * arrow. Pure arithmetic so it is tested without a window.
 */
final class InkShapes {

	/** Arrow head length as a multiple of the line width, with a floor for thin lines. */
	static final double HEAD_LENGTH_PER_WIDTH = 3.5;
	static final double MIN_HEAD_LENGTH = 14;
	/** Half-angle between the shaft and each side of the head. */
	static final double HEAD_HALF_ANGLE = Math.toRadians(28);

	private InkShapes() {
	}

	/** An axis-aligned box from two opposite corners. */
	record Box(double x, double y, double width, double height) {
	}

	static Box box(double x0, double y0, double x1, double y1) {
		return new Box(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0));
	}

	/**
	 * The two ends of an arrow head at {@code (x1, y1)}: from each end a line runs to the tip.
	 * The head never grows past the shaft, so a tiny drag still reads as an arrow.
	 *
	 * @return {left x, left y, right x, right y}; empty for a zero-length arrow
	 */
	static double[] arrowHead(double x0, double y0, double x1, double y1, double lineWidth) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double length = Math.hypot(dx, dy);
		if (length < 1e-6) {
			return new double[0];
		}
		double head = Math.min(length, Math.max(MIN_HEAD_LENGTH, lineWidth * HEAD_LENGTH_PER_WIDTH));
		double angle = Math.atan2(dy, dx);
		return new double[] {
				x1 - head * Math.cos(angle - HEAD_HALF_ANGLE), y1 - head * Math.sin(angle - HEAD_HALF_ANGLE),
				x1 - head * Math.cos(angle + HEAD_HALF_ANGLE), y1 - head * Math.sin(angle + HEAD_HALF_ANGLE)};
	}
}
