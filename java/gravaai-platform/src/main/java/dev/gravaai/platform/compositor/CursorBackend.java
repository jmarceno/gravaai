package dev.gravaai.platform.compositor;

import java.util.Locale;
import java.util.Map;

/**
 * Where the global pointer position comes from during a recording.
 *
 * <p>This is separate from {@link CaptureBackend} because the two do not line up: the
 * xdg-desktop-portal ScreenCast stream is the same on every portal desktop, but no portal reports
 * where the pointer is (the stream records it hidden so the editor can draw its own), so each
 * desktop needs its own pointer source. Every helper prints the same {@code POS:x:y},
 * {@code INTERACTION:...} and {@code KEY:...} lines, so the recorder does not care which one runs.
 *
 * <p>Adding a desktop means adding a constant with its helper and a branch in {@link #detect}.
 * GNOME has none yet; Mutter does fill the ScreenCast stream's {@code SPA_META_Cursor} metadata
 * (cursor_mode 4), which KWin leaves empty, so that is the likely route there.
 */
public enum CursorBackend {

	/** COSMIC's ext-image-copy-capture cursor sessions ({@code gravaai-wayland-cursor}). */
	EXT_IMAGE_COPY("gravaai-wayland-cursor"),

	/** A KWin script that reads {@code workspace.cursorPos} and calls back over D-Bus ({@code gravaai-kwin-cursor}). */
	KWIN_SCRIPT("gravaai-kwin-cursor");

	private final String helper;

	CursorBackend(String helper) {
		this.helper = helper;
	}

	/** The native helper binary that implements this backend. */
	public String helperName() {
		return helper;
	}

	/**
	 * Picks the pointer source for an environment. KDE Plasma uses its scripting engine; everything
	 * else keeps the historical COSMIC helper, which reports a clear error where the compositor
	 * lacks the protocol.
	 *
	 * @param env the process environment ({@code XDG_CURRENT_DESKTOP}, {@code XDG_SESSION_TYPE})
	 */
	public static CursorBackend detect(Map<String, String> env) {
		if (CaptureBackend.detect(env) == CaptureBackend.EXT_IMAGE_COPY) {
			return EXT_IMAGE_COPY;
		}
		String desktop = env.getOrDefault("XDG_CURRENT_DESKTOP", "");
		for (String name : desktop.split(":", -1)) {
			String normalized = name.trim().toLowerCase(Locale.ROOT);
			if (normalized.contains("kde") || normalized.contains("plasma")) {
				return KWIN_SCRIPT;
			}
		}
		return EXT_IMAGE_COPY;
	}
}
