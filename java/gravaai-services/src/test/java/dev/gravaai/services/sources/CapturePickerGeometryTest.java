package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.sources.CapturePickerGeometry.AreaHandle;
import dev.gravaai.services.sources.CapturePickerGeometry.AreaRect;
import dev.gravaai.services.sources.CapturePickerGeometry.Bounds;
import dev.gravaai.services.sources.CapturePickerGeometry.RectEntry;

/** Ported from {@code src/components/capture-picker/areaGeometry.test.ts}. */
class CapturePickerGeometryTest {

	private static final Bounds BOUNDS = new Bounds(1000, 800);

	private record Entry(String id, AreaRect rect) implements RectEntry {
	}

	@Test
	@DisplayName("normalizes drags that go up or left")
	void normalizesDragsThatGoUpOrLeft() {
		assertEquals(
				new AreaRect(200, 150, 100, 50),
				CapturePickerGeometry.normalizeAreaRect(new AreaRect(300, 200, -100, -50), BOUNDS));
	}

	@Test
	@DisplayName("clamps rectangles to the overlay")
	void clampsRectanglesToTheOverlay() {
		assertEquals(
				new AreaRect(900, 700, 100, 100),
				CapturePickerGeometry.normalizeAreaRect(new AreaRect(900, 700, 300, 300), BOUNDS));
		assertEquals(
				new AreaRect(800, 0, 200, 200),
				CapturePickerGeometry.moveAreaRect(new AreaRect(100, 100, 200, 200), 2000, -500, BOUNDS));
	}

	@Test
	@DisplayName("resizes from an edge or corner and flips past the opposite edge")
	void resizesFromEdgesAndCorners() {
		AreaRect rect = new AreaRect(100, 100, 200, 100);
		assertEquals(
				new AreaRect(100, 100, 250, 120),
				CapturePickerGeometry.resizeAreaRect(rect, AreaHandle.SE, 50, 20, BOUNDS));
		assertEquals(
				new AreaRect(300, 100, 100, 100),
				CapturePickerGeometry.resizeAreaRect(rect, AreaHandle.W, 300, 0, BOUNDS));
		assertEquals(
				new AreaRect(100, 0, 200, 200),
				CapturePickerGeometry.resizeAreaRect(rect, AreaHandle.N, 0, -150, BOUNDS));
	}

	@Test
	@DisplayName("finds the frontmost window under a point")
	void findsTheFrontmostWindow() {
		Entry back = new Entry("back", new AreaRect(0, 0, 800, 600));
		Entry front = new Entry("front", new AreaRect(100, 100, 200, 200));
		assertEquals("front", CapturePickerGeometry.frontmostAt(List.of(front, back), 150, 150).id());
		assertEquals("back", CapturePickerGeometry.frontmostAt(List.of(front, back), 500, 500).id());
	}

	@Test
	@DisplayName("finds nothing over the desktop")
	void findsNothingOverTheDesktop() {
		Entry back = new Entry("back", new AreaRect(0, 0, 800, 600));
		Entry front = new Entry("front", new AreaRect(100, 100, 200, 200));
		assertNull(CapturePickerGeometry.frontmostAt(List.of(front, back), 900, 700));
	}
}
