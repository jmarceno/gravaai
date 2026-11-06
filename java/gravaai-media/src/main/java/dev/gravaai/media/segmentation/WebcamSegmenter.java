package dev.gravaai.media.segmentation;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * Person-segmentation port behind the webcam background blur (stage 7): maps one decoded webcam
 * frame to a person mask. The media layer stays free of the inference runtime — implementations
 * wrap the model (ONNX selfie segmenter) and are wired in by the app layer.
 *
 * <p>The returned mask covers the whole input frame with the person probability in every RGBA
 * channel (the compositor samples one channel), or {@code null} when segmentation cannot run (the
 * native runtime or the model is missing): the compositor then draws the webcam unblurred, which is
 * exactly the blur-off look. The mask does not have to be at frame resolution — implementations
 * return it at their model's native size and the compositor samples it up through a matrix, which
 * avoids a scalar full-frame bilinear pass per frame. Implementations own their temporal state
 * (mask smoothing) and may cache internally; implementations are single-threaded, one per caller
 * (each {@code ProjectFrameInputFactory} consumer gets its own).</p>
 */
public interface WebcamSegmenter extends AutoCloseable {

	/**
	 * The person mask for {@code frame}, covering the whole frame (mask value in every channel) at
	 * any resolution; the compositor scales it onto the webcam card.
	 *
	 * @param tuning how crisp the mask edge is and how far it sits from the detected subject (the
	 *        per-project controls behind Webcam → Advanced); implementations that do not shape
	 *        their masks may ignore it
	 */
	@Nullable
	RgbaFrame maskFor(RgbaFrame frame, MaskTuning tuning);

	/**
	 * The mask with the shipped edge shape ({@link MaskTuning#DEFAULT}), for callers that do not
	 * expose the controls (tests, still-frame tools).
	 */
	@Nullable
	default RgbaFrame maskFor(RgbaFrame frame) {
		return maskFor(frame, MaskTuning.DEFAULT);
	}

	/**
	 * The most recent completed mask, without asking for a new one; {@code null} when none exists
	 * yet or the implementation segments synchronously. Asynchronous implementations use this so a
	 * consumer can pick up a mask that finished in the background without re-queueing work — a
	 * paused preview repaints when a mask lands, and re-queueing on every repaint would segment
	 * forever.
	 */
	@Nullable
	default RgbaFrame completedMask() {
		return null;
	}

	/**
	 * Registers a callback the implementation runs after producing a mask, so a consumer whose
	 * render loop only draws submitted frames can repaint when a mask finally lands (a paused
	 * preview after an edge-control change is the motivating case). Called from whatever thread
	 * the implementation segments on; a null clears it. Synchronous implementations do not need to
	 * implement this: their {@link #maskFor} caller already has the mask in hand.
	 */
	default void setMaskReadyListener(@Nullable Runnable listener) {}

	/** Releases the inference resources; implementations without state keep this a no-op. */
	@Override
	default void close() {}
}
