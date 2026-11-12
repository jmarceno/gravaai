package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.services.sources.SelectedSource;

/**
 * Window capture targets: the ids the capture helpers understand, the helper output that lists
 * them, and the source ids the app stores for them.
 *
 * <p>A recording target is either a monitor or a single window. Monitors have ids the helper
 * resolves itself; a window is named by the compositor's own stable toplevel identifier, so a
 * window that is closed and a different one that reuses its title cannot be confused.
 *
 * <p>The wire format is the capture helper's own, which keeps the recorder free of a second
 * translation layer:
 *
 * <ul>
 *   <li>{@code window:<identifier>} is what {@code gravaai-wayland-capture} takes as its first
 *       argument for a window, and what {@code gravaai-wayland-capture --list-windows} reports.
 *   <li>{@code window:portal} is the portal capture target: there the desktop's own dialog is the
 *       picker, so no identifier is known before the user chooses.
 * </ul>
 *
 * <p>Source ids (what {@link SelectedSource#id()} carries) put a {@code screen:} or
 * {@code window:} prefix in front of that, so the UI can tell the two groups apart without
 * knowing the helper's spelling. {@link SelectedSource} is the selection port's record, whose id
 * may be absent, so the id inputs here accept null.
 */
public final class WindowSources {

	/** Source ids for directly captured windows; also the helper's own target prefix. */
	public static final String WINDOW_SOURCE_PREFIX = "window:";

	/** The identifier slot of the portal's "the desktop dialog picks a window" source. */
	private static final String PORTAL_IDENTIFIER = "portal";

	/** The capture helper argument that records whatever window the desktop's picker returns. */
	public static final String PORTAL_WINDOW_TARGET = WINDOW_SOURCE_PREFIX + PORTAL_IDENTIFIER;

	/**
	 * One window the compositor reported: a stable identifier, an app id when the client set one,
	 * and a title (possibly empty).
	 */
	public record CaptureWindow(String identifier, Optional<String> appId, String title) {

		/** A window whose app id is given as text; a blank one means the client set none. */
		public CaptureWindow(String identifier, String appId, String title) {
			this(identifier, appId.isBlank() ? Optional.empty() : Optional.of(appId), title);
		}
	}

	/**
	 * What the capture stream ended up recording, as the helpers report it on their
	 * {@code SOURCE <monitor|window>} line. On a portal desktop the user picks in the desktop's
	 * dialog, so this is the only way the app learns that a "screen" selection became a window.
	 */
	public enum CaptureSurface {
		MONITOR("monitor"),
		WINDOW("window");

		private final String wire;

		CaptureSurface(String wire) {
			this.wire = wire;
		}

		/** The surface a helper's {@code SOURCE} value names; empty for an unknown word. */
		public static Optional<CaptureSurface> parse(String value) {
			for (CaptureSurface surface : values()) {
				if (surface.wire.equals(value)) {
					return Optional.of(surface);
				}
			}
			return Optional.empty();
		}
	}

	private static final Pattern WINDOW_LINE =
			Pattern.compile("^WINDOW\t([^\t]+)\t([^\t]*)\t(.*)$");

	private WindowSources() {
	}

	/**
	 * The capture-helper argument for a selected source.
	 *
	 * <p>A COSMIC screen source keeps its own {@code screen:cosmic:<output>} spelling, an existing
	 * window source keeps its identifier, and anything else falls back to the monitor the helper
	 * should pick itself (the portal's dialog). Returning the empty string asks the helper for
	 * "whatever the portal offers", which is what a portal session has always done.
	 *
	 * @param source the selected source, or null when the selection port has none
	 */
	public static String captureTarget(@Nullable SelectedSource source) {
		if (source == null || source.id() == null) {
			return "";
		}
		String id = source.id();
		if (CosmicSources.isCosmicSourceId(id)) {
			return CosmicSources.outputNameForSourceId(id);
		}
		if (isWindowSourceId(id)) {
			return windowIdentifier(id).map(WindowSources::windowSourceId)
					.orElse(PORTAL_WINDOW_TARGET);
		}
		// The portal sentinel and every other screen source: the desktop's dialog decides.
		return "";
	}

	/** True when a source id names a window rather than a monitor. */
	public static boolean isWindowSourceId(@Nullable String sourceId) {
		return sourceId != null && sourceId.startsWith(WINDOW_SOURCE_PREFIX);
	}

	/**
	 * The window identifier inside a source id; empty for a monitor.
	 *
	 * <p>The portal's synthetic window source has no identifier yet, so it also answers empty: there
	 * is no window to resolve before the user picks one.
	 */
	public static Optional<String> windowIdentifier(@Nullable String sourceId) {
		if (sourceId == null || !isWindowSourceId(sourceId)) {
			return Optional.empty();
		}
		String identifier = sourceId.substring(WINDOW_SOURCE_PREFIX.length());
		return identifier.isEmpty() || identifier.equals(PORTAL_IDENTIFIER)
				? Optional.empty()
				: Optional.of(identifier);
	}

	/** The source id for a window the compositor reported. */
	public static String windowSourceId(String identifier) {
		return WINDOW_SOURCE_PREFIX + identifier;
	}

	/** The source id for the portal's "the user picks a window in the desktop dialog" source. */
	public static String portalWindowSourceId() {
		return PORTAL_WINDOW_TARGET;
	}

	/** Parses the helper's {@code --list-windows} stdout; malformed lines are ignored. */
	public static List<CaptureWindow> parseWindows(String stdout) {
		List<CaptureWindow> windows = new ArrayList<>();
		for (String line : stdout.split("\n", -1)) {
			Matcher match = WINDOW_LINE.matcher(line);
			if (!match.matches()) {
				continue;
			}
			String identifier = match.group(1).trim();
			if (identifier.isEmpty()) {
				continue;
			}
			windows.add(new CaptureWindow(identifier, match.group(2).trim(),
					match.group(3).trim()));
		}
		return windows;
	}

	/**
	 * The label a window is listed under.
	 *
	 * <p>Titles can be empty (an app that has not set one yet) and app ids can be a reverse-DNS
	 * name or missing entirely, so the label falls back to whichever is present instead of
	 * showing a blank row.
	 */
	public static String label(CaptureWindow window) {
		if (!window.title().isBlank()) {
			return window.title();
		}
		return window.appId().filter(app -> !app.isBlank()).orElse(window.identifier());
	}
}
