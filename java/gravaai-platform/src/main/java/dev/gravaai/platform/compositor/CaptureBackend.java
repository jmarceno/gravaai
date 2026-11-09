package dev.gravaai.platform.compositor;

/**
 * Which screen-capture backend the current Wayland compositor needs.
 *
 * <p>COSMIC (and other compositors that expose {@code ext-image-copy-capture}) are captured
 * directly through {@code gravaai-wayland-capture}. Everything else — KDE Plasma/KWin, GNOME,
 * wlroots — only offers the {@code xdg-desktop-portal} ScreenCast interface, so those sessions use
 * {@code gravaai-portal-capture}. Both helpers speak the same command line and the same
 * {@code FORMAT} + raw-frame stdout contract, so the recording pipeline is identical either way.
 *
 * <p>Which backend a session gets is decided by {@link DesktopEnvironment}'s profile; this enum
 * only names the mechanisms.
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
}
