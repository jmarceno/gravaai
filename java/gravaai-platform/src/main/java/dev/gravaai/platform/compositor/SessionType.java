package dev.gravaai.platform.compositor;

import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The display-server kind of the current login session.
 *
 * <p>This is the only place {@code XDG_SESSION_TYPE}, {@code WAYLAND_DISPLAY} and {@code DISPLAY}
 * are read. Everything else receives a {@link DesktopSession}, so the session cannot be classified
 * two different ways in two different code paths.
 *
 * <p>The fallback order matches the Electron app this was ported from: the explicit
 * {@code XDG_SESSION_TYPE} wins, then a non-empty {@code WAYLAND_DISPLAY} proves Wayland, and a
 * non-empty {@code DISPLAY} proves X11. Nothing is assumed when none of them is set.
 */
public enum SessionType {

	/** A Wayland compositor session. */
	WAYLAND,

	/** An X11 session (including XWayland on an X11 desktop). */
	X11,

	/** Neither variable is set (CI, a bare container, a stripped-down launcher). */
	UNKNOWN;

	/**
	 * Classifies a process environment.
	 *
	 * @param env the process environment, or an empty map when there is none
	 */
	public static SessionType detect(Map<String, String> env) {
		String declared = trimToNull(env.get("XDG_SESSION_TYPE"));
		if (declared != null) {
			String normalized = declared.toLowerCase(Locale.ROOT);
			if (normalized.equals("wayland")) {
				return WAYLAND;
			}
			if (normalized.equals("x11")) {
				return X11;
			}
		}
		if (trimToNull(env.get("WAYLAND_DISPLAY")) != null) {
			return WAYLAND;
		}
		if (trimToNull(env.get("DISPLAY")) != null) {
			return X11;
		}
		return UNKNOWN;
	}

	private static @Nullable String trimToNull(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
