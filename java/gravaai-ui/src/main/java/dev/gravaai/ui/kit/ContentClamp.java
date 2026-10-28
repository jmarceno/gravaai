package dev.gravaai.ui.kit;

import javafx.scene.control.Label;

/**
 * HeroUI Tooltip-based ContentClamp port (plan §5.12), source
 * {@code src/components/ui/content-clamp.tsx}: text longer than {@code truncateLength} is cut with
 * an ellipsis and the full text appears in a tooltip after the same 300 ms delay; shorter text is
 * rendered as-is.
 */
public final class ContentClamp extends Label {

	private final String fullText;

	public ContentClamp(String text) {
		this(text, KitText.DEFAULT_TRUNCATE_LENGTH);
	}

	public ContentClamp(String text, int truncateLength) {
		super();
		this.fullText = text == null ? "" : text;
		getStyleClass().add("content-clamp");
		setWrapText(false);
		if (fullText.length() <= truncateLength) {
			setText(fullText);
		} else {
			setText(KitText.truncate(fullText, truncateLength));
			getStyleClass().add("content-clamp--truncated");
			Tooltip.install(this, fullText, Motion.HUD_TOOLTIP_DELAY).side(Anchors.Side.TOP);
		}
	}

	/** The untruncated text. */
	public String fullText() {
		return fullText;
	}
}