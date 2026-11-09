package dev.gravaai.platform.compositor;

/**
 * Everything Gravaai does differently on one desktop environment, in one value.
 *
 * <p>This exists so a desktop's behaviour is one readable line in {@link DesktopEnvironment}, not
 * three unrelated branches in three classes. The three capabilities are deliberately separate
 * because they do not line up: the xdg-desktop-portal ScreenCast stream is the same on every portal
 * desktop, but no portal reports where the pointer is, and shortcut registration has a third,
 * unrelated mechanism.
 *
 * @param capture which screen-capture helper records the session
 * @param cursor which helper reports the global pointer (and keystrokes)
 * @param shortcuts how Gravaai's own shortcut bindings reach the desktop
 */
public record DesktopProfile(
		CaptureBackend capture,
		CursorBackend cursor,
		ShortcutStrategy shortcuts) {

	/**
	 * The profile of a desktop that offers the desktop portal and no pointer bridge: portal
	 * capture, the direct cursor helper (it still reports keystrokes, and its clear
	 * "compositor lacks ext-image-copy-capture" error is the honest answer for the pointer),
	 * and shortcut registration through the portal.
	 */
	public static DesktopProfile portalWithoutPointer() {
		return new DesktopProfile(
				CaptureBackend.PORTAL, CursorBackend.EXT_IMAGE_COPY, ShortcutStrategy.PORTAL);
	}
}
