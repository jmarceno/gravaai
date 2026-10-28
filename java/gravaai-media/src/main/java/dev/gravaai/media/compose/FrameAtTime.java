package dev.gravaai.media.compose;

/**
 * Public filmstrip/timeline API (media-native's P2.4 plan): render one composited frame at a
 * timeline time. Used by the editor timeline filmstrip/thumbnails and by any caller that needs a
 * single frame without driving the playback engine.
 *
 * <p>Implementations render on their own GL thread and return a copied top-first RGBA frame owned
 * by the caller; the same {@link FrameInputFactory} the preview/export uses supplies the resolved
 * project state for the requested time, so preview, filmstrip and export cannot disagree.
 * Thread-safety: one caller at a time; consecutive calls are allowed.
 */
public interface FrameAtTime extends AutoCloseable {

	/** Resolves the compositor input (source/webcam frames, overlays, style) for a timeline time. */
	@FunctionalInterface
	interface FrameInputFactory {
		FrameInput inputAtTime(double timelineSeconds);
	}

	/** Renders one frame at {@code timelineSeconds}; the caller owns the returned pixels. */
	RgbaFrame frameAtTime(double timelineSeconds);

	@Override
	default void close() {
	}
}
