package dev.gravaai.ui.editor.timeline;

import java.util.List;

/**
 * What the Camera layout track shows of eye contact: a thin strip along the bottom of the row,
 * blue where the eyes are corrected, pale where the correction fades out (blinks, head turns,
 * looking away), a progress bar while the webcam is being analysed and a red line when the
 * analysis failed.
 *
 * @param progress 0..1 while analysing
 * @param spans    corrected timeline spans, when ready
 */
public record EyeContactStrip(State state, double progress, List<Span> spans) {

	public enum State {
		ANALYSING,
		READY,
		FAILED
	}

	/** A timeline span where the correction is on; {@code full} when at (nearly) full weight. */
	public record Span(long startMs, long endMs, boolean full) {}

	public EyeContactStrip {
		spans = List.copyOf(spans);
	}
}
