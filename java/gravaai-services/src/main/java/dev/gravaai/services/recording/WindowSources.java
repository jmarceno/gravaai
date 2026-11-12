package dev.gravaai.services.recording;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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
 * knowing the helper's spelling.
 */
public final class WindowSources {

	/** The capture helper argument that records whatever window the desktop's picker returns. */
	public static final String PORTAL_WINDOW_TARGET = "window:portal";

	/** Source ids for directly captured windows; also the helper's own target prefix. */
	public static final String WINDOW_SOURCE_PREFIX = "window:";

	/** One window the compositor reported: a stable identifier, an app id and a title. */
	public record CaptureWindow(String identifier, @Nullable String appId, String title) {
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
	 * @param source the selected source, or null when nothing is selected
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
			String identifier = id.substring(WINDOW_SOURCE_PREFIX.length());
			return identifier.isEmpty() ? PORTAL_WINDOW_TARGET : WINDOW_SOURCE_PREFIX + identifier;
		}
		// The portal sentinel and every other screen source: the desktop's dialog decides.
		return "";
	}

	/** True when a source id names a window rather than a monitor. */
	public static boolean isWindowSourceId(@Nullable String sourceId) {
		return sourceId != null && sourceId.startsWith(WINDOW_SOURCE_PREFIX);
	}

	/**
	 * The window identifier inside a source id, or null for a monitor.
	 *
	 * <p>The portal's synthetic window source has no identifier yet, so it also answers null: there
	 * is no window to resolve before the user picks one.
	 */
	public static @Nullable String windowIdentifier(@Nullable String sourceId) {
		if (sourceId == null || !isWindowSourceId(sourceId)) {
			return null;
		}
		String identifier = sourceId.substring(WINDOW_SOURCE_PREFIX.length());
		return identifier.isEmpty() || identifier.equals("portal") ? null : identifier;
	}

	/** The source id for a window the compositor reported. */
	public static String windowSourceId(String identifier) {
		return WINDOW_SOURCE_PREFIX + identifier;
	}

	/** The source id for the portal's "the user picks a window in the desktop dialog" source. */
	public static String portalWindowSourceId() {
		return WINDOW_SOURCE_PREFIX + "portal";
	}

	/** Parses the helper's {@code --list-windows} stdout; malformed lines are ignored. */
	public static List<CaptureWindow> parseWindows(@Nullable String stdout) {
		List<CaptureWindow> windows = new ArrayList<>();
		if (stdout == null) {
			return windows;
		}
		for (String line : stdout.split("\n", -1)) {
			Matcher match = WINDOW_LINE.matcher(line);
			if (!match.matches()) {
				continue;
			}
			String identifier = match.group(1).trim();
			if (identifier.isEmpty()) {
				continue;
			}
			String appId = match.group(2).trim();
			String title = match.group(3).trim();
			windows.add(new CaptureWindow(identifier, appId.isEmpty() ? null : appId, title));
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
		if (window.appId() != null && !window.appId().isBlank()) {
			return window.appId();
		}
		return window.identifier();
	}
}
