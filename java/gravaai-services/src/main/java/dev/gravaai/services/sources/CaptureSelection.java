package dev.gravaai.services.sources;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.services.recording.CosmicOutput;
import dev.gravaai.services.recording.CosmicSources;
import dev.gravaai.services.recording.WindowSources;

/**
 * Revalidates a candidate capture source against the live system, ported from
 * {@code electron/ipc/captureSelection.ts}.
 *
 * <p>Only the Linux Wayland branches are ported: the Electron {@code desktopCapturer} window list
 * and the macOS ScreenCaptureKit window bounds are platform code that the Java app does not have
 * (plan §2.3). The rule that matters is preserved: identity and geometry are revalidated, and a
 * vanished source is never substituted with a broader one. A window source is revalidated the same
 * way: its identifier must still be in the compositor's toplevel list, so recording a window that
 * closed in the meantime fails instead of capturing something else.
 */
public final class CaptureSelection {

	/** Lists the outputs of the COSMIC screen-capture helper. */
	public interface CosmicOutputs {
		List<CosmicOutput> list() throws Exception;
	}

	/** Lists the windows the capture helper can record. */
	public interface CaptureWindows {
		List<WindowSources.CaptureWindow> list() throws Exception;
	}

	private final CosmicOutputs cosmicOutputs;
	private final DesktopSession session;
	private final CaptureWindows captureWindows;

	public CaptureSelection(CosmicOutputs cosmicOutputs, DesktopSession session) {
		this(cosmicOutputs, session, () -> List.of());
	}

	/**
	 * @param cosmicOutputs the monitor list of the capture helper
	 * @param session how the app classifies this session
	 * @param captureWindows the window list of the capture helper
	 */
	public CaptureSelection(CosmicOutputs cosmicOutputs, DesktopSession session,
			CaptureWindows captureWindows) {
		this.cosmicOutputs = cosmicOutputs;
		this.session = session;
		this.captureWindows = captureWindows;
	}

	/** True on a Wayland session, where portal or direct COSMIC capture is used. */
	public boolean isWaylandSession() {
		return session.isWayland();
	}

	/** Revalidate identity and geometry; never substitute a broader capture source. */
	public @Nullable SelectedSource validateCaptureSource(SelectedSource candidate) {
		return validateCaptureSource(candidate, List.of());
	}

	/** Revalidate against a known display list, so the cosmic source carries its display id. */
	public @Nullable SelectedSource validateCaptureSource(
			SelectedSource candidate,
			List<DisplayOutputResolver.DisplayInfo> displays) {
		if (candidate == null || candidate.id() == null || candidate.name() == null) {
			return null;
		}
		if (!isWaylandSession()) {
			return null;
		}
		if (SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID.equals(candidate.id())) {
			return SelectedSource.screen(candidate.id(), "System picker", null);
		}
		if (WindowSources.isWindowSourceId(candidate.id())) {
			return validateWindowSource(candidate);
		}
		if (!CosmicSources.isCosmicSourceId(candidate.id())) {
			return null;
		}
		String outputName = CosmicSources.outputNameForSourceId(candidate.id());
		List<CosmicOutput> outputs;
		try {
			outputs = cosmicOutputs.list();
		} catch (Exception error) {
			outputs = List.of();
		}
		for (CosmicOutput output : outputs) {
			if (output.name().equals(outputName)) {
				return createCosmicScreenSource(output, displays);
			}
		}
		return null;
	}

	/** Every COSMIC output as a screen source, for the HUD monitor list (empty off Wayland). */
	public List<SelectedSource> listCosmicScreenSources(List<DisplayOutputResolver.DisplayInfo> displays) {
		if (!isWaylandSession()) {
			return List.of();
		}
		List<CosmicOutput> outputs;
		try {
			outputs = cosmicOutputs.list();
		} catch (Exception error) {
			return List.of();
		}
		List<SelectedSource> screens = new ArrayList<>();
		for (CosmicOutput output : outputs) {
			screens.add(createCosmicScreenSource(output, displays));
		}
		return screens;
	}

	/** A COSMIC output as a selected source, tied to the display at the same spot. */
	public static SelectedSource createCosmicScreenSource(
			CosmicOutput output,
			List<DisplayOutputResolver.DisplayInfo> displays) {
		DisplayOutputResolver.DisplayInfo display =
				DisplayOutputResolver.findDisplayForOutput(output, displays);
		return new SelectedSource(
				CosmicSources.COSMIC_SOURCE_PREFIX + output.name(),
				"Screen " + output.name() + " (" + CaptureArea.number(output.width()) + "×"
						+ CaptureArea.number(output.height()) + ")",
				display == null ? null : String.valueOf(display.id()),
				SourceType.SCREEN,
				null,
				null,
				null,
				null);
	}

	/**
	 * Every window the compositor reports, as a selectable source (empty when the session cannot
	 * capture windows directly).
	 *
	 * <p>On a portal compositor the list is empty on purpose: the desktop's own dialog is the
	 * picker there, so offering a list would mean showing windows the app cannot verify are still
	 * mapped.
	 */
	public List<SelectedSource> listCaptureWindowSources() {
		if (!isWaylandSession()) {
			return List.of();
		}
		List<WindowSources.CaptureWindow> windows;
		try {
			windows = captureWindows.list();
		} catch (Exception error) {
			return List.of();
		}
		List<SelectedSource> sources = new ArrayList<>();
		for (WindowSources.CaptureWindow window : windows) {
			sources.add(asWindowSource(window));
		}
		return sources;
	}

	/**
	 * Revalidates one window source against the compositor's live window list.
	 *
	 * <p>An identifier that is no longer mapped means the window was closed, so it is refused
	 * rather than substituted — the same rule the monitor path follows.
	 */
	private @Nullable SelectedSource validateWindowSource(SelectedSource candidate) {
		String identifier = WindowSources.windowIdentifier(candidate.id());
		if (identifier == null) {
			// The portal's "the user picks a window" source has no identifier to check, and no
			// list to check it against: the dialog decides at capture time.
			return candidate.sourceType() == SourceType.WINDOW
					? SelectedSource.window(candidate.id(), candidate.name(), candidate.displayId(),
							candidate.appName(), candidate.windowTitle() == null ? "" : candidate.windowTitle())
					: null;
		}
		List<WindowSources.CaptureWindow> windows;
		try {
			windows = captureWindows.list();
		} catch (Exception error) {
			return null;
		}
		for (WindowSources.CaptureWindow window : windows) {
			if (window.identifier().equals(identifier)) {
				return asWindowSource(window);
			}
		}
		return null;
	}

	/** One compositor window as a selected source. */
	public static SelectedSource asWindowSource(WindowSources.CaptureWindow window) {
		return SelectedSource.window(
				WindowSources.windowSourceId(window.identifier()),
				WindowSources.label(window),
				null,
				window.appId(),
				window.title());
	}

	/** The portal's "share a window" source: the desktop's dialog is the picker. */
	public static SelectedSource portalWindowSource(String label) {
		return SelectedSource.window(WindowSources.portalWindowSourceId(), label, null, null, label);
	}
}
