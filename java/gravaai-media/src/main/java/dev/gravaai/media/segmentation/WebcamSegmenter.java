package dev.gravaai.media.segmentation;

import org.jspecify.annotations.Nullable;

import dev.gravaai.media.compose.RgbaFrame;

/**
 * Person-segmentation port behind the webcam background blur (stage 7): maps one decoded webcam
 * frame to a person mask. The media layer stays free of the inference runtime — implementations
 * wrap the model (ONNX selfie segmenter) and are wired in by the app layer.
 *
 * <p>The returned mask has the input frame's dimensions with the person probability in every
 * RGBA channel (the compositor samples one channel), or {@code null} when segmentation cannot
 * run (the native runtime or the model is missing): the compositor then draws the webcam
 * unblurred, which is exactly the blur-off look. Implementations own their temporal state (mask
 * smoothing) and may cache internally; implementations are single-threaded, one per caller (each
 * {@code ProjectFrameInputFactory} consumer gets its own).</p>
 */
public interface WebcamSegmenter extends AutoCloseable {

	/** The person mask for {@code frame} (same dimensions, mask value in every channel). */
	@Nullable
	RgbaFrame maskFor(RgbaFrame frame);

	/** Releases the inference resources; implementations without state keep this a no-op. */
	@Override
	default void close() {}
}
