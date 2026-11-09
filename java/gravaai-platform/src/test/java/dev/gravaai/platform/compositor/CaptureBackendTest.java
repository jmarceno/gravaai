package dev.gravaai.platform.compositor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which capture backend each desktop environment resolves to. */
class CaptureBackendTest {

	private static Map<String, String> wayland(String desktop) {
		Map<String, String> env = new HashMap<>();
		env.put("XDG_SESSION_TYPE", "wayland");
		if (desktop != null) {
			env.put("XDG_CURRENT_DESKTOP", desktop);
		}
		return env;
	}

	private static CaptureBackend capture(String desktop) {
		return DesktopSession.detect(wayland(desktop)).captureBackend();
	}

	@Test
	@DisplayName("COSMIC keeps direct ext-image-copy-capture")
	void cosmicKeepsDirectCapture() {
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, capture("COSMIC"));
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, capture("cosmic"));
	}

	@Test
	@DisplayName("KDE Plasma uses the portal, in both the old and Plasma 6 spellings")
	void kdeUsesPortal() {
		assertEquals(CaptureBackend.PORTAL, capture("KDE"));
		assertEquals(CaptureBackend.PORTAL, capture("KDE:KDE Plasma"));
		assertEquals(CaptureBackend.PORTAL, capture("plasma"));
	}

	@Test
	@DisplayName("GNOME and wlroots compositors use the portal")
	void portalCompositorsUsePortal() {
		assertEquals(CaptureBackend.PORTAL, capture("GNOME"));
		assertEquals(CaptureBackend.PORTAL, capture("ubuntu:GNOME"));
		assertEquals(CaptureBackend.PORTAL, capture("sway"));
		assertEquals(CaptureBackend.PORTAL, capture("Hyprland"));
		assertEquals(CaptureBackend.PORTAL, capture("river"));
		assertEquals(CaptureBackend.PORTAL, capture("wayfire"));
		assertEquals(CaptureBackend.PORTAL, capture("labwc"));
		assertEquals(CaptureBackend.PORTAL, capture("LXQt"));
		assertEquals(CaptureBackend.PORTAL, capture("XFCE"));
		assertEquals(CaptureBackend.PORTAL, capture("X-Cinnamon"));
		assertEquals(CaptureBackend.PORTAL, capture("MATE"));
	}

	@Test
	@DisplayName("an unknown desktop keeps the historical COSMIC backend")
	void unknownDesktopKeepsDirectCapture() {
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, capture("SomethingNew"));
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, capture(null));
	}

	@Test
	@DisplayName("an X11 session keeps the historical backend")
	void x11KeepsDirectCapture() {
		Map<String, String> env = new HashMap<>();
		env.put("XDG_SESSION_TYPE", "x11");
		env.put("XDG_CURRENT_DESKTOP", "KDE");
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, DesktopSession.detect(env).captureBackend());
	}

	@Test
	@DisplayName("a COSMIC entry wins over a portal-ish one in a colon list")
	void cosmicEntryWins() {
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, capture("COSMIC:GNOME"));
	}

	@Test
	@DisplayName("the first named entry decides, so the desktop's own order is respected")
	void firstNamedEntryDecides() {
		assertEquals(DesktopEnvironment.KDE, DesktopEnvironment.detect(wayland("KDE:GNOME")));
		assertEquals(DesktopEnvironment.GNOME, DesktopEnvironment.detect(wayland("GNOME:KDE")));
	}

	@Test
	@DisplayName("helpers are named after the existing convention")
	void helperNames() {
		assertEquals("gravaai-wayland-capture", CaptureBackend.EXT_IMAGE_COPY.helperName());
		assertEquals("gravaai-portal-capture", CaptureBackend.PORTAL.helperName());
	}

	@Test
	@DisplayName("every named desktop is matched by its own aliases")
	void everyNamedDesktopMatchesItsAliases() {
		for (DesktopEnvironment environment : DesktopEnvironment.values()) {
			if (environment == DesktopEnvironment.OTHER) {
				assertEquals(0, environment.aliases().length, "OTHER must not be nameable");
				continue;
			}
			assertTrue(environment.aliases().length > 0, environment + " has no aliases");
			for (String alias : environment.aliases()) {
				assertEquals(environment, DesktopEnvironment.detect(wayland(alias)),
						"alias '" + alias + "' must resolve to " + environment);
			}
		}
	}

	@Test
	@DisplayName("a portal desktop without a pointer bridge still keeps the cursor helper")
	void portalDesktopsKeepTheCursorHelper() {
		DesktopSession sway = DesktopSession.detect(wayland("sway"));
		assertEquals(CaptureBackend.PORTAL, sway.captureBackend());
		// The helper still delivers --keys telemetry on a compositor without the cursor protocol.
		assertEquals(CursorBackend.EXT_IMAGE_COPY, sway.cursorBackend());
		assertEquals(ShortcutStrategy.PORTAL, sway.shortcutStrategy());
		assertTrue(sway.isWayland());
		assertFalse(sway.isX11());
	}
}
