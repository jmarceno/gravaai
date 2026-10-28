package dev.gravaai.ui.editor.timeline.core;

/**
 * A timeline span in the generic shape {@code dnd-timeline} uses (port of the TS {@code Span}):
 * {@code start}/{@code end} are either timeline milliseconds or screen pixels, depending on the
 * helper. Kept as {@code double} because presentation maps media time to display space.
 */
public record Span(double start, double end) {

	public static Span of(double start, double end) {
		return new Span(start, end);
	}

	public double duration() {
		return end - start;
	}
}
