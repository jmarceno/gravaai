package dev.gravaai.media.compose;

import java.util.List;

/**
 * Key-press overlay for one compositor frame (plan §5.8 stage 10), media-local like
 * {@link FrameInput}: normalized settings plus the recorded presses in timeline order.
 */
public record KeystrokeSpec(
		boolean enabled,
		String position,
		double fontSize,
		long lingerMs,
		long fadeMs,
		long groupingMs,
		double bottomOffset,
		List<Press> presses) {

	/** One key press: its time and the chip labels (modifiers first, then the key). */
	public record Press(long timeMs, List<String> chipLabels) {
		public Press {
			chipLabels = chipLabels == null ? List.of() : List.copyOf(chipLabels);
		}
	}

	/** Defaults from {@code DEFAULT_KEYSTROKE_OVERLAY_SETTINGS}. */
	public static final KeystrokeSpec DISABLED = new KeystrokeSpec(false, "bottom-center", 34, 900,
			150, 600, 12, List.of());

	public KeystrokeSpec {
		presses = presses == null ? List.of() : List.copyOf(presses);
		position = position == null ? "bottom-center" : position;
	}
}
