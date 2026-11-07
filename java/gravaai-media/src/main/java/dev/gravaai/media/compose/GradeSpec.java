package dev.gravaai.media.compose;

import java.util.List;
import java.util.Optional;

/**
 * Color/luminosity grade windows for one compositor frame, ported from the project's
 * {@code GradeRegion}s. Windows are timeline milliseconds; where grades overlap the later-starting
 * window wins, so a user stacking two grades always sees the newest one's look.
 */
public record GradeSpec(List<Window> windows) {

	/** One graded span with its sliders: signed -100..100 adjusts plus the gamma exponent. */
	public record Window(double startMs, double endMs, double brightness, double contrast,
			double saturation, double temperature, double gamma) {

		/** True when the sliders change nothing (the compositor then skips the pass). */
		public boolean neutral() {
			return brightness == 0 && contrast == 0 && saturation == 0 && temperature == 0
					&& gamma == 1;
		}
	}

	public static final GradeSpec NONE = new GradeSpec(List.of());

	public GradeSpec {
		windows = windows == null ? List.of() : List.copyOf(windows);
	}

	/** The window covering {@code timeMs}, latest start wins; empty when none covers it. */
	public Optional<Window> at(double timeMs) {
		Window active = null;
		for (Window window : windows) {
			if (timeMs < window.startMs() || timeMs >= window.endMs()) {
				continue;
			}
			if (active == null || window.startMs() >= active.startMs()) {
				active = window;
			}
		}
		return active == null || active.neutral() ? Optional.empty() : Optional.of(active);
	}
}
