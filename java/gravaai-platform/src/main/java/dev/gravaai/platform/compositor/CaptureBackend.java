package dev.gravaai.platform.compositor;

import java.util.Locale;
import java.util.Map;

/**
 * Which screen-capture backend the current Wayland compositor needs.
 *
 * <p>COSMIC (and other compositors that expose {@code ext-image-copy-capture}) are captured
 * directly through {@code gravaai-wayland-capture}. Everything else — KDE Plasma/KWin, GNOME,
 * wlroots — only offers the {@code xdg-desktop-portal} ScreenCast interface, so those sessions use
 * {@code gravaai-portal-capture}. Both helpers speak the same command line and the same
 * {@code FORMAT} + raw-frame stdout contract, so the recording pipeline is identical either way.
 */
public enum CaptureBackend {

	/** Direct compositor capture over {@code ext-image-copy-capture}. */
	EXT_IMAGE_COPY("gravaai-wayland-capture"),

	/** {@code xdg-desktop-portal} ScreenCast into PipeWire. */
	PORTAL("gravaai-portal-capture");

	private final String helper;

	CaptureBackend(String helper) {
		this.helper = helper;
	}

	/** The native helper binary that implements this backend. */
	public String helperName() {
		return helper;
	}

	/**
	 * Picks the backend for an environment, preferring the compositor's own name.
	 *
	 * <p>The portal helper is used when the desktop is a known portal-only compositor. Anything
	 * unrecognised keeps the historical COSMIC behaviour so existing sessions cannot regress: on
	 * such a compositor the COSMIC helper reports a clear error, whereas silently switching to the
	 * portal would change how captures are picked.
	 *
	 * @param env the process environment ({@code XDG_CURRENT_DESKTOP}, {@code XDG_SESSION_TYPE})
	 */
	public static CaptureBackend detect(Map<String, String> env) {
		String session = env.get("XDG_SESSION_TYPE");
		if (session != null && !session.equalsIgnoreCase("wayland")) {
			return EXT_IMAGE_COPY;
		}
		String desktop = env.get("XDG_CURRENT_DESKTOP");
		if (desktop == null) {
			return EXT_IMAGE_COPY;
		}
		String[] names = desktop.split(":", -1);
		// A COSMIC entry decides for the whole list: COSMIC advertises ext-image-copy-capture, and
		// a session such as "COSMIC:GNOME" is still a COSMIC session.
		for (String name : names) {
			if (name.trim().toLowerCase(Locale.ROOT).contains("cosmic")) {
				return EXT_IMAGE_COPY;
			}
		}
		for (String name : names) {
			String normalized = name.trim().toLowerCase(Locale.ROOT);
			if (normalized.isEmpty()) {
				continue;
			}
			// KDE: XDG_CURRENT_DESKTOP is "KDE" (older releases) or "KDE:KDE Plasma" (Plasma 6).
			if (normalized.contains("kde") || normalized.contains("plasma")) {
				return PORTAL;
			}
			// GNOME and wlroots-based desktops have no ext-image-copy-capture.
			if (normalized.contains("gnome") || normalized.contains("unity")
					|| normalized.contains("sway") || normalized.contains("wlroots")
					|| normalized.contains("hyprland") || normalized.contains("river")
					|| normalized.contains("wayfire") || normalized.contains("labwc")
					|| normalized.contains("lxqt") || normalized.contains("xfce")
					|| normalized.contains("cinnamon") || normalized.contains("mate")) {
				return PORTAL;
			}
		}
		return EXT_IMAGE_COPY;
	}
}
