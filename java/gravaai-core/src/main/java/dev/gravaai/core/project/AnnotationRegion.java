package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/** A text/image/figure/blur annotation on the timeline. */
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
		@Nullable String blurColor) {

	public static final int BLUR_ANNOTATION_STRENGTH = 20;
}
