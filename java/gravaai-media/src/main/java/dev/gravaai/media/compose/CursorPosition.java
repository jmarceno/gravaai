package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Port of {@code src/components/video-editor/videoPlayback/cursorPosition.ts}: linear
 * interpolation of the neighbouring cursor telemetry samples at a media timestamp.
 */
public final class CursorPosition {

	private CursorPosition() {}

	/**
	 * Interpolates the cursor position at {@code timeMs}.
	 *
	 * @return the interpolated sample (its {@code timeSeconds} set to {@code timeMs / 1000}),
	 *         or {@code null} when there are no samples
	 */
	public static @Nullable CursorSample interpolateCursorPosition(List<CursorSample> samples,
			double timeMs) {
		if (samples == null || samples.isEmpty()) {
			return null;
		}
		CursorSample first = samples.get(0);
		if (timeMs <= first.timeSeconds() * 1000) {
			return new CursorSample(timeMs / 1000.0, first.x(), first.y());
		}
		CursorSample last = samples.get(samples.size() - 1);
		if (timeMs >= last.timeSeconds() * 1000) {
			return new CursorSample(timeMs / 1000.0, last.x(), last.y());
		}

		int lo = 0;
		int hi = samples.size() - 1;
		while (lo < hi - 1) {
			int mid = (lo + hi) >> 1;
			if (samples.get(mid).timeSeconds() * 1000 <= timeMs) {
				lo = mid;
			} else {
				hi = mid;
			}
		}

		CursorSample a = samples.get(lo);
		CursorSample b = samples.get(hi);
		double span = (b.timeSeconds() - a.timeSeconds()) * 1000;
		if (span <= 0) {
			return new CursorSample(timeMs / 1000.0, a.x(), a.y());
		}
		double t = (timeMs - a.timeSeconds() * 1000) / span;
		return new CursorSample(timeMs / 1000.0,
				a.x() + (b.x() - a.x()) * t,
				a.y() + (b.y() - a.y()) * t);
	}
}
