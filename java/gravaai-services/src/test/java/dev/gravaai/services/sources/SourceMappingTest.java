package dev.gravaai.services.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from {@code electron/ipc/register/sourceMapping.test.ts}. */
class SourceMappingTest {

	private static final Map<String, String> WAYLAND =
			Map.of("XDG_SESSION_TYPE", "wayland", "WAYLAND_DISPLAY", "wayland-0");
	private static final Map<String, String> X11 = Map.of("XDG_SESSION_TYPE", "x11", "DISPLAY", ":0");

	@Test
	@DisplayName("keeps the live Electron screen source when one is available")
	void keepsTheLiveElectronScreenSource() {
		assertEquals(
				"screen:42:0",
				SourceMapping.getScreenSourceIdForDisplay("42", Map.of(), "screen:42:0", "linux"));
	}

	@Test
	@DisplayName("routes unmatched Linux Wayland screens through the portal sentinel")
	void routesUnmatchedLinuxWaylandScreensThroughThePortalSentinel() {
		assertEquals(
				SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID,
				SourceMapping.getScreenSourceIdForDisplay("42", WAYLAND, null, "linux"));
	}

	@Test
	@DisplayName("keeps unmatched Linux X11 screens on the explicit fallback id")
	void keepsUnmatchedLinuxX11ScreensOnTheExplicitFallbackId() {
		assertEquals(
				"screen:fallback:42",
				SourceMapping.getScreenSourceIdForDisplay("42", X11, null, "linux"));
	}

	@Test
	@DisplayName("keeps non-Linux unmatched screens on the explicit fallback id")
	void keepsNonLinuxUnmatchedScreensOnTheExplicitFallbackId() {
		assertEquals(
				"screen:fallback:42",
				SourceMapping.getScreenSourceIdForDisplay("42", Map.of(), null, "win32"));
	}

	@Test
	@DisplayName("detects Wayland from the session type or the Wayland display")
	void detectsWayland() {
		assertTrue(SourceMapping.isLikelyLinuxWaylandSession(WAYLAND));
		assertTrue(SourceMapping.isLikelyLinuxWaylandSession(Map.of("WAYLAND_DISPLAY", "wayland-1")));
		assertTrue(SourceMapping.isLikelyLinuxWaylandSession(Map.of("XDG_SESSION_TYPE", " Wayland ")));
		assertFalse(SourceMapping.isLikelyLinuxWaylandSession(X11));
		assertFalse(SourceMapping.isLikelyLinuxWaylandSession(Map.of("WAYLAND_DISPLAY", "")));
		assertFalse(SourceMapping.isLikelyLinuxWaylandSession(Map.of()));
	}
}
