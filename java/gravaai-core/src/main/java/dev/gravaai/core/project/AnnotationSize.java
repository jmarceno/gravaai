package dev.gravaai.core.project;

/** Annotation size in percent of the frame (default 30x20). */
public record AnnotationSize(double width, double height) {

	public static final AnnotationSize DEFAULT = new AnnotationSize(30, 20);
}
