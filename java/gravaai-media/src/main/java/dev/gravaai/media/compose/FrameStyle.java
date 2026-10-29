package dev.gravaai.media.compose;

/**
 * Video-layer visual style for one compositor frame (plan §5.8 stage 4): the squircle mask
 * corner radius and the drop-shadow settings. Media-local like {@link LayoutSpec}; the services
 * adapter maps the editor state ({@code borderRadius}, {@code shadowIntensity}) into it.
 *
 * @param borderRadiusPercent border-radius setting as a percentage of the content's shorter
 *        side (the editor's 0-50 scale, see
 *        {@link LayoutUtils#scalePreviewBorderRadius(double, double, double)})
 * @param showShadow whether the three-layer video drop shadow is drawn
 * @param shadowIntensity the shadow intensity (0-1; clamped by the compositor)
 */
public record FrameStyle(double borderRadiusPercent, boolean showShadow,
		double shadowIntensity) {

	/** No mask radius, no shadow: plain rectangular video layer. */
	public static final FrameStyle DEFAULT = new FrameStyle(0, false, 0);

	public FrameStyle {
		if (!Double.isFinite(borderRadiusPercent) || !Double.isFinite(shadowIntensity)) {
			throw new IllegalArgumentException("frame style values must be finite");
		}
	}
}
