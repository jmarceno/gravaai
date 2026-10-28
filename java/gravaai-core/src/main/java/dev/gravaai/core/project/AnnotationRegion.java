package dev.gravaai.core.project;

/** A text/image/figure/blur annotation on the timeline. */
public record AnnotationRegion(
		String id,
		long startMs,
		long endMs,
		AnnotationType type,
		String content,
		String textContent,
		String imageContent,
		AnnotationPosition position,
		AnnotationSize size,
		AnnotationTextStyle style,
		int zIndex,
		Integer trackIndex,
		FigureData figureData,
		Double blurIntensity,
		String blurColor) {

	public static final int BLUR_ANNOTATION_STRENGTH = 20;
}
