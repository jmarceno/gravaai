package dev.gravaai.ui.overlays.ink;

import java.util.List;

/**
 * Midpoint smoothing for pen strokes: each new point adds a quadratic curve from the previous
 * midpoint to the new midpoint, with the previous point as control. Raw mouse samples become a
 * continuous curve with no corners at the sample points, and each piece can be drawn as soon as its
 * point arrives.
 */
final class InkSmoothing {

	private InkSmoothing() {
	}

	/** One quadratic piece of a stroke. */
	record Segment(double fromX, double fromY, double controlX, double controlY, double toX, double toY) {
	}

	/**
	 * The piece point {@code index} adds. The first point is a dot (a zero-length segment); the
	 * second starts at the first point itself so the stroke begins where the pen went down.
	 */
	static Segment segment(List<double[]> points, int index) {
		double[] point = points.get(index);
		if (index == 0) {
			return new Segment(point[0], point[1], point[0], point[1], point[0], point[1]);
		}
		double[] previous = points.get(index - 1);
		double[] to = midpoint(previous, point);
		if (index == 1) {
			return new Segment(previous[0], previous[1], previous[0], previous[1], to[0], to[1]);
		}
		double[] from = midpoint(points.get(index - 2), previous);
		return new Segment(from[0], from[1], previous[0], previous[1], to[0], to[1]);
	}

	static double[] midpoint(double[] a, double[] b) {
		return new double[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
	}
}
