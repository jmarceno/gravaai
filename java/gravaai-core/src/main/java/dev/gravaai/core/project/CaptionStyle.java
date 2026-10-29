package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/** Caption text geometry helpers (port of {@code captionStyle.ts}). */
public final class CaptionStyle {

	public static final double CAPTION_FONT_WEIGHT = 400;
	public static final double CAPTION_LINE_HEIGHT = 1.32;

	private static final double DEFAULT_CAPTION_REFERENCE_WIDTH =
			1920 * (AutoCaptionSettings.defaults().maxWidth() / 100);

	public record Padding(double x, @Nullable double y) {}

	/** Result of {@code getCaptionWordVisualState}. */
	public record WordVisualState(boolean isInactive, double opacity) {}

	private CaptionStyle() {}

	/** {@code getCaptionTargetWidth}. */
	public static double getCaptionTargetWidth(double containerWidth, @Nullable double maxWidthPercent) {
		return Math.max(1, containerWidth * (maxWidthPercent / 100));
	}

	/** {@code getCaptionScaledFontSize}. */
	public static double getCaptionScaledFontSize(
			@Nullable double fontSize, double containerWidth, @Nullable double maxWidthPercent) {
		return Math.max(14, fontSize
				* (getCaptionTargetWidth(containerWidth, maxWidthPercent)
						/ DEFAULT_CAPTION_REFERENCE_WIDTH));
	}

	/** {@code getCaptionPadding}. */
	public static Padding getCaptionPadding(double fontSize) {
		return new Padding(fontSize * 1.1, fontSize * 0.78);
	}

	/** {@code getCaptionScaledRadius}. */
	public static double getCaptionScaledRadius(@Nullable double radius, double fontSize) {
		double baseline = Math.max(1, AutoCaptionSettings.defaults().fontSize());
		return Math.max(0, radius * (fontSize / baseline));
	}

	/** {@code getCaptionTextMaxWidth}. */
	public static double getCaptionTextMaxWidth(
			double containerWidth, double maxWidthPercent, double fontSize) {
		Padding padding = getCaptionPadding(fontSize);
		return Math.max(fontSize * 4,
				getCaptionTargetWidth(containerWidth, maxWidthPercent) - padding.x() * 2);
	}

	/**
	 * {@code getCaptionWordVisualState}: per-word highlighting is disabled because transcriber word
	 * timings are unreliable, so captions render as one uniform block.
	 */
	public static WordVisualState getCaptionWordVisualState(
			boolean hasWordTimings, CaptionLayout.WordState state) {
		return new WordVisualState(false, 1);
	}
}
