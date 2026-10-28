package dev.gravaai.ui.editor.timeline.core;

/** A region on one timeline row, for drag/resize constraints (port of {@code TimelineRegionSpan}). */
public record TimelineRegionSpan(String id, double start, double end, String rowId) {

	public Span span() {
		return new Span(start, end);
	}
}
