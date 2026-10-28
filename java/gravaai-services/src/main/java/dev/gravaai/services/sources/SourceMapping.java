package dev.gravaai.services.sources;

import java.util.Map;

/**
 * Maps displays to capture source ids, ported from {@code electron/ipc/register/sourceMapping.ts}.
 *
 * <p>On a Linux Wayland session an unmatched screen goes through the xdg-desktop-portal sentinel
 * instead of inventing a Chromium source id.
 */
public final class SourceMapping {

	public static final String LINUX_PORTAL_SCREEN_SOURCE_ID = "screen:linux-portal";

	private SourceMapping() {
	}

	/** True when this is (or looks like) a Wayland session. */
	public static boolean isLikelyLinuxWaylandSession(Map<String, String> env) {
		String sessionType = env.get("XDG_SESSION_TYPE");
		sessionType = sessionType == null ? null : sessionType.trim().toLowerCase();
		if ("wayland".equals(sessionType)) {
			return true;
		}
		if ("x11".equals(sessionType)) {
			return false;
		}
		String waylandDisplay = env.get("WAYLAND_DISPLAY");
		return waylandDisplay != null && !waylandDisplay.isEmpty();
	}

	/**
	 * The source id the window list should use for a display: the live source id when Electron
	 * reported one, the portal sentinel on Linux Wayland, and an explicit fallback id otherwise.
	 */
	public static String getScreenSourceIdForDisplay(
			String displayId,
			Map<String, String> env,
			String matchedSourceId,
			String platform) {
		if (matchedSourceId != null) {
			return matchedSourceId;
		}
		if ("linux".equals(platform) && isLikelyLinuxWaylandSession(env)) {
			return LINUX_PORTAL_SCREEN_SOURCE_ID;
		}
		return "screen:fallback:" + displayId;
	}
}
