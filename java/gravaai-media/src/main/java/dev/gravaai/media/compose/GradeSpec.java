package dev.gravaai.media.compose;

import java.util.List;
import java.util.Optional;

import dev.gravaai.core.project.GradeTarget;

/**
 * Color/luminosity grade windows for one compositor frame, ported from the project's
 * {@code GradeRegion}s. Windows are timeline milliseconds; within one layer the later-starting
 * window wins, so a user stacking two grades always sees the newest one's look. Layers resolve
 * independently: a screen-only and a camera-only grade may share a span and each picture gets its
 * own calibration.
 */
public record GradeSpec(List<Window> windows) {

	/** One graded span with its sliders: signed -100..100 adjusts plus the gamma exponent. */
	public record Window(double startMs, double endMs, double brightness, double contrast,
			double saturation, double temperature, double gamma, GradeTarget targets) {

		/** The pre-targets constructor shape, for callers that shade the screen only. */
		public Window(double startMs, double endMs, double brightness, double contrast,
				double saturation, double temperature, double gamma) {
			this(startMs, endMs, brightness, contrast, saturation, temperature, gamma,
					GradeTarget.MAIN);
		}

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

	/**
	 * The window covering {@code timeMs} on the given layer ({@code MAIN} or {@code CAMERA}),
	 * latest start winning; empty when none covers it or the winner is neutral.
	 */
	public Optional<Window> at(double timeMs, GradeTarget layer) {
		Window active = null;
		for (Window window : windows) {
			if (timeMs < window.startMs() || timeMs >= window.endMs() || !shades(window, layer)) {
				continue;
			}
			if (active == null || window.startMs() >= active.startMs()) {
				active = window;
			}
		}
		return active == null || active.neutral() ? Optional.empty() : Optional.of(active);
	}

	/** True when the window's layer choice includes the requested picture. */
	private static boolean shades(Window window, GradeTarget layer) {
		return layer == GradeTarget.CAMERA ? window.targets().includesCamera()
				: window.targets().includesMain();
	}
}
