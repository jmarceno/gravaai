package dev.gravaai.media.compose;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Golden-frame comparison (plan §6.2): "The Java test renders the same project/time offscreen
 * and compares with per-channel tolerance. Start at mean abs diff ≤ 2.0 and max-diff pixels
 * ≤ 0.5 %. Write a diff PNG on failure."
 *
 * <p>Definitions used by the harness (single source of truth for the compositor tests):</p>
 * <ul>
 *   <li>{@code meanAbsDiff} = mean absolute RGB channel difference over all pixels, 0..255.</li>
 *   <li>A pixel is a "max-diff pixel" when any RGB channel differs by more than
 *       {@link Thresholds#PLAN channelTolerance} (2, the same scale as the mean limit);
 *       {@code changedPixelRatio} is its fraction over all pixels.</li>
 *   <li>Alpha is ignored because the compositor output is opaque.</li>
 * </ul>
 *
 * <p>The diff PNG marks changed pixels red and dims the actual frame to grey, so a failure can
 * be eyeballed (the same style as the golden reports).</p>
 */
final class GoldenComparison {

	private GoldenComparison() {}

	/** The plan's starting thresholds; tuning needs reviewer approval (plan §6.2). */
	static final Thresholds PLAN_THRESHOLDS = new Thresholds(2.0, 0.005, 2);

	/**
	 * @param meanAbsDiffLimit the maximum mean absolute RGB channel difference
	 * @param changedPixelRatioLimit the maximum fraction of max-diff pixels
	 * @param channelTolerance a channel difference above this marks a max-diff pixel
	 */
	record Thresholds(double meanAbsDiffLimit, double changedPixelRatioLimit,
			int channelTolerance) {
		Thresholds {
			if (meanAbsDiffLimit < 0 || changedPixelRatioLimit < 0 || channelTolerance < 0) {
				throw new IllegalArgumentException("thresholds must not be negative");
			}
		}
	}

	/**
	 * @param passed whether both thresholds are met
	 * @param meanAbsDiff the measured mean absolute RGB channel difference
	 * @param changedPixelRatio the measured fraction of max-diff pixels
	 * @param maxChannelDiff the largest RGB channel difference seen (diagnostics only)
	 * @param changedPixels the number of max-diff pixels
	 * @param totalPixels the pixel count
	 * @param diffPng the written diff PNG, or {@code null} when the comparison passed
	 */
	record Result(boolean passed, double meanAbsDiff, double changedPixelRatio, int maxChannelDiff,
			int changedPixels, int totalPixels, Path diffPng) {

		String summary() {
			return String.format(java.util.Locale.ROOT,
					"%s: meanAbsDiff=%.3f, changedPixels=%.4f%%, maxChannelDiff=%d",
					passed ? "PASS" : "FAIL", meanAbsDiff, changedPixelRatio * 100.0,
					maxChannelDiff);
		}
	}

	/**
	 * Compares a reference frame with a rendered frame.
	 *
	 * @param reference the golden reference frame
	 * @param actual the frame the compositor produced
	 * @param diffPng where to write the diff PNG when the comparison fails
	 */
	static Result compare(RgbaFrame reference, RgbaFrame actual, Path diffPng)
			throws IOException {
		return compare(reference, actual, diffPng, PLAN_THRESHOLDS);
	}

	static Result compare(RgbaFrame reference, RgbaFrame actual, Path diffPng,
			Thresholds thresholds) throws IOException {
		if (reference.width() != actual.width() || reference.height() != actual.height()) {
			throw new IllegalArgumentException("frame size mismatch: reference "
					+ reference.width() + "x" + reference.height() + ", actual "
					+ actual.width() + "x" + actual.height());
		}
		int width = reference.width();
		int height = reference.height();
		int totalPixels = width * height;
		var referencePixels = reference.pixelsTopFirstRgba();
		var actualPixels = actual.pixelsTopFirstRgba();
		long absoluteDifference = 0;
		int maxChannelDiff = 0;
		int changedPixels = 0;
		byte[] diff = new byte[totalPixels * 4];
		for (int pixel = 0; pixel < totalPixels; pixel++) {
			int offset = pixel * 4;
			int pixelMaxDiff = 0;
			for (int channel = 0; channel < 3; channel++) {
				int referenceValue = Byte.toUnsignedInt(referencePixels.get(offset + channel));
				int actualValue = Byte.toUnsignedInt(actualPixels.get(offset + channel));
				int difference = Math.abs(referenceValue - actualValue);
				absoluteDifference += difference;
				pixelMaxDiff = Math.max(pixelMaxDiff, difference);
				diff[offset + channel] = (byte) (actualValue / 4);
			}
			maxChannelDiff = Math.max(maxChannelDiff, pixelMaxDiff);
			if (pixelMaxDiff > thresholds.channelTolerance()) {
				changedPixels++;
				diff[offset] = (byte) 255;
				diff[offset + 1] = 0;
				diff[offset + 2] = 0;
			}
			diff[offset + 3] = (byte) 255;
		}
		double meanAbsDiff = absoluteDifference / (double) (totalPixels * 3);
		double changedPixelRatio = changedPixels / (double) totalPixels;
		boolean passed = meanAbsDiff <= thresholds.meanAbsDiffLimit()
				&& changedPixelRatio <= thresholds.changedPixelRatioLimit();
		Path writtenDiff = null;
		if (!passed && diffPng != null) {
			var buffer = java.nio.ByteBuffer.allocateDirect(diff.length);
			buffer.put(diff);
			buffer.position(0);
			GoldenImages.writePng(new RgbaFrame(width, height, buffer), diffPng);
			writtenDiff = diffPng;
		}
		return new Result(passed, meanAbsDiff, changedPixelRatio, maxChannelDiff, changedPixels,
				totalPixels, writtenDiff);
	}
}
