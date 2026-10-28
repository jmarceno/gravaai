package dev.gravaai.ui.kit;

import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Port of {@code src/components/launch/HudTooltip.tsx} (plan §5.12): labels a HUD control on hover
 * because native title tooltips never show in the transparent HUD overlay window. Delay 300 ms,
 * close delay 0, placement top with offset 10.
 */
public final class HudTooltip {

	private HudTooltip() {}

	/** Attaches a text label to the trigger and returns it (for tests/showcase). */
	public static Tooltip attach(Node trigger, String label) {
		return Tooltip.install(trigger, label, Motion.HUD_TOOLTIP_DELAY)
				.closeDelay(Duration.ZERO)
				.side(Anchors.Side.TOP)
				.align(Anchors.Align.CENTER)
				.offset(10);
	}

	/** Attaches arbitrary content (the React component accepts a ReactNode label). */
	public static Tooltip attachContent(Node trigger, Node content) {
		return Tooltip.install(trigger, content, Motion.HUD_TOOLTIP_DELAY, Duration.ZERO)
				.side(Anchors.Side.TOP)
				.align(Anchors.Align.CENTER)
				.offset(10);
	}
}
