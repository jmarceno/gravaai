package dev.gravaai.core.project;

/** Annotation position in percent of the frame (default 50,50). */
public record AnnotationPosition(double x, double y) {

	public static final AnnotationPosition DEFAULT = new AnnotationPosition(50, 50);
}
