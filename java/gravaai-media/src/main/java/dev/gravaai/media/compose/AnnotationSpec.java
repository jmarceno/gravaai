package dev.gravaai.media.compose;

import java.util.List;

/**
 * One annotation as the compositor needs it (plan §5.8 stage 8), media-local like
 * {@link FrameInput}: the services adapter maps the project model to this record.
 */
public record AnnotationSpec(
		String id,
		long startMs,
		long endMs,
		Type type,
		String content,
		String imageData,
		Position position,
		Size size,
		TextStyle style,
		int zIndex,
		Figure figure,
		Double blurIntensity,
		String blurColor) {

	/** Annotation kinds (TS {@code "text" | "image" | "figure" | "blur"}). */
	public enum Type {
		TEXT,
		IMAGE,
		FIGURE,
		BLUR
	}

	/** Center position in percent of the annotation coordinate rect. */
	public record Position(double x, double y) {
	}

	/** Box size in percent of the annotation coordinate rect. */
	public record Size(double width, double height) {
	}

	/** Text style with ARGB colours ({@code backgroundColor} null/transparent for none). */
	public record TextStyle(
			int color,
			Integer backgroundColor,
			double fontSize,
			String fontFamily,
			boolean bold,
			boolean italic,
			boolean underline,
			String textAlign,
			double borderRadius) {
	}

	/** Figure payload: arrow direction, ARGB colour and stroke width in reference pixels. */
	public record Figure(String direction, int color, double strokeWidth) {
	}

	public AnnotationSpec {
		position = position == null ? new Position(50, 50) : position;
		size = size == null ? new Size(30, 20) : size;
		style = style == null ? defaultTextStyle() : style;
		content = content == null ? "" : content;
	}

	/** {@code DEFAULT_ANNOTATION_STYLE} equivalent. */
	public static TextStyle defaultTextStyle() {
		return new TextStyle(0xFFFFFFFF, null, 32,
				"\"SF Pro Display\", \"SF Pro Text\", \"Helvetica Neue\", sans-serif",
				true, false, false, "center", 8);
	}

	/** TS visibility: inclusive at both ends ({@code currentTimeMs >= start && <= end}). */
	public boolean isActiveAt(long timeMs) {
		return timeMs >= startMs && timeMs <= endMs;
	}

	/** Active annotations ordered by z-index (stable). */
	public static List<AnnotationSpec> activeAt(List<AnnotationSpec> annotations, long timeMs) {
		if (annotations == null || annotations.isEmpty()) {
			return List.of();
		}
		return annotations.stream()
				.filter(annotation -> annotation.isActiveAt(timeMs))
				.sorted(java.util.Comparator.comparingInt(AnnotationSpec::zIndex))
				.toList();
	}
}
