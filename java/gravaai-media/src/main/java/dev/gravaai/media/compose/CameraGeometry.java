package dev.gravaai.media.compose;

/**
 * The video-layer geometry of one rendered frame (plan §5.8 stages 2–3, annotation overlay).
 *
 * <p>It is the Java equivalent of the TS {@code layoutCache.maskRect} plus the applied camera
 * transform ({@code annotationRecordingRect} + {@code annotationSceneTransform} in
 * {@code VideoPlayback.tsx}): the rect the annotations are positioned against, the camera
 * transform on top of it, and the render target size to map back into panel pixels. The preview's
 * annotation overlay uses it to place, hit-test, drag and resize annotations exactly where the
 * compositor draws them.
 *
 * @param maskX x of the cropped video rect in output pixels
 * @param maskY y of the cropped video rect in output pixels
 * @param maskWidth displayed width of the cropped video rect
 * @param maskHeight displayed height of the cropped video rect
 * @param scale camera zoom scale applied around the mask rect
 * @param transformX camera translation x in output pixels
 * @param transformY camera translation y in output pixels
 * @param outputWidth compositor output width in pixels
 * @param outputHeight compositor output height in pixels
 */
public record CameraGeometry(
		double maskX,
		double maskY,
		double maskWidth,
		double maskHeight,
		double scale,
		double transformX,
		double transformY,
		int outputWidth,
		int outputHeight) {

	/**
	 * Geometry without an active video frame: the whole output is the recording rect and no
	 * camera transform applies (the React fallback when {@code maskRect} is still empty).
	 */
	public static CameraGeometry fullFrame(int width, int height) {
		return new CameraGeometry(0, 0, width, height, 1, 0, 0, width, height);
	}

	/** True when this geometry can position annotations (a positive recording rect). */
	public boolean hasRecordingRect() {
		return maskWidth > 0 && maskHeight > 0;
	}
}
