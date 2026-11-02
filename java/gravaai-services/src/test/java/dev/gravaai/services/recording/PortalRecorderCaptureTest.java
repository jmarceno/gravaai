package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.platform.compositor.CaptureBackend;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.sources.SourceMapping;

/**
 * The portal path reuses the COSMIC recording pipeline: both helpers take {@code <output> <fps>}
 * and answer with {@code FORMAT <w> <h> <rgba|bgra> <transform>} plus raw frames on stdout. This
 * pins the pieces the portal backend depends on, so a change to either contract fails here rather
 * than on a user's desktop.
 */
class PortalRecorderCaptureTest {

	@Test
	@DisplayName("the portal picker source is not mistaken for a named COSMIC output")
	void portalSourceIdIsNotACosmicOutput() {
		SelectedSource source =
				SelectedSource.screen(SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID, "System picker", null);

		assertFalse(CosmicSources.isCosmicSourceId(source.id()));
		// A foreign id must never be sliced into a bogus output name such as "ortal".
		assertEquals("", CosmicSources.outputNameForSourceId(source.id()));
		assertEquals("", CosmicSources.outputNameForSourceId(null));
	}

	@Test
	@DisplayName("a KDE session resolves the portal backend and its helper name")
	void kdeResolvesThePortalBackend() {
		Map<String, String> env = Map.of(
				"XDG_SESSION_TYPE", "wayland",
				"XDG_CURRENT_DESKTOP", "KDE");

		assertEquals(CaptureBackend.PORTAL, CaptureBackend.detect(env));
		assertEquals("gravaai-portal-capture", CaptureBackend.PORTAL.helperName());
	}

	@Test
	@DisplayName("a COSMIC session is untouched by the portal work")
	void cosmicSessionStillUsesTheDirectHelper() {
		Map<String, String> env = Map.of(
				"XDG_SESSION_TYPE", "wayland",
				"XDG_CURRENT_DESKTOP", "COSMIC");

		assertEquals(CaptureBackend.EXT_IMAGE_COPY, CaptureBackend.detect(env));
		assertEquals("gravaai-wayland-capture", CaptureBackend.EXT_IMAGE_COPY.helperName());
	}
}
