package dev.gravaai.media.compose;

import org.jspecify.annotations.Nullable;

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
		@Nullable String content,
		@Nullable String imageData,
		Position position,
		Size size,
		TextStyle style,
		int zIndex,
		@Nullable Figure figure,
		@Nullable Double blurIntensity,
		@Nullable String blurColor,
		@Nullable Double loupeMagnification,
		Spotlight spotlight) {

	/** Annotation kinds (TS {@code "text" | "image" | "figure" | "blur" | "loupe" | "spotlight"}). */
	public enum Type {
		TEXT,
		IMAGE,
		FIGURE,
		BLUR,
		LOUPE,
		SPOTLIGHT
	}

	/** The effective magnification of a loupe lens (never below 1). */
	public double loupeMagnificationOrDefault() {
		return loupeMagnification == null || loupeMagnification < 1 ? 2.0 : loupeMagnification;
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
			@Nullable Integer backgroundColor,
			double fontSize,
			@Nullable String fontFamily,
			boolean bold,
			boolean italic,
			boolean underline,
			String textAlign,
			double borderRadius) {
	}

	/** Figure payload: arrow direction, ARGB colour and stroke width in reference pixels. */
	public record Figure(@Nullable String direction, int color, double strokeWidth) {
	}

	/**
	 * Spotlight payload: {@code dimPercent} is the scrim opacity over the rest of the frame
	 * (0–100) and {@code featherPercent} the softness of the bright area's edge as a percent of
	 * the rectangle's short side (0–100, 0 for a crisp edge).
	 */
	public record Spotlight(double dimPercent, double featherPercent) {

		/** Mirrors {@code dev.gravaai.core.project.SpotlightData.DEFAULT}. */
		public static final Spotlight DEFAULT = new Spotlight(60.0, 25.0);
	}

	public AnnotationSpec {
		position = position == null ? new Position(50, 50) : position;
		size = size == null ? new Size(30, 20) : size;
		style = style == null ? defaultTextStyle() : style;
		content = content == null ? "" : content;
		// Every spec carries the spotlight payload (only a SPOTLIGHT reads it), like the project
		// region it comes from, so the renderer never has to guess a default.
		java.util.Objects.requireNonNull(spotlight, "spotlight");
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
