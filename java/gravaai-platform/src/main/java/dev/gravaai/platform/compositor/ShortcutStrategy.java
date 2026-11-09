package dev.gravaai.platform.compositor;

/**
 * How Gravaai's global shortcut bindings reach a desktop.
 *
 * <p>Each strategy is a different mechanism with different guarantees:
 *
 * <ul>
 *   <li>{@link #COSMIC_CONFIG}: Gravaai splices its entries into COSMIC's RON config; the keys take
 *       effect immediately and COSMIC owns them afterwards.</li>
 *   <li>{@link #PORTAL}: the desktop's {@code org.freedesktop.portal.GlobalShortcuts} interface
 *       registers the triggers and asks the user to confirm once. Whether that portal is actually
 *       running is a runtime capability question, not a desktop-identity question, so
 *       {@code GlobalShortcutsService} probes it and degrades to CLI-only commands when absent.</li>
 * </ul>
 */
public enum ShortcutStrategy {

	/** Splice Gravaai's entries into COSMIC's shortcut config files. */
	COSMIC_CONFIG,

	/** Register through the desktop's GlobalShortcuts portal when it is running. */
	PORTAL
}
