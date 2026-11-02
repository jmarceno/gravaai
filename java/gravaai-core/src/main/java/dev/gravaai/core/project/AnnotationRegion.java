package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/** A text/image/figure/blur/loupe annotation on the timeline. */
public record AnnotationRegion(
		String id,
		long startMs,
		long endMs,
		AnnotationType type,
		String content,
		@Nullable String textContent,
		@Nullable String imageContent,
		AnnotationPosition position,
		AnnotationSize size,
		AnnotationTextStyle style,
		int zIndex,
		Integer trackIndex,
		@Nullable FigureData figureData,
		@Nullable Double blurIntensity,
		@Nullable String blurColor,
		@Nullable Double loupeMagnification) {

	public static final int BLUR_ANNOTATION_STRENGTH = 20;

	/** Loupe magnification used when a loupe is created or the field is absent. */
	public static final double LOUPE_DEFAULT_MAGNIFICATION = 2.0;

	/**
	 * Loupe lens diameter as percent of the output frame's short side (the loupe follows the
	 * cursor, so unlike the other annotations its {@code position} is not rendered).
	 */
	public static final double LOUPE_DEFAULT_DIAMETER_PERCENT = 40.0;

	/** The effective magnification of a loupe region (never below 1). */
	public double loupeMagnificationOrDefault() {
		return loupeMagnification == null || loupeMagnification < 1
				? LOUPE_DEFAULT_MAGNIFICATION
				: loupeMagnification;
	}
}
