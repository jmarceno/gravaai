package dev.gravaai.core.project;

/** Figure-annotation payload. */
public record FigureData(ArrowDirection arrowDirection, String color, double strokeWidth) {

	public static final FigureData DEFAULT =
			new FigureData(ArrowDirection.RIGHT, "#2563EB", 4);
}
