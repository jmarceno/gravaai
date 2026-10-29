package dev.gravaai.ui.kit;

import org.jspecify.annotations.Nullable;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * HeroUI Tooltip port (plan §5.12), source {@code src/components/ui/tooltip.tsx} (which re-exports
 * HeroUI's) and {@code @heroui/styles/components/tooltip.css}.
 *
 * <p>In-window overlay: JavaFX {@code Tooltip} would open an OS window, which the plan forbids for
 * overlays. Default open delay is the HeroUI {@code --tooltip-delay} 1500 ms; the content is
 * {@code bg-overlay}, {@code p-2}, {@code text-xs}, radius 12, max width 320 (max-w-xs).
 */
public final class Tooltip {

	private final Node target;
	private final Region content;
	private final PauseTransition openTimer;
	private final PauseTransition closeTimer;
	private Anchors.Side side = Anchors.Side.TOP;
	private Anchors.Align align = Anchors.Align.CENTER;
	private double offset = Anchors.DEFAULT_OFFSET;
	private boolean showing;
	private @Nullable Pane layer;

	private Tooltip(Node target, Region content, Duration openDelay, Duration closeDelay) {
		this.target = target;
		this.content = content;
		this.openTimer = new PauseTransition(openDelay);
		this.openTimer.setOnFinished(event -> show());
		this.closeTimer = new PauseTransition(closeDelay);
		this.closeTimer.setOnFinished(event -> hide());
		target.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED,
				event -> scheduleShow());
		target.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED,
				event -> scheduleHide());
		target.sceneProperty().addListener((observable, oldScene, newScene) -> {
			if (newScene == null) {
				hideNow();
			}
		});
	}

	/** A text tooltip with the HeroUI default 1500 ms delay. */
	public static Tooltip install(Node target, String text) {
		return install(target, text, Motion.TOOLTIP_DELAY);
	}

	/** A text tooltip with an explicit open delay. */
	public static Tooltip install(Node target, String text, Duration openDelay) {
		Label label = new Label(text);
		label.getStyleClass().add("tooltip");
		label.setWrapText(true);
		label.setMaxWidth(320);
		return new Tooltip(target, label, openDelay, Motion.TOOLTIP_CLOSE_DELAY);
	}

	/** A node-content tooltip (HudTooltip accepts arbitrary content). */
	public static Tooltip install(Node target, Node node, Duration openDelay, Duration closeDelay) {
		StackPane wrapper = new StackPane(node);
		wrapper.getStyleClass().add("tooltip");
		wrapper.setMaxWidth(320);
		return new Tooltip(target, wrapper, openDelay, closeDelay);
	}

	public Tooltip side(Anchors.Side side) {
		this.side = side;
		return this;
	}

	public Tooltip align(Anchors.Align align) {
		this.align = align;
		return this;
	}

	public Tooltip offset(double offset) {
		this.offset = offset;
		return this;
	}

	/** 0 ms for the HUD tooltip (closeDelay={0} in HudTooltip.tsx). */
	public Tooltip closeDelay(Duration closeDelay) {
		closeTimer.setDuration(closeDelay);
		return this;
	}

	/** Shows immediately (the showcase uses this to render the hover state). */
	public void showNow() {
		openTimer.stop();
		show();
	}

	/** Hides immediately. */
	public void hideNow() {
		openTimer.stop();
		closeTimer.stop();
		hide();
	}

	private void scheduleShow() {
		closeTimer.stop();
		openTimer.playFromStart();
	}

	private void scheduleHide() {
		openTimer.stop();
		closeTimer.playFromStart();
	}

	private void show() {
		if (showing) {
			return;
		}
		try {
			layer = Kit.popoverLayer(target);
		} catch (IllegalStateException error) {
			return;
		}
		showing = true;
		// The tooltip is a non-interactive overlay. Without this, the content can become the
		// mouse target and fire MOUSE_EXITED on the target, which immediately hides the tooltip.
		content.setMouseTransparent(true);
		content.setManaged(false);
		layer.getChildren().add(content);
		content.applyCss();
		content.autosize();
		double width = content.getWidth();
		double height = content.getHeight();
		Anchors.Side effectiveSide = Anchors.flipIfNeeded(target, side, width, height, offset,
				layer.getWidth(), layer.getHeight());
		Point2D point = Anchors.place(target, effectiveSide, align, width, height, offset, layer.getWidth(),
				layer.getHeight());
		content.setLayoutX(point.getX());
		content.setLayoutY(point.getY());
		content.setOpacity(0);
		FadeTransition fade = Motion.fadeIn(content, Motion.POPOVER_ENTER, Motion.EASE_SMOOTH);
		fade.play();
	}

	private void hide() {
		if (!showing) {
			return;
		}
		showing = false;
		FadeTransition fade = Motion.fadeOut(content, Motion.POPOVER_EXIT, Motion.EASE_SMOOTH);
		fade.setOnFinished(event -> removeContent());
		fade.play();
	}

	private void removeContent() {
		if (layer != null) {
			layer.getChildren().remove(content);
		}
		layer = null;
		content.setOpacity(1);
	}
}
