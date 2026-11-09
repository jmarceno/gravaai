package dev.gravaai.platform.compositor;

import java.util.Locale;
import java.util.Map;

/**
 * The Linux desktop environments and compositors Gravaai knows how to behave differently on.
 *
 * <p>This enum is the single list of desktop names, and each constant declares its own
 * {@link DesktopProfile} right next to it: which capture mechanism, which pointer source and which
 * shortcut registration strategy that desktop needs. Adding a desktop is one constant with its
 * profile and its {@code XDG_CURRENT_DESKTOP} aliases — not a branch to add to five classes.
 *
 * <p>The name list is intentionally explicit. Each entry needs evidence about the mechanisms it
 * exposes, so a new desktop is a deliberate decision rather than a substring-heuristics accident.
 *
 * <p>{@link #OTHER} is not "unknown metadata"; it is a session whose desktop name matched nothing
 * here. It exists so callers never invent behaviour for an unrecognised name: those sessions keep
 * the behaviour Gravaai had before it knew desktop names — direct COSMIC capture, which reports a
 * clear error on a compositor that lacks the protocol, rather than silently switching to the portal
 * and changing how captures are picked.
 */
public enum DesktopEnvironment {

	/**
	 * COSMIC (System76): direct {@code ext-image-copy-capture} capture, its own cursor session, and
	 * shortcuts written into COSMIC's RON config files.
	 */
	COSMIC(new DesktopProfile(
			CaptureBackend.EXT_IMAGE_COPY, CursorBackend.EXT_IMAGE_COPY, ShortcutStrategy.COSMIC_CONFIG),
			"cosmic"),

	/**
	 * KDE Plasma (KWin): xdg-desktop-portal capture and a KWin script for the pointer.
	 */
	KDE(new DesktopProfile(
			CaptureBackend.PORTAL, CursorBackend.KWIN_SCRIPT, ShortcutStrategy.PORTAL),
			"kde", "plasma"),

	/**
	 * GNOME (Mutter): xdg-desktop-portal capture and the gravaai-cursor shell extension for the
	 * pointer.
	 */
	GNOME(new DesktopProfile(
			CaptureBackend.PORTAL, CursorBackend.GNOME_BRIDGE, ShortcutStrategy.PORTAL),
			"gnome", "unity"),

	/** Sway: wlroots, portal capture, no pointer bridge. */
	SWAY(portalOnly(), "sway"),

	/** Hyprland: wlroots, portal capture, no pointer bridge. */
	HYPRLAND(portalOnly(), "hyprland"),

	/** River: wlroots, portal capture, no pointer bridge. */
	RIVER(portalOnly(), "river"),

	/** Wayfire: wlroots, portal capture, no pointer bridge. */
	WAYFIRE(portalOnly(), "wayfire"),

	/** labwc: wlroots, portal capture, no pointer bridge. */
	LABWC(portalOnly(), "labwc"),

	/** LXQt: portal capture, no pointer bridge. */
	LXQT(portalOnly(), "lxqt"),

	/** XFCE: portal capture, no pointer bridge. */
	XFCE(portalOnly(), "xfce"),

	/** Cinnamon: portal capture, no pointer bridge. */
	CINNAMON(portalOnly(), "cinnamon"),

	/** MATE: portal capture, no pointer bridge. */
	MATE(portalOnly(), "mate"),

	/**
	 * A wlroots-based compositor that only advertises the generic {@code wlroots} token in
	 * {@code XDG_CURRENT_DESKTOP}. Portal capture, no pointer bridge.
	 */
	WLROOTS(portalOnly(), "wlroots"),

	/** No name matched: the historical direct-capture behaviour. */
	OTHER(new DesktopProfile(
			CaptureBackend.EXT_IMAGE_COPY, CursorBackend.EXT_IMAGE_COPY, ShortcutStrategy.PORTAL));

	/**
	 * The profile of a desktop known only through the portal: portal capture, the direct cursor
	 * helper (it still reports keystrokes with {@code --keys}, and its clear "compositor lacks
	 * ext-image-copy-capture" error is the honest answer for the pointer), and shortcut
	 * registration through the desktop's portal when it runs one.
	 *
	 * <p>A method rather than a constant: enum constant arguments cannot reference a static field
	 * declared below them.
	 */
	private static DesktopProfile portalOnly() {
		return new DesktopProfile(
				CaptureBackend.PORTAL, CursorBackend.EXT_IMAGE_COPY, ShortcutStrategy.PORTAL);
	}

	/** The desktops that can be named; {@link #OTHER} has no aliases and is never matched. */
	private static final DesktopEnvironment[] NAMED = {
			KDE, GNOME, SWAY, HYPRLAND, RIVER, WAYFIRE, LABWC, LXQT, XFCE, CINNAMON, MATE, WLROOTS,
	};

	private final DesktopProfile profile;
	private final String[] aliases;

	DesktopEnvironment(DesktopProfile profile, String... aliases) {
		this.profile = profile;
		this.aliases = aliases;
	}

	/** The substrings this desktop advertises in {@code XDG_CURRENT_DESKTOP}. */
	public String[] aliases() {
		return aliases.clone();
	}

	/** How this desktop captures, points and registers shortcuts. */
	public DesktopProfile profile() {
		return profile;
	}

	/**
	 * Classifies {@code XDG_CURRENT_DESKTOP}.
	 *
	 * <p>The variable is a colon-separated list ({@code ubuntu:GNOME}, {@code COSMIC:GNOME}). An entry
	 * is matched by containment, because distributions qualify the name
	 * ({@code KDE:KDE Plasma}). {@link #COSMIC} deliberately decides for the whole list: COSMIC
	 * advertises {@code ext-image-copy-capture} and a {@code COSMIC:GNOME} session is still a COSMIC
	 * session. Every other desktop yields to the first entry that matches, so the order in the
	 * variable — the desktop's own preference order — is preserved.
	 *
	 * @param env the process environment ({@code XDG_CURRENT_DESKTOP})
	 */
	public static DesktopEnvironment detect(Map<String, String> env) {
		String desktop = env.get("XDG_CURRENT_DESKTOP");
		if (desktop == null || desktop.isBlank()) {
			return OTHER;
		}
		String[] names = desktop.split(":", -1);
		for (String name : names) {
			if (matches(name, COSMIC)) {
				return COSMIC;
			}
		}
		for (String name : names) {
			for (DesktopEnvironment environment : NAMED) {
				if (matches(name, environment)) {
					return environment;
				}
			}
		}
		return OTHER;
	}

	private static boolean matches(String name, DesktopEnvironment environment) {
		String normalized = name.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			return false;
		}
		for (String alias : environment.aliases) {
			if (normalized.contains(alias)) {
				return true;
			}
		}
		return false;
	}
}
