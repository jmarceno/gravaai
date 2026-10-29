package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of {@code src/lib/geometry/squircle.ts} (plan §5.8 stage 4): the superellipse "squircle"
 * outline used for the video layer mask and its drop shadow. Ported 1:1 with the same exponent,
 * segment count and fallbacks; the compositor turns the point list into a Skia path.
 */
public final class SquircleGeometry {

	/** {@code SQUIRCLE_EXPONENT}. */
	public static final double SQUIRCLE_EXPONENT = 4.5;

	/** {@code SQUIRCLE_SEGMENTS_PER_CORNER}. */
	public static final int SQUIRCLE_SEGMENTS_PER_CORNER = 10;

	private SquircleGeometry() {}

	/** A squircle rectangle (TS {@code SquircleRect}). */
	public record SquircleRect(double x, double y, double width, double height, double radius) {
	}

	/** A path point (TS {@code SquirclePoint}). */
	public record SquirclePoint(double x, double y) {
	}

	private static double clamp(double value, double min, double max) {
		return Math.min(max, Math.max(min, value));
	}

	private static double getClampedRadius(double width, double height, double radius) {
		return clamp(radius, 0, Math.min(width, height) / 2);
	}

	private static SquirclePoint getSuperellipsePoint(double centerX, double centerY,
			double radius, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double exponent = 2 / SQUIRCLE_EXPONENT;
		return new SquirclePoint(
				centerX + Math.signum(cos) * radius * Math.pow(Math.abs(cos), exponent),
				centerY + Math.signum(sin) * radius * Math.pow(Math.abs(sin), exponent));
	}

	/** {@code getSquirclePathPoints}. */
	public static List<SquirclePoint> getSquirclePathPoints(SquircleRect rect) {
		List<SquirclePoint> points = new ArrayList<>();
		if (rect.width() <= 0 || rect.height() <= 0) {
			return points;
		}
		double x = rect.x();
		double y = rect.y();
		double width = rect.width();
		double height = rect.height();
		double clampedRadius = getClampedRadius(width, height, rect.radius());
		if (clampedRadius <= 0.5) {
			points.add(new SquirclePoint(x, y));
			points.add(new SquirclePoint(x + width, y));
			points.add(new SquirclePoint(x + width, y + height));
			points.add(new SquirclePoint(x, y + height));
			return points;
		}

		points.add(new SquirclePoint(x + clampedRadius, y));
		double[][] corners = {
				{x + width - clampedRadius, y + clampedRadius, -Math.PI / 2, 0},
				{x + width - clampedRadius, y + height - clampedRadius, 0, Math.PI / 2},
				{x + clampedRadius, y + height - clampedRadius, Math.PI / 2, Math.PI},
				{x + clampedRadius, y + clampedRadius, Math.PI, (Math.PI * 3) / 2}};
		for (double[] corner : corners) {
			for (int index = 1; index <= SQUIRCLE_SEGMENTS_PER_CORNER; index += 1) {
				double t = index / (double) SQUIRCLE_SEGMENTS_PER_CORNER;
				double angle = corner[2] + (corner[3] - corner[2]) * t;
				points.add(getSuperellipsePoint(corner[0], corner[1], clampedRadius, angle));
			}
		}
		return points;
	}

	/** {@code getSquircleSvgPath}. */
	public static String getSquircleSvgPath(SquircleRect rect) {
		List<SquirclePoint> points = getSquirclePathPoints(rect);
		if (points.isEmpty()) {
			return "";
		}
		StringBuilder path = new StringBuilder();
		SquirclePoint first = points.get(0);
		path.append("M ").append(first.x()).append(' ').append(first.y());
		for (int index = 1; index < points.size(); index++) {
			SquirclePoint point = points.get(index);
			path.append(" L ").append(point.x()).append(' ').append(point.y());
		}
		path.append(" Z");
		return path.toString();
	}

	/**
	 * Builds the closed Skia path for this outline (the Skija equivalent of
	 * {@code drawSquircleOnGraphics}; Skija 0.143.18 exposes paths through its static
	 * factories, so the point list becomes a closed polygon).
	 *
	 * @return the path the caller must close, or {@code null} for a degenerate rectangle
	 */
	public static io.github.humbleui.skija.@Nullable Path toPath(SquircleRect rect) {
		List<SquirclePoint> points = getSquirclePathPoints(rect);
		if (points.isEmpty()) {
			return null;
		}
		io.github.humbleui.types.Point[] polygon = new io.github.humbleui.types.Point[points.size()];
		for (int index = 0; index < points.size(); index++) {
			SquirclePoint point = points.get(index);
			polygon[index] = new io.github.humbleui.types.Point((float) point.x(),
					(float) point.y());
		}
		return io.github.humbleui.skija.Path.makePolygon(polygon, true);
	}
}
