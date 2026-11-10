package dev.gravaai.ui.editor.timeline.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.gravaai.core.project.AudioCleanup;

/**
 * The timeline's cleanup indicator for a source-audio lane. The decision lives here (and is tested)
 * rather than in {@code TimelineView}, so the badge text cannot drift between the lane header, the
 * block pill and the inspector's summary.
 */
public final class SourceAudioCleanupBadge {

	/** The pill drawn on every source-audio block of a cleaned track. */
	public static final String BLOCK_BADGE = "CLEAN";

	/** The short marker drawn in the lane header next to the track name. */
	public static final String LANE_MARKER = "NR";

	private SourceAudioCleanupBadge() {}

	/** True when the lane carries cleanup and must show the indicator. */
	public static boolean visible(AudioCleanup cleanup) {
		return cleanup != null && cleanup.isActive();
	}

	/** The block pill text, or the empty string when the track has no cleanup. */
	public static String blockBadge(AudioCleanup cleanup) {
		return visible(cleanup) ? BLOCK_BADGE : "";
	}

	/** The lane-header marker, or the empty string when the track has no cleanup. */
	public static String laneMarker(AudioCleanup cleanup) {
		return visible(cleanup) ? LANE_MARKER : "";
	}

	/**
	 * The full description of the active stages, e.g. {@code "NR balanced · HP 120 Hz · -16 LUFS"};
	 * the empty string when nothing is on. Used as the lane tooltip/accessibility text.
	 */
	public static String describe(AudioCleanup cleanup) {
		if (!visible(cleanup)) {
			return "";
		}
		List<String> parts = new ArrayList<>(3);
		String noise = switch (cleanup.noiseReduction()) {
			case OFF -> "";
			case LIGHT -> "NR light";
			case BALANCED -> "NR balanced";
			case STRONG -> "NR strong";
		};
		if (!noise.isEmpty()) {
			parts.add(noise);
		}
		if (cleanup.hasHighPass()) {
			parts.add("HP " + cleanup.highPassHz() + " Hz");
		}
		if (cleanup.hasLoudnessTarget()) {
			parts.add(String.format(Locale.ROOT, "%.0f LUFS", cleanup.loudnessTargetLufs()));
		}
		return String.join(" · ", parts);
	}
}
