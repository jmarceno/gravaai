package dev.gravaai.platform.compositor;

/**
 * How a full-screen Gravaai overlay (the live ink overlay) stays above the windows it covers.
 *
 * <p>JavaFX runs on XWayland, and window managers disagree about what an XWayland window may ask
 * for. This is a separate axis of {@link DesktopProfile} because it does not follow capture,
 * pointer or shortcut support: COSMIC captures directly yet ignores keep-above, while KWin and
 * Mutter capture through the portal and (for X11 clients) honour it.
 *
 * <p>Which mechanism a session gets is decided by {@link DesktopEnvironment}'s profile; this enum
 * only names the mechanisms. Reading the overlay's real bounds back after it is shown is not a
 * desktop difference (any window manager may clamp a window), so it is not modelled here.
 */
public enum OverlayStacking {
	/**
	 * The window manager ignores {@code _NET_WM_STATE_ABOVE} for XWayland windows, so the overlay
	 * raises itself again ({@code toFront} + {@code requestFocus}) whenever it loses focus.
	 * Verified on COSMIC (2026-11-13 spike): a newly mapped overlay is on top, a later window
	 * covers it, and re-raising brings it back with focus.
	 */
	REFRONT_ON_FOCUS_LOSS,
	/**
	 * The window manager honours {@code _NET_WM_STATE_ABOVE}: the overlay asks for it once, and
	 * still re-raises on focus loss as a backstop. Not yet verified on a real desktop; the
	 * KDE/GNOME pass may move a desktop to another value.
	 */
	KEEP_ABOVE
}
