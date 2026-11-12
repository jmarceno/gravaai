package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.services.sources.CaptureSelection;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.sources.SourceMapping;

/**
 * The window target vocabulary shared by the capture helpers, the services and the HUD: the ids the
 * helper takes, the lines it lists windows with, and the source ids the app stores.
 */
class WindowSourcesTest {

	@Test
	@DisplayName("lists the compositor's windows, keeping app id and title apart")
	void parsesWindowLines() {
		List<WindowSources.CaptureWindow> windows = WindowSources.parseWindows(
				"WINDOW\t5f1c\torg.gnome.Nautilus\tHome\n"
						+ "WINDOW\t9ab2\tcode\tmain.rs - gravaai\n");

		assertEquals(2, windows.size());
		assertEquals(new WindowSources.CaptureWindow("5f1c", "org.gnome.Nautilus", "Home"),
				windows.get(0));
		assertEquals("main.rs - gravaai", windows.get(1).title());
	}

	@Test
	@DisplayName("tolerates an empty title and a missing app id")
	void parsesIncompleteWindowLines() {
		List<WindowSources.CaptureWindow> windows = WindowSources.parseWindows(
				"WINDOW\t5f1c\torg.gnome.Nautilus\t\nWINDOW\t9ab2\t\tUntitled\n");

		assertEquals(new WindowSources.CaptureWindow("5f1c", "org.gnome.Nautilus", ""), windows.get(0));
		assertNull(windows.get(1).appId());
		assertEquals("Untitled", windows.get(1).title());
	}

	@Test
	@DisplayName("ignores malformed helper output")
	void ignoresMalformedLines() {
		assertTrue(WindowSources.parseWindows(null).isEmpty());
		assertTrue(WindowSources.parseWindows("").isEmpty());
		assertTrue(WindowSources.parseWindows("not a window line\nWINDOW\t\nWINDOW\t\t\t\n").isEmpty());
	}

	@Test
	@DisplayName("a window is named by the compositor's identifier, not its title")
	void buildsWindowSourceIds() {
		WindowSources.CaptureWindow window =
				new WindowSources.CaptureWindow("5f1c", "org.gnome.Nautilus", "Home");
		SelectedSource source = dev.gravaai.services.sources.CaptureSelection.asWindowSource(window);

		assertEquals("window:5f1c", source.id());
		assertEquals("Home", source.name());
		assertEquals("5f1c", WindowSources.windowIdentifier(source.id()));
		assertTrue(WindowSources.isWindowSourceId(source.id()));
	}

	@Test
	@DisplayName("capture target is the helper's own window spelling")
	void mapsWindowSourcesToHelperTargets() {
		assertEquals("window:5f1c", WindowSources.captureTarget(
				dev.gravaai.services.sources.CaptureSelection.asWindowSource(
						new WindowSources.CaptureWindow("5f1c", "code", "main.rs"))));
		assertEquals("", WindowSources.captureTarget(
				SelectedSource.screen(dev.gravaai.services.sources.SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID,
						"System picker", null)));
	}

	@Test
	@DisplayName("the portal's window source asks the dialog instead of naming a window")
	void mapsThePortalWindowSentinel() {
		assertEquals(WindowSources.PORTAL_WINDOW_TARGET, WindowSources.captureTarget(
				dev.gravaai.services.sources.CaptureSelection.portalWindowSource("Share a window…")));
		assertNull(WindowSources.windowIdentifier(WindowSources.portalWindowSourceId()));
		assertFalse(WindowSources.isWindowSourceId(null));
		// "no source" means "let the helper decide", which is the portal's dialog.
		assertEquals("", WindowSources.captureTarget(null));
	}

	@Test
	@DisplayName("a window without a title falls back to its app id or identifier")
	void labelsWindowsWithoutTitles() {
		assertEquals("Home", WindowSources.label(
				new WindowSources.CaptureWindow("5f1c", "org.gnome.Nautilus", "Home")));
		assertEquals("code", WindowSources.label(
				new WindowSources.CaptureWindow("5f1c", "code", "  ")));
		assertEquals("5f1c", WindowSources.label(
				new WindowSources.CaptureWindow("5f1c", null, "")));
	}
}
