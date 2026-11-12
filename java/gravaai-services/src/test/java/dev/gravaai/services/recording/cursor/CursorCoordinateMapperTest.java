package dev.gravaai.services.recording.cursor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.recording.CursorScreen;
import dev.gravaai.services.recording.WindowSources;
import dev.gravaai.services.sources.CaptureSelection;
import dev.gravaai.services.sources.SelectedSource;

/**
 * The pointer mapping rules for a window recording: the compositor's reading inside the captured
 * surface wins, and a window with no such reading is never normalized against the global pointer,
 * because a Wayland window's global position is not exposed to the app.
 */
class CursorCoordinateMapperTest {

	private static final CursorScreen.Display PRIMARY =
			new CursorScreen.Display(1, 0, 0, 1920, 1080, 1);

	private static final CursorScreen SCREEN = new CursorScreen() {
		@Override
		public Point cursorScreenPoint() {
			return new Point(960, 540);
		}

		@Override
		public List<Display> allDisplays() {
			return List.of(PRIMARY);
		}

		@Override
		public Display primaryDisplay() {
			return PRIMARY;
		}

		@Override
		public @Nullable Display displayNearestPoint(double x, double y) {
			return PRIMARY;
		}
	};

	private static final SelectedSource WINDOW = CaptureSelection.asWindowSource(
			new WindowSources.CaptureWindow("5f1c", "code", "main.rs"));

	private static CursorCoordinateMapper mapper(SelectedSource source) {
		return new CursorCoordinateMapper(SCREEN, () -> source, () -> false);
	}

	@Test
	@DisplayName("the capture stream's reading is used as-is")
	void prefersTheCaptureReading() {
		CursorCoordinateMapper mapper = mapper(WINDOW);
		mapper.recordCapturePoint(0.25, 0.75);

		assertTrue(mapper.hasCapturePoint());
		assertArrayEquals(new double[] {0.25, 0.75}, mapper.normalize(), 1e-9);
	}

	@Test
	@DisplayName("a global pointer reading never becomes a window coordinate")
	void refusesToNormalizeAGlobalReadingAgainstAWindow() {
		CursorCoordinateMapper mapper = mapper(WINDOW);
		mapper.recordPosition(1800, 100);

		// The pointer is in the window, but the mapper cannot know where the window is, so the
		// centre is the only honest answer until the compositor reports a position.
		assertFalse(mapper.hasCapturePoint());
		assertArrayEquals(new double[] {0.5, 0.5}, mapper.normalize(), 1e-9);
	}

	@Test
	@DisplayName("the capture reading survives a still pointer and wins over a later global one")
	void keepsTheCaptureReading() {
		CursorCoordinateMapper mapper = mapper(WINDOW);
		mapper.recordCapturePoint(0.4, 0.6);
		mapper.recordPosition(100, 100);

		assertArrayEquals(new double[] {0.4, 0.6}, mapper.normalize(), 1e-9);
	}

	@Test
	@DisplayName("a new recording does not inherit the previous pointer reading")
	void resetsTheCaptureReading() {
		CursorCoordinateMapper mapper = mapper(WINDOW);
		mapper.recordCapturePoint(0.1, 0.2);
		mapper.reset();

		assertFalse(mapper.hasCapturePoint());
		assertArrayEquals(new double[] {0.5, 0.5}, mapper.normalize(), 1e-9);
	}

	@Test
	@DisplayName("a screen source still normalizes the global pointer against its display")
	void normalizesScreensAgainstTheDisplay() {
		SelectedSource screen = SelectedSource.screen("screen:cosmic:DP-1", "Screen DP-1", "1");
		CursorCoordinateMapper mapper = mapper(screen);
		mapper.recordPosition(480, 270);

		assertArrayEquals(new double[] {0.25, 0.25}, mapper.normalize(), 1e-9);
	}

	@Test
	@DisplayName("a screen selection the portal dialog turned into a window is treated as a window")
	void treatsAReportedWindowSurfaceAsAWindow() {
		SelectedSource portalScreen = SelectedSource.screen(
				dev.gravaai.services.sources.SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID,
				"System picker", null);
		CursorCoordinateMapper mapper = mapper(portalScreen);
		mapper.recordPosition(480, 270);
		mapper.recordCaptureSurface(WindowSources.CaptureSurface.WINDOW);

		assertArrayEquals(new double[] {0.5, 0.5}, mapper.normalize(), 1e-9);

		mapper.reset();
		mapper.recordPosition(480, 270);
		assertArrayEquals(new double[] {0.25, 0.25}, mapper.normalize(), 1e-9);
	}

	@Test
	@DisplayName("out-of-range and non-finite capture readings are ignored")
	void ignoresImpossibleCaptureReadings() {
		CursorCoordinateMapper mapper = mapper(WINDOW);
		mapper.recordCapturePoint(Double.NaN, 0.5);
		mapper.recordCapturePoint(0.5, Double.POSITIVE_INFINITY);
		assertFalse(mapper.hasCapturePoint());

		mapper.recordCapturePoint(-0.5, 1.5);
		assertTrue(mapper.hasCapturePoint());
		assertArrayEquals(new double[] {0.0, 1.0}, mapper.normalize(), 1e-9);
	}

	@Test
	@DisplayName("the portal window sentinel behaves like any other window source")
	void treatsThePortalWindowAsAWindow() {
		SelectedSource portal = CaptureSelection.portalWindowSource("Share a window…");
		CursorCoordinateMapper mapper = mapper(portal);
		mapper.recordPosition(960, 540);

		assertArrayEquals(new double[] {0.5, 0.5}, mapper.normalize(), 1e-9);
	}
}
