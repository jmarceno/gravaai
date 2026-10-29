package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/** A text/image/figure/blur annotation on the timeline. */
public record AnnotationRegion(
		String id,
		long startMs,
		long endMs,
		AnnotationType type,
		@Nullable String content,
		@Nullable String textContent,
		@Nullable String imageContent,
		@Nullable AnnotationPosition position,
		@Nullable AnnotationSize size,
		@Nullable AnnotationTextStyle style,
		@Nullable int zIndex,
		@Nullable Integer trackIndex,
		@Nullable FigureData figureData,
		@Nullable Double blurIntensity,
		@Nullable String blurColor) {

	public static final int BLUR_ANNOTATION_STRENGTH = 20;
}
