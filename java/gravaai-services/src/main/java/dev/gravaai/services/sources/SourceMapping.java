package dev.gravaai.services.sources;

/**
 * The source id the portal-backed capture path uses.
 *
 * <p>The live app records either a portal screen (one synthetic source whose real target the
 * desktop's own dialog picks) or a direct COSMIC output ({@code screen:cosmic:<output>}). The
 * Electron-era display-to-source mapping that used to live here had no callers once the overlay
 * picker went, so it was removed rather than left as a second, unused answer.
 */
public final class SourceMapping {

	/** The synthetic screen source that makes the desktop's screen-sharing dialog the picker. */
	public static final String LINUX_PORTAL_SCREEN_SOURCE_ID = "screen:linux-portal";

	private SourceMapping() {
	}
}
