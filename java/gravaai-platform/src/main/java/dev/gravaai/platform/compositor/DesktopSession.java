package dev.gravaai.platform.compositor;

import java.util.Map;

/**
 * The classified state of the login session: which display server is running and which desktop
 * environment is on top of it.
 *
 * <p>This is the single entry point for "what kind of session am I in?". The two axes are combined
 * in one value because callers almost always need them together, and because splitting them is how
 * the codebase ended up with four independent parsers of the same three environment variables, two
 * of which disagreed about the same session.
 *
 * @param type the display server ({@link SessionType#WAYLAND}, {@link SessionType#X11} or
 *     {@link SessionType#UNKNOWN})
 * @param environment the desktop environment, or {@link DesktopEnvironment#OTHER} when the name
 *     matched nothing known
 */
public record DesktopSession(SessionType type, DesktopEnvironment environment) {

	/**
	 * An X11 session keeps the historical direct helpers.
	 *
	 * <p>Gravaai has no X11 capture or pointer backend yet, and the direct helpers are the ones
	 * that report a clear error where the compositor lacks their protocol. Selecting the KDE/GNOME
	 * bridges from a desktop name alone would send an X11 session to a helper that expects a
	 * Wayland compositor, so the session type wins over the desktop name here.
	 */
	private static final DesktopProfile X11_HISTORICAL = new DesktopProfile(
			CaptureBackend.EXT_IMAGE_COPY, CursorBackend.EXT_IMAGE_COPY, ShortcutStrategy.PORTAL);

	/**
	 * Classifies a process environment once.
	 *
	 * @param env the process environment
	 * @return the session; never null, even when every variable is missing
	 */
	public static DesktopSession detect(Map<String, String> env) {
		return new DesktopSession(SessionType.detect(env), DesktopEnvironment.detect(env));
	}

	/** Classifies the real process environment (the app's one read of it). */
	public static DesktopSession current() {
		return detect(System.getenv());
	}

	/**
	 * How this session captures, points and registers shortcuts.
	 *
	 * <p>The display server is applied on top of the desktop's own profile: an X11 session keeps
	 * the historical helpers regardless of which desktop runs on it.
	 */
	public DesktopProfile profile() {
		return type == SessionType.X11 ? X11_HISTORICAL : environment.profile();
	}

	/** The capture backend this session needs. */
	public CaptureBackend captureBackend() {
		return profile().capture();
	}

	/** The pointer source this session needs. */
	public CursorBackend cursorBackend() {
		return profile().cursor();
	}

	/** How Gravaai's own shortcut bindings reach this session. */
	public ShortcutStrategy shortcutStrategy() {
		return profile().shortcuts();
	}

	/** True for a Wayland session, the only kind where portal capture applies. */
	public boolean isWayland() {
		return type == SessionType.WAYLAND;
	}

	/** True for an X11 session; {@code false} when the session type is unknown. */
	public boolean isX11() {
		return type == SessionType.X11;
	}
}
