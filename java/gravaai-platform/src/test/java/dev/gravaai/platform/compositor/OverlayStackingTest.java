package dev.gravaai.platform.compositor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How each desktop keeps a full-screen overlay above other windows. */
class OverlayStackingTest {

	private static OverlayStacking stacking(String type, String desktop) {
		return DesktopSession.detect(Map.of("XDG_SESSION_TYPE", type, "XDG_CURRENT_DESKTOP", desktop))
				.overlayStacking();
	}

	@Test
	@DisplayName("COSMIC ignores keep-above, so the overlay re-raises itself on focus loss")
	void cosmicRefronts() {
		assertEquals(OverlayStacking.REFRONT_ON_FOCUS_LOSS, stacking("wayland", "COSMIC"));
		assertEquals(OverlayStacking.REFRONT_ON_FOCUS_LOSS, stacking("wayland", "COSMIC:GNOME"));
	}

	@Test
	@DisplayName("KDE and GNOME ask the window manager for keep-above")
	void kdeAndGnomeKeepAbove() {
		assertEquals(OverlayStacking.KEEP_ABOVE, stacking("wayland", "KDE"));
		assertEquals(OverlayStacking.KEEP_ABOVE, stacking("wayland", "ubuntu:GNOME"));
	}

	@Test
	@DisplayName("an X11 session asks for keep-above whatever the desktop")
	void x11KeepsAbove() {
		assertEquals(OverlayStacking.KEEP_ABOVE, stacking("x11", "COSMIC"));
	}
}
