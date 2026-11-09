package dev.gravaai.platform.compositor;

/**
 * Where the global pointer position comes from during a recording.
 *
 * <p>This is separate from {@link CaptureBackend} because the two do not line up: the
 * xdg-desktop-portal ScreenCast stream is the same on every portal desktop, but no portal reports
 * where the pointer is (the stream records it hidden so the editor can draw its own), so each
 * desktop needs its own pointer source. Every helper prints the same {@code POS:x:y},
 * {@code INTERACTION:...} and {@code KEY:...} lines, so the recorder does not care which one runs.
 *
 * <p>KDE and GNOME each have their own bridge. Every other desktop keeps
 * {@link #EXT_IMAGE_COPY}: on a compositor without {@code ext-image-copy-capture} it prints a clear
 * error for the pointer, but it still delivers keystroke events with {@code --keys}, so key
 * telemetry is not lost just because the pointer source is unavailable.
 *
 * <p>On GNOME the position comes from the gravaai-cursor shell extension, which polls
 * {@code global.get_pointer()} inside gnome-shell and calls {@code gravaai-gnome-cursor} over
 * D-Bus - the same shape as the KWin bridge. Mutter's ScreenCast stream carries the pointer as
 * {@code SPA_META_Cursor} metadata (cursor_mode 4), but on current mutter the metadata never
 * reaches a consumer process (the stream's buffer adapter re-packs without it), so the extension
 * is the working route.
 */
public enum CursorBackend {

	/** COSMIC's ext-image-copy-capture cursor sessions ({@code gravaai-wayland-cursor}). */
	EXT_IMAGE_COPY("gravaai-wayland-cursor"),

	/** A KWin script that reads {@code workspace.cursorPos} and calls back over D-Bus ({@code gravaai-kwin-cursor}). */
	KWIN_SCRIPT("gravaai-kwin-cursor"),

	/** The gravaai-cursor shell extension calling back over D-Bus ({@code gravaai-gnome-cursor}). */
	GNOME_BRIDGE("gravaai-gnome-cursor");

	private final String helper;

	CursorBackend(String helper) {
		this.helper = helper;
	}

	/** The native helper binary that implements this backend. */
	public String helperName() {
		return helper;
	}
}
