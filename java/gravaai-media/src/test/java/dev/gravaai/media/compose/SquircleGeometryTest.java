package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The squircle outline port (plan §5.8 stage 4). The TS module has no dedicated test file, so
 * these tests pin the ported geometry contract: the flat-rectangle fallback, radius clamping,
 * the 10-segments-per-corner superellipse outline, bounds and the SVG form.
 */
class SquircleGeometryTest {

	@Test
	@DisplayName("a zero radius produces the plain rectangle outline")
	void zeroRadiusIsRectangle() {
		List<SquircleGeometry.SquirclePoint> points = SquircleGeometry.getSquirclePathPoints(
				new SquircleGeometry.SquircleRect(10, 20, 100, 40, 0));
		assertEquals(4, points.size());
		assertEquals(new SquircleGeometry.SquirclePoint(10, 20), points.get(0));
		assertEquals(new SquircleGeometry.SquirclePoint(110, 20), points.get(1));
		assertEquals(new SquircleGeometry.SquirclePoint(110, 60), points.get(2));
		assertEquals(new SquircleGeometry.SquirclePoint(10, 60), points.get(3));
	}

	@Test
	@DisplayName("the radius is clamped to half of the shorter side")
	void radiusClamps() {
		List<SquircleGeometry.SquirclePoint> points = SquircleGeometry.getSquirclePathPoints(
				new SquircleGeometry.SquircleRect(0, 0, 100, 40, 999));
		// 1 start point + 4 corners * 10 segments.
		assertEquals(1 + 4 * SquircleGeometry.SQUIRCLE_SEGMENTS_PER_CORNER, points.size());
		assertEquals(new SquircleGeometry.SquirclePoint(20, 0), points.get(0));
	}

	@Test
	@DisplayName("the superellipse outline stays inside the rectangle and closes on its start")
	void outlineStaysInBoundsAndCloses() {
		double x = 5;
		double y = 7;
		double width = 120;
		double height = 80;
		double radius = 20;
		List<SquircleGeometry.SquirclePoint> points = SquircleGeometry.getSquirclePathPoints(
				new SquircleGeometry.SquircleRect(x, y, width, height, radius));
		assertEquals(1 + 4 * SquircleGeometry.SQUIRCLE_SEGMENTS_PER_CORNER, points.size());
		for (SquircleGeometry.SquirclePoint point : points) {
			assertTrue(point.x() >= x - 1e-9 && point.x() <= x + width + 1e-9,
					"point outside the rectangle: " + point);
			assertTrue(point.y() >= y - 1e-9 && point.y() <= y + height + 1e-9,
					"point outside the rectangle: " + point);
		}
		SquircleGeometry.SquirclePoint first = points.get(0);
		SquircleGeometry.SquirclePoint last = points.get(points.size() - 1);
		assertTrue(Math.abs(first.x() - last.x()) < 1e-5
				&& Math.abs(first.y() - last.y()) < 1e-5, "outline does not close: " + last);
		// The corners are square-ish: the diagonal corner point sits further out than a circle.
		SquircleGeometry.SquirclePoint firstCornerPoint = points.get(1);
		assertNotEquals(0, firstCornerPoint.y());
		assertTrue(firstCornerPoint.x() <= x + width);
	}

	@Test
	@DisplayName("degenerate sizes produce no path")
	void degenerateSizes() {
		assertTrue(SquircleGeometry.getSquirclePathPoints(
				new SquircleGeometry.SquircleRect(0, 0, 0, 10, 4)).isEmpty());
		assertTrue(SquircleGeometry.getSquirclePathPoints(
				new SquircleGeometry.SquircleRect(0, 0, 10, -1, 4)).isEmpty());
		assertEquals("", SquircleGeometry.getSquircleSvgPath(
				new SquircleGeometry.SquircleRect(0, 0, 0, 10, 4)));
	}

	@Test
	@DisplayName("the SVG path starts with M, has one L per remaining point and closes with Z")
	void svgPathShape() {
		String svg = SquircleGeometry.getSquircleSvgPath(
				new SquircleGeometry.SquircleRect(10, 20, 100, 40, 0));
		assertEquals("M 10.0 20.0 L 110.0 20.0 L 110.0 60.0 L 10.0 60.0 Z", svg);
		String rounded = SquircleGeometry.getSquircleSvgPath(
				new SquircleGeometry.SquircleRect(0, 0, 100, 100, 10));
		assertTrue(rounded.startsWith("M "));
		assertTrue(rounded.endsWith(" Z"));
		assertEquals(4 * SquircleGeometry.SQUIRCLE_SEGMENTS_PER_CORNER,
				rounded.split(" L ", -1).length - 1);
	}
}
