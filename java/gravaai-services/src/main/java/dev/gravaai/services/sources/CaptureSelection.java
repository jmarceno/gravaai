package dev.gravaai.services.sources;

import java.util.List;
import java.util.Map;

import dev.gravaai.services.recording.CosmicOutput;
import dev.gravaai.services.recording.CosmicSources;

/**
 * Revalidates a candidate capture source against the live system, ported from
 * {@code electron/ipc/captureSelection.ts}.
 *
 * <p>Only the Linux Wayland branches are ported: the Electron {@code desktopCapturer} window list,
 * the macOS ScreenCaptureKit window bounds and the area-source rules for macOS are platform code
 * that the Java app does not have (plan §2.3). The rule that matters is preserved: identity and
 * geometry are revalidated, and a vanished source is never substituted with a broader one.
 */
public final class CaptureSelection {

	/** Lists the outputs of the COSMIC screen-capture helper. */
	public interface CosmicOutputs {
		List<CosmicOutput> list() throws Exception;
	}

	private final CosmicOutputs cosmicOutputs;
	private final Map<String, String> env;

	public CaptureSelection(CosmicOutputs cosmicOutputs, Map<String, String> env) {
		this.cosmicOutputs = cosmicOutputs;
		this.env = env;
	}

	/** True on a Linux Wayland session, where direct COSMIC capture is used. */
	public boolean isWaylandSession() {
		return SourceMapping.isLikelyLinuxWaylandSession(env);
	}

	/** Revalidate identity and geometry; never substitute a broader capture source. */
	public SelectedSource validateCaptureSource(SelectedSource candidate) {
		if (candidate == null || candidate.id() == null || candidate.name() == null) {
			return null;
		}
		if (SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID.equals(candidate.id())) {
			return isWaylandSession()
					? SelectedSource.screen(candidate.id(), "System picker", null)
					: null;
		}
		if (!isWaylandSession()) {
			return null;
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
				return createCosmicScreenSource(output, List.of());
			}
		}
		return null;
	}

	/** Revalidate against a known display list, so the cosmic source carries its display id. */
	public SelectedSource validateCaptureSource(
			SelectedSource candidate,
			List<DisplayOutputResolver.DisplayInfo> displays) {
		if (candidate == null || candidate.id() == null || candidate.name() == null) {
			return null;
		}
		if (SourceMapping.LINUX_PORTAL_SCREEN_SOURCE_ID.equals(candidate.id())) {
			return isWaylandSession()
					? SelectedSource.screen(candidate.id(), "System picker", null)
					: null;
		}
		if (!isWaylandSession() || !CosmicSources.isCosmicSourceId(candidate.id())) {
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
		List<SelectedSource> screens = new java.util.ArrayList<>();
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
}
