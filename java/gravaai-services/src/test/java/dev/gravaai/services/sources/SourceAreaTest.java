package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from {@code electron/ipc/sourceArea.test.ts}. */
class SourceAreaTest {

	private static final List<SourceArea.PickerDisplay> DISPLAYS = List.of(
			new SourceArea.PickerDisplay(1, new CaptureArea(0, 0, 1728, 1117)),
			new SourceArea.PickerDisplay(2, new CaptureArea(1728, -200, 2560, 1440)));

	private static final List<CapturePickerWindow> WINDOWS = List.of(
			new CapturePickerWindow("window:42:0", "Safari", "Docs", "1", 10, 40, 1200, 800));

	private static CapturePickRequest area(Double x, Double y, Double width, Double height, Integer displayId) {
		return new CapturePickRequest("area", x, y, width, height, null, displayId, false);
	}

	@Test
	@DisplayName("keeps an area that lies on its display")
	void keepsAnAreaThatLiesOnItsDisplay() {
		assertEquals(
				new CapturePick.Area(100, 50, 1280, 720, 1, true),
				SourceArea.normalizeCapturePick(
						new CapturePickRequest("area", 100.4, 50.0, 1280.0, 720.0, null, 1, true),
						DISPLAYS,
						WINDOWS));
	}

	@Test
	@DisplayName("clips an area to its display, including displays left of or above the origin")
	void clipsAnAreaToItsDisplay() {
		assertEquals(
				new CapturePick.Area(1728, -200, 372, 300, 2, false),
				SourceArea.normalizeCapturePick(area(1700.0, -300.0, 400.0, 400.0, 2), DISPLAYS, WINDOWS));
	}

	@Test
	@DisplayName("trims an area to an even size so it maps onto whole screen pixels")
	void trimsAnAreaToAnEvenSize() {
		CapturePick pick = SourceArea.normalizeCapturePick(
				area(10.0, 20.0, 401.0, 301.0, 1), DISPLAYS, WINDOWS);
		assertEquals(new CapturePick.Area(10, 20, 400, 300, 1, false), pick);
	}

	@Test
	@DisplayName("rejects areas that are too small, unknown displays and malformed input")
	void rejectsInvalidAreas() {
		CapturePickRequest small = area(0.0, 0.0, 20.0, 400.0, 1);
		assertNull(SourceArea.normalizeCapturePick(small, DISPLAYS, WINDOWS));
		assertNull(SourceArea.normalizeCapturePick(
				new CapturePickRequest("area", 0.0, 0.0, 400.0, 400.0, null, 9, false), DISPLAYS, WINDOWS));
		assertNull(SourceArea.normalizeCapturePick(
				new CapturePickRequest("area", 0.0, 0.0, null, 400.0, null, 1, false), DISPLAYS, WINDOWS));
		assertNull(SourceArea.normalizeCapturePick(
				new CapturePickRequest("region", 0.0, 0.0, 400.0, 400.0, null, 1, false), DISPLAYS, WINDOWS));
		assertNull(SourceArea.normalizeCapturePick(null, DISPLAYS, WINDOWS));
	}

	@Test
	@DisplayName("accepts only windows the picker offered")
	void acceptsOnlyWindowsThePickerOffered() {
		assertEquals(
				new CapturePick.Window("window:42:0", 1, true),
				SourceArea.normalizeCapturePick(
						new CapturePickRequest("window", null, null, null, null, "window:42:0", 1, true),
						DISPLAYS,
						WINDOWS));
		assertNull(SourceArea.normalizeCapturePick(
				new CapturePickRequest("window", null, null, null, null, "window:7:0", 1, false),
				DISPLAYS,
				WINDOWS));
	}

	@Test
	@DisplayName("accepts a connected screen")
	void acceptsAConnectedScreen() {
		assertEquals(
				new CapturePick.Screen(2, false),
				SourceArea.normalizeCapturePick(
						new CapturePickRequest("screen", null, null, null, null, null, 2, false),
						DISPLAYS,
						WINDOWS));
	}

	@Test
	@DisplayName("round-trips an area's rectangle")
	void roundTripsAnAreaRectangle() {
		SelectedSource source = SourceArea.createAreaSource(
				new CapturePick.Area(10, 20, 640, 480, 1, false));
		assertEquals("area:1", source.id());
		assertEquals("Area 640×480", source.name());
		assertEquals("1", source.displayId());
		assertEquals(SourceType.AREA, source.sourceType());
		assertEquals(new CaptureArea(10, 20, 640, 480), SourceArea.getSourceArea(source));
	}

	@Test
	@DisplayName("builds the same window source the window list offers")
	void buildsTheSameWindowSource() {
		assertEquals(
				new SelectedSource(
						"window:42:0", "Docs", "1", SourceType.WINDOW, "Safari", "Docs", null, null),
				SourceArea.createWindowSource(WINDOWS.get(0)));
	}

	@Test
	@DisplayName("ignores sources that are not areas")
	void ignoresSourcesThatAreNotAreas() {
		assertNull(SourceArea.getSourceArea(SelectedSource.screen("screen:1:0", "Screen", null)));
		assertNull(SourceArea.getSourceArea(null));
	}
}
