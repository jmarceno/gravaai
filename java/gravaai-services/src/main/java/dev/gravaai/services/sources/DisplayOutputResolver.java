package dev.gravaai.services.sources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.services.recording.CosmicOutput;

/**
 * Resolves COSMIC outputs to JavaFX displays and builds the screen-list entries.
 *
 * <p>The Windows HMONITOR handles of {@code electron/ipc/monitorResolver.ts} are platform code and
 * not ported (plan §2.3); Java uses monitor geometry for the same job (plan §5.5: "Match them by
 * geometry exactly like monitorResolver.ts"). The display ordering, "Screen N" naming and the
 * display-id-or-index source matching mirror {@code electron/ipc/register/sources.ts}, and the
 * output-to-display match mirrors {@code createCosmicScreenSource} in
 * {@code electron/ipc/captureSelection.ts}.
 */
public final class DisplayOutputResolver {

	private DisplayOutputResolver() {
	}

	/** A display as the JavaFX screen API reports it. */
	public record DisplayInfo(int id, CaptureArea bounds) {
	}

	/** A screen source from the source list, before the display match is applied. */
	public record ScreenSourceRef(String id, String displayId, String name) {
	}

	/** One display paired with the screen source the list should use for it. */
	public record ScreenMatch(DisplayInfo display, ScreenSourceRef source) {
	}

	/** Displays sorted by bounds x, bounds y, then id: the order the source list numbers them in. */
	public static List<DisplayInfo> sortDisplays(List<DisplayInfo> displays) {
		List<DisplayInfo> sorted = new ArrayList<>(displays);
		sorted.sort(Comparator
				.comparingDouble((DisplayInfo display) -> display.bounds().x())
				.thenComparingDouble(display -> display.bounds().y())
				.thenComparingInt(DisplayInfo::id));
		return sorted;
	}

	/** "Screen N (Primary)" for the primary display, "Screen N" otherwise, 1-based. */
	public static String screenSourceName(int index, boolean primary) {
		return primary ? "Screen " + (index + 1) + " (Primary)" : "Screen " + (index + 1);
	}

	/**
	 * The display an output sits on. The COSMIC output and the display share the top-left corner,
	 * which is how the original ties a cosmic source to a display id.
	 */
	public static DisplayInfo findDisplayForOutput(CosmicOutput output, List<DisplayInfo> displays) {
		for (DisplayInfo display : displays) {
			if (display.bounds().x() == output.x() && display.bounds().y() == output.y()) {
				return display;
			}
		}
		return null;
	}

	/**
	 * Matches the live screen sources to displays: by display id when Electron reported one, and
	 * by position when the live source count equals the display count. A display without a match
	 * stays unpaired instead of borrowing another screen's source.
	 */
	public static List<ScreenMatch> matchScreenSources(
			List<DisplayInfo> displays,
			List<ScreenSourceRef> liveSources) {
		List<ScreenMatch> matches = new ArrayList<>(displays.size());
		boolean sameCount = liveSources.size() == displays.size();
		for (int index = 0; index < displays.size(); index++) {
			DisplayInfo display = displays.get(index);
			ScreenSourceRef matched = null;
			String displayId = String.valueOf(display.id());
			for (ScreenSourceRef source : liveSources) {
				if (displayId.equals(source.displayId())) {
					matched = source;
					break;
				}
			}
			if (matched == null && sameCount) {
				matched = liveSources.get(index);
			}
			matches.add(new ScreenMatch(display, matched));
		}
		return matches;
	}
}
