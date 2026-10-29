package dev.gravaai.ui.editor.timeline;

import org.jspecify.annotations.Nullable;

import javafx.scene.image.Image;

/**
 * Filmstrip frame seam for the clip row (P2.7 filmstrip lane).
 *
 * <p>The app provides the adapter: recording's public {@code dev.gravaai.media.compose.FrameAtTime}
 * API ({@code CompositorFrameAtTime}) renders one composited frame per source time on its own
 * offscreen GL thread; a services/app adapter wraps it, copies/scales the returned
 * {@code RgbaFrame} into the requested thumbnail size, caches it by source time and implements
 * {@link #requestFrame} asynchronously, then calls the installed listener so the timeline repaints.
 *
 * <p>Contract: {@link #frameAt} must never block (return {@code null} when the frame is not cached
 * yet); {@link #requestFrame} schedules the render; {@link #quantumMs} lets the view snap sample
 * times so a small number of thumbnails covers the strip. The view also caches by
 * {@code (clipId, quantized source time, thumbnail size)}.
 */
public interface FilmstripProvider {

	/** A cached thumbnail for the timeline time, or {@code null} when it is not ready yet. */
	@Nullable Image frameAt(String clipId, double timelineTimeMs, int thumbWidth, int thumbHeight);

	/** Requests an asynchronous render; the provider calls the listener when the frame is cached. */
	default void requestFrame(String clipId, double timelineTimeMs, int thumbWidth,
			int thumbHeight) {
	}

	/** Installs the repaint callback the provider invokes after frames become available. */
	default void setOnFrameReady(Runnable listener) {
	}

	/** Sample-time quantum in milliseconds (default React-like 250 ms steps). */
	default double quantumMs() {
		return 250;
	}

	/** Provider with no frames: the view keeps the placeholder filmstrip cells. */
	static FilmstripProvider empty() {
		return (clipId, timelineTimeMs, thumbWidth, thumbHeight) -> null;
	}
}
