package dev.gravaai.media.compose;

/**
 * The layout inputs the compositor needs for one frame (plan §5.8 stage 2): padding and crop.
 * The render target itself defines the output aspect ratio, so no aspect field is needed here.
 *
 * <p>Media-local on purpose: the project model ({@code dev.gravaai.core.project}) is mapped to
 * this by the services adapter, keeping the media layer free of editor state.</p>
 *
 * @param padding the padding percentages, or {@code null} for none
 * @param crop the crop region, or {@code null} for the full frame
 */
public record LayoutSpec(LayoutPadding padding, LayoutCrop crop) {

	/** No padding, no crop. */
	public static final LayoutSpec FULL_FRAME = new LayoutSpec(LayoutPadding.NONE, LayoutCrop.FULL);

	public LayoutSpec {
		padding = padding == null ? LayoutPadding.NONE : padding;
		crop = crop == null ? LayoutCrop.FULL : crop;
	}
}
