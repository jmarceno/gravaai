package dev.gravaai.platform.compositor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which pointer source each desktop environment resolves to. */
class CursorBackendTest {

	private static Map<String, String> wayland(String desktop) {
		return Map.of("XDG_SESSION_TYPE", "wayland", "XDG_CURRENT_DESKTOP", desktop);
	}

	private static CursorBackend cursor(String desktop) {
		return DesktopSession.detect(wayland(desktop)).cursorBackend();
	}

	@Test
	@DisplayName("KDE Plasma reads the pointer through a KWin script")
	void kdeUsesKwinScript() {
		assertEquals(CursorBackend.KWIN_SCRIPT, cursor("KDE"));
		assertEquals(CursorBackend.KWIN_SCRIPT, cursor("KDE:KDE Plasma"));
	}

	@Test
	@DisplayName("COSMIC keeps its ext-image-copy-capture cursor helper")
	void cosmicKeepsItsHelper() {
		assertEquals(CursorBackend.EXT_IMAGE_COPY, cursor("COSMIC"));
		assertEquals(CursorBackend.EXT_IMAGE_COPY, cursor("COSMIC:KDE"));
	}

	@Test
	@DisplayName("GNOME reads the pointer through its shell extension bridge")
	void gnomeUsesShellExtensionBridge() {
		assertEquals(CursorBackend.GNOME_BRIDGE, cursor("GNOME"));
		assertEquals(CursorBackend.GNOME_BRIDGE, cursor("ubuntu:GNOME"));
	}

	@Test
	@DisplayName("an X11 KDE session keeps the historical helper")
	void x11KdeKeepsHistoricalHelper() {
		assertEquals(CursorBackend.EXT_IMAGE_COPY,
				DesktopSession.detect(Map.of("XDG_SESSION_TYPE", "x11", "XDG_CURRENT_DESKTOP", "KDE"))
						.cursorBackend());
	}
}
