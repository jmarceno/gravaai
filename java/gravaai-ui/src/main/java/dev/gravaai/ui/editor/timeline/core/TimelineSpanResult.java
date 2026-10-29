package dev.gravaai.ui.editor.timeline.core;

import org.jspecify.annotations.Nullable;

/**
 * Result of a primary-track drag/resize: the span plus, for the clip sequence, the insertion index
 * ({@code null} for plain rows). Port of the TS {@code Span | ClipSequenceSpan} union.
 */
public record TimelineSpanResult(double start, double end, @Nullable Integer sequenceIndex) {

	public static TimelineSpanResult of(Span span) {
		return new TimelineSpanResult(span.start(), span.end(), null);
	}

	public static TimelineSpanResult of(double start, double end) {
		return new TimelineSpanResult(start, end, null);
	}

	public Span span() {
		return new Span(start, end);
	}
}
