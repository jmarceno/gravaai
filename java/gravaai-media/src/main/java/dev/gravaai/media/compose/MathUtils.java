package dev.gravaai.media.compose;

/**
 * Port of {@code src/components/video-editor/videoPlayback/mathUtils.ts}: the easing curves and
 * helpers shared by the zoom/cursor animation code. Ported 1:1 (plan §8.5), including the
 * Newton-plus-bisection solve in {@link #cubicBezier}.
 */
public final class MathUtils {

	private MathUtils() {}

	/** {@code clamp01}. */
	public static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}

	private static double sampleCubicBezier(double a1, double a2, double t) {
		double oneMinusT = 1 - t;
		return 3 * a1 * oneMinusT * oneMinusT * t + 3 * a2 * oneMinusT * t * t + t * t * t;
	}

	private static double sampleCubicBezierDerivative(double a1, double a2, double t) {
		double oneMinusT = 1 - t;
		return 3 * a1 * oneMinusT * oneMinusT + 6 * (a2 - a1) * oneMinusT * t
				+ 3 * (1 - a2) * t * t;
	}

	/** {@code cubicBezier}: solve x(t) = target then evaluate y(t). */
	public static double cubicBezier(double x1, double y1, double x2, double y2, double t) {
		double targetX = clamp01(t);
		double solvedT = targetX;

		for (int i = 0; i < 8; i += 1) {
			double currentX = sampleCubicBezier(x1, x2, solvedT) - targetX;
			double currentDerivative = sampleCubicBezierDerivative(x1, x2, solvedT);

			if (Math.abs(currentX) < 1e-6 || Math.abs(currentDerivative) < 1e-6) {
				break;
			}
			solvedT -= currentX / currentDerivative;
		}

		double lower = 0;
		double upper = 1;
		solvedT = clamp01(solvedT);

		for (int i = 0; i < 10; i += 1) {
			double currentX = sampleCubicBezier(x1, x2, solvedT);
			if (Math.abs(currentX - targetX) < 1e-6) {
				break;
			}
			if (currentX < targetX) {
				lower = solvedT;
			} else {
				upper = solvedT;
			}
			solvedT = (lower + upper) / 2;
		}

		return sampleCubicBezier(y1, y2, solvedT);
	}

	/** {@code easeOutExpo}. */
	public static double easeOutExpo(double t) {
		double clamped = clamp01(t);
		if (clamped == 1) {
			return 1;
		}
		return 1 - Math.pow(2, -7 * clamped);
	}

	/** {@code easeOutZoom}: cubic-bezier(0.16, 1, 0.3, 1). */
	public static double easeOutZoom(double t) {
		return cubicBezier(0.16, 1, 0.3, 1, t);
	}

	/** {@code smoothStep}. */
	public static double smoothStep(double t) {
		double clamped = clamp01(t);
		return clamped * clamped * (3 - 2 * clamped);
	}

	/** {@code easeInOutCubic}, used by zoom-in transitions. */
	public static double easeInOutCubic(double t) {
		double x = clamp01(t);
		return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
	}

	/** {@code easeOutCubic}, used by zoom-out transitions. */
	public static double easeOutCubic(double t) {
		double x = clamp01(t);
		return 1 - Math.pow(1 - x, 3);
	}
}
