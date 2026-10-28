package dev.gravaai.media.compose;

/**
 * Padding settings of the video layer (port of the {@code Padding} type in
 * {@code src/components/video-editor/types.ts} and {@code dev.gravaai.core.project.Padding}).
 *
 * <p>Values are percentages (0-100 for linked padding, up to
 * {@link LayoutUtils#ADVANCED_VERTICAL_PADDING_MAX} for unlinked vertical padding). The layout
 * maths in {@link LayoutUtils} is the TS {@code computePaddedLayout} port and owns the
 * clamping, exactly like the renderer does.</p>
 *
 * @param top top padding percentage
 * @param bottom bottom padding percentage
 * @param left left padding percentage
 * @param right right padding percentage
 * @param linked whether the padding is linked (uniform); unlinked vertical padding can reach
 *        the advanced range to pin the video to an edge
 */
public record LayoutPadding(double top, double bottom, double left, double right,
		boolean linked) {

	/** Zero padding, linked. */
	public static final LayoutPadding NONE = new LayoutPadding(0, 0, 0, 0, true);

	/** Linked padding with the same value on all four sides. */
	public static LayoutPadding linked(double all) {
		return new LayoutPadding(all, all, all, all, true);
	}

	/** {@code isZeroPadding} from layoutUtils.ts. */
	public boolean isZero() {
		return top == 0 && bottom == 0 && left == 0 && right == 0;
	}
}
