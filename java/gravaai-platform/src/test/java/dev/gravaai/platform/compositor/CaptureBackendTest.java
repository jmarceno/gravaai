package dev.gravaai.platform.compositor;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

	@Test
	@DisplayName("COSMIC keeps direct ext-image-copy-capture")
	void cosmicKeepsDirectCapture() {
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, CaptureBackend.detect(wayland("COSMIC")));
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, CaptureBackend.detect(wayland("cosmic")));
	}

	@Test
	@DisplayName("KDE Plasma uses the portal, in both the old and Plasma 6 spellings")
	void kdeUsesPortal() {
		assertEquals(CaptureBackend.PORTAL, CaptureBackend.detect(wayland("KDE")));
		assertEquals(CaptureBackend.PORTAL, CaptureBackend.detect(wayland("KDE:KDE Plasma")));
		assertEquals(CaptureBackend.PORTAL, CaptureBackend.detect(wayland("plasma")));
	}

	@Test
	@DisplayName("GNOME and wlroots compositors use the portal")
	void portalCompositorsUsePortal() {
		assertEquals(CaptureBackend.PORTAL, CaptureBackend.detect(wayland("GNOME")));
		assertEquals(CaptureBackend.PORTAL, CaptureBackend.detect(wayland("ubuntu:GNOME")));
		assertEquals(CaptureBackend.PORTAL, CaptureBackend.detect(wayland("sway")));
		assertEquals(CaptureBackend.PORTAL, CaptureBackend.detect(wayland("Hyprland")));
	}

	@Test
	@DisplayName("an unknown desktop keeps the historical COSMIC backend")
	void unknownDesktopKeepsDirectCapture() {
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, CaptureBackend.detect(wayland("SomethingNew")));
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, CaptureBackend.detect(wayland(null)));
	}

	@Test
	@DisplayName("an X11 session keeps the historical backend")
	void x11KeepsDirectCapture() {
		Map<String, String> env = new HashMap<>();
		env.put("XDG_SESSION_TYPE", "x11");
		env.put("XDG_CURRENT_DESKTOP", "KDE");
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, CaptureBackend.detect(env));
	}

	@Test
	@DisplayName("a COSMIC entry wins over a portal-ish one in a colon list")
	void cosmicEntryWins() {
		assertEquals(CaptureBackend.EXT_IMAGE_COPY, CaptureBackend.detect(wayland("COSMIC:GNOME")));
	}

	@Test
	@DisplayName("helpers are named after the existing convention")
	void helperNames() {
		assertEquals("gravaai-wayland-capture", CaptureBackend.EXT_IMAGE_COPY.helperName());
		assertEquals("gravaai-portal-capture", CaptureBackend.PORTAL.helperName());
	}
}
