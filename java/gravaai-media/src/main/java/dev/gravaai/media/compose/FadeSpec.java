package dev.gravaai.media.compose;

import java.util.List;

/**
 * Fade-through-black clip transitions for one compositor frame (plan §5.8 stage 11), port of
 * {@code getFadeBlackAmount}/{@code buildFadeWindows} in {@code clipTransitions.ts}.
 *
 * <p>The windows are media-local copies of the project's resolved fade windows (a dip to black at
 * each clip cut, stretching across a timeline gap). The compositor paints a full-frame black
 * overlay with {@link #amountAt(double)} alpha after every other stage, so deletes/captions/
 * keystrokes all fade with the picture.
 */
public record FadeSpec(List<Window> windows) {

	/** One dip-to-black window in timeline milliseconds. */
	public record Window(double startMs, double peakMs, Double holdUntilMs, double endMs) {
	}

	public static final FadeSpec NONE = new FadeSpec(List.of());

	public FadeSpec {
		windows = windows == null ? List.of() : List.copyOf(windows);
	}

	/** {@code getFadeWindowHoldEnd}. */
	public static double holdEnd(Window window) {
		return window.holdUntilMs() != null ? window.holdUntilMs() : window.peakMs();
	}

	private static double smoothstep(double value) {
		double t = Math.min(1, Math.max(0, value));
		return t * t * (3 - 2 * t);
	}

	/** How black the frame is at {@code timeMs}: 0 untouched, 1 fully black. */
	public double amountAt(double timeMs) {
		double amount = 0;
		for (Window window : windows) {
			if (timeMs < window.startMs() || timeMs > window.endMs()) {
				continue;
			}
			double holdEndMs = holdEnd(window);
			double progress;
			if (timeMs <= window.peakMs()) {
				progress = window.peakMs() > window.startMs()
						? (timeMs - window.startMs()) / (window.peakMs() - window.startMs())
						: 1;
			} else if (timeMs <= holdEndMs) {
				progress = 1;
			} else if (window.endMs() > holdEndMs) {
				progress = (window.endMs() - timeMs) / (window.endMs() - holdEndMs);
			} else {
				progress = 1;
			}
			amount = Math.max(amount, smoothstep(progress));
		}
		return amount;
	}

	/** {@code getFadeGain}: the matching audio gain. */
	public double gainAt(double timeMs) {
		return 1 - amountAt(timeMs);
	}
}
