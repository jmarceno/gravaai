package dev.gravaai.core.project;

import org.jspecify.annotations.Nullable;

/** Figure-annotation payload. */
public record FigureData(ArrowDirection arrowDirection, @Nullable String color, double strokeWidth) {

	public static final FigureData DEFAULT =
			new FigureData(ArrowDirection.RIGHT, "#2563EB", 4);
}
