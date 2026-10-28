package dev.gravaai.media.compose;

/**
 * Result of {@link LayoutUtils#computePaddedLayout}: the exact port of the TS
 * {@code PaddedLayoutResult} fields (plan §5.8 stage 2).
 *
 * @param scale the uniform scale applied to the full (uncropped) source frame
 * @param centerOffsetX x of the cropped content's top-left corner in output pixels
 * @param centerOffsetY y of the cropped content's top-left corner in output pixels
 * @param spriteX x where the full source image is drawn (crop offset applied)
 * @param spriteY y where the full source image is drawn
 * @param fullFrameDisplayW displayed width of the full frame including device insets
 * @param fullFrameDisplayH displayed height of the full frame including device insets
 * @param fullVideoDisplayWidth displayed width of the uncropped source
 * @param fullVideoDisplayHeight displayed height of the uncropped source
 * @param croppedDisplayWidth displayed width of the cropped content
 * @param croppedDisplayHeight displayed height of the cropped content
 * @param cropStartX source x of the crop region in source pixels
 * @param cropStartY source y of the crop region in source pixels
 */
public record PaddedLayout(
		double scale,
		double centerOffsetX,
		double centerOffsetY,
		double spriteX,
		double spriteY,
		double fullFrameDisplayW,
		double fullFrameDisplayH,
		double fullVideoDisplayWidth,
		double fullVideoDisplayHeight,
		double croppedDisplayWidth,
		double croppedDisplayHeight,
		double cropStartX,
		double cropStartY) {
}
