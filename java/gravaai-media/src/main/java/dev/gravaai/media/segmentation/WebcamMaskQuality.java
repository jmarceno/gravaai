package dev.gravaai.media.segmentation;

/**
 * Which segmentation model a consumer wants: the cheap one or the precise one.
 *
 * <p>Both run at 256x256 through the same {@link OnnxMaskSegmenter} pipeline and the same
 * {@link MaskTuning}, so they differ in model quality only. The preview picks per state (fast while
 * the timeline plays, precise once it stops, unless the project asks for precise always); export
 * and agent renders always ask for precise.</p>
 */
public enum WebcamMaskQuality {
	/** MediaPipe selfie segmenter: ~2 ms, visible bleed and halo on a busy background. */
	FAST,
	/** MODNet: ~28 ms, clean edge. The only choice for exports and still frames. */
	PRECISE
}
