package dev.gravaai.core.project;

import java.util.ArrayList;
import java.util.List;

/** Fade-through-black windows (port of {@code clipTransitions.ts}). */
public final class ClipTransitions {

	/** A dip to black in timeline ms; holdUntilMs is only set when the next clip starts after a gap. */
	public record FadeWindow(double startMs, double peakMs, Double holdUntilMs, double endMs) {}

	private ClipTransitions() {}

	/** {@code getFadeWindowHoldEnd}. */
	public static double getFadeWindowHoldEnd(FadeWindow window) {
		return window.holdUntilMs() != null ? window.holdUntilMs() : window.peakMs();
	}

	public static boolean hasClipTransitions(List<ClipRegion> clips) {
		for (ClipRegion clip : clips) {
			if (clip.transitionOut() != null
					&& clip.transitionOut().kind() == ClipTransitionKind.FADE_BLACK) {
				return true;
			}
		}
		return false;
	}

	/** {@code buildFadeWindows}. */
	public static List<FadeWindow> buildFadeWindows(List<ClipRegion> clips) {
		List<ClipRegion> sorted = Clips.sortClipRegions(clips);
		List<FadeWindow> windows = new ArrayList<>();
		for (int index = 0; index < sorted.size(); index++) {
			ClipRegion clip = sorted.get(index);
			ClipTransition transition = clip.transitionOut();
			if (transition == null || transition.kind() != ClipTransitionKind.FADE_BLACK
					|| !(transition.durationMs() > 0)) {
				continue;
			}

			double clipDurationMs = Math.max(0, clip.endMs() - clip.startMs());
			double half = Math.min(transition.durationMs() / 2.0, clipDurationMs / 2.0);
			ClipRegion next = index + 1 < sorted.size() ? sorted.get(index + 1) : null;
			if (next != null) {
				half = Math.min(half, Math.max(0, next.endMs() - next.startMs()) / 2.0);
				if (half <= 0) {
					continue;
				}
				double holdUntilMs = Math.max(clip.endMs(), next.startMs());
				windows.add(new FadeWindow(clip.endMs() - half, clip.endMs(),
						holdUntilMs > clip.endMs() ? holdUntilMs : null, holdUntilMs + half));
			} else {
				if (half <= 0) {
					continue;
				}
				windows.add(new FadeWindow(clip.endMs() - 2 * half, clip.endMs(), null,
						clip.endMs()));
			}
		}
		return List.copyOf(windows);
	}

	private static double smoothstep(double x) {
		double t = Math.min(1, Math.max(0, x));
		return t * t * (3 - 2 * t);
	}

	/** {@code getFadeBlackAmount}: 0 = untouched, 1 = fully black. */
	public static double getFadeBlackAmount(List<FadeWindow> windows, double timeMs) {
		double amount = 0;
		for (FadeWindow window : windows) {
			if (timeMs < window.startMs() || timeMs > window.endMs()) {
				continue;
			}
			double holdEndMs = getFadeWindowHoldEnd(window);
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

	/** {@code getFadeGain}: audio gain matching the picture fade. */
	public static double getFadeGain(List<FadeWindow> windows, double timeMs) {
		return 1 - getFadeBlackAmount(windows, timeMs);
	}
}
