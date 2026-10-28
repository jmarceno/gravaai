package dev.gravaai.ui.kit;

import dev.gravaai.ui.kit.Anchors.Align;
import dev.gravaai.ui.kit.Anchors.Side;
import java.util.function.Consumer;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * A light-dismiss overlay hosted inside the window (plan §5.12).
 *
 * <p>Replacement for HeroUI's {@code Popover} / {@code Dropdown.Popover} / Select popover: placed
 * next to its owner, closes on outside click or Escape, and animates in with the HeroUI enter
 * timing (150 ms ease-smooth, fade 0→1 and zoom 0.95→1) and out with the 100 ms exit. This is never
 * an OS window: the content lives in {@link KitRoot#popoverLayer()}.
 */
public final class Popup {

	private final Node owner;
	private final Region content;
	private Side side = Anchors.Side.BOTTOM;
	private Anchors.Align align = Anchors.Align.CENTER;
	private double offset = Anchors.DEFAULT_OFFSET;
	private boolean modal;
	private boolean flip = true;
	private Consumer<Popup> onHide = popup -> {};
	private Pane layer;
	private Pane block;
	private boolean showing;
	private final EventHandler<MouseEvent> outsideClick = this::handleOutsideClick;
	private final EventHandler<KeyEvent> escape = event -> {
		if (event.getCode() == KeyCode.ESCAPE) {
			hide();
			event.consume();
		}
	};

	public Popup(Node owner, Region content) {
		this.owner = owner;
		this.content = content;
	}

	/** Side of the owner (default bottom). */
	public Popup side(Side side) {
		this.side = side;
		return this;
	}

	/** Alignment along the owner edge (default centre). */
	public Popup align(Align align) {
		this.align = align;
		return this;
	}

	/** Gap in px (default 8, the HeroUI popover offset). */
	public Popup offset(double offset) {
		this.offset = offset;
		return this;
	}

	/** When true a transparent block layer catches the outside click (React Aria modal popover). */
	public Popup modal(boolean modal) {
		this.modal = modal;
		return this;
	}

	/** Flip to the opposite side when the content would leave the window. */
	public Popup flip(boolean flip) {
		this.flip = flip;
		return this;
	}

	public Popup onHide(Consumer<Popup> listener) {
		this.onHide = listener == null ? popup -> {} : listener;
		return this;
	}

	public boolean isShowing() {
		return showing;
	}

	public Region content() {
		return content;
	}

	public void show() {
		if (showing) {
			return;
		}
		KitRoot root = Kit.root(owner);
		layer = root.popoverLayer();
		showing = true;
		if (modal) {
			block = new Pane();
			block.setManaged(false);
			block.setPickOnBounds(true);
			block.setStyle("-fx-background-color: transparent;");
			block.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
				hide();
				event.consume();
			});
			layer.getChildren().add(block);
			block.resizeRelocate(0, 0, layer.getWidth(), layer.getHeight());
		}
		// The overlay layers are plain Panes: a managed child is laid out at its preferred
		// size and position (0,0) on the next pulse, which would undo the placement here.
		content.setManaged(false);
		content.setOpacity(0);
		layer.getChildren().add(content);
		content.applyCss();
		content.autosize();
		double width = content.getWidth() > 0 ? content.getWidth() : content.prefWidth(-1);
		double height = content.getHeight() > 0 ? content.getHeight() : content.prefHeight(width);
		content.resize(width, height);
		Anchors.Side effectiveSide = side;
		if (flip) {
			effectiveSide = Anchors.flipIfNeeded(owner, side, width, height, offset, layer.getWidth(),
					layer.getHeight());
		}
		Point2D point = Anchors.place(owner, effectiveSide, align, width, height, offset, layer.getWidth(),
				layer.getHeight());
		content.setLayoutX(point.getX());
		content.setLayoutY(point.getY());
		animateIn(effectiveSide);
		Scene scene = owner.getScene();
		if (scene != null) {
			scene.addEventFilter(MouseEvent.MOUSE_PRESSED, outsideClick);
			scene.addEventFilter(KeyEvent.KEY_PRESSED, escape);
		}
		owner.sceneProperty().addListener((observable, oldScene, newScene) -> {
			if (newScene == null) {
				hideImmediately();
			}
		});
	}

	private void animateIn(Anchors.Side effectiveSide) {
		FadeTransition fade = Motion.fadeIn(content, Motion.POPOVER_ENTER, Motion.EASE_SMOOTH);
		content.setScaleX(0.95);
		content.setScaleY(0.95);
		ScaleTransition zoom = new ScaleTransition(Motion.POPOVER_ENTER, content);
		zoom.setToX(1);
		zoom.setToY(1);
		zoom.setInterpolator(Motion.EASE_SMOOTH);
		// HeroUI slides one step (4px) towards the owner from the placement side.
		double x = 0;
		double y = 0;
		switch (effectiveSide) {
			case TOP -> y = -4;
			case BOTTOM -> y = 4;
			case LEFT -> x = -4;
			case RIGHT -> x = 4;
		}
		content.setTranslateX(x);
		content.setTranslateY(y);
		ParallelTransition enter = new ParallelTransition(fade, zoom,
				Motion.translateTo(content, 0, 0, Motion.POPOVER_ENTER, Motion.EASE_SMOOTH));
		enter.setOnFinished(event -> content.setOpacity(1));
		enter.play();
	}

	/** Hides with the HeroUI exit timing. */
	public void hide() {
		if (!showing) {
			return;
		}
		showing = false;
		detachSceneHandlers();
		FadeTransition fade = Motion.fadeOut(content, Motion.POPOVER_EXIT, Motion.EASE_SMOOTH);
		fade.setOnFinished(event -> {
			removeNodes();
			content.setOpacity(1);
			onHide.accept(this);
		});
		fade.play();
	}

	/**
	 * Re-measures and re-places the content while showing (for state swaps that change the size).
	 * No-op when not showing; no animation, matching a fresh placement.
	 */
	public void relayout() {
		if (!showing || layer == null) {
			return;
		}
		content.applyCss();
		double width = content.prefWidth(-1);
		double height = content.prefHeight(width);
		content.resize(width, height);
		Anchors.Side effectiveSide = side;
		if (flip) {
			effectiveSide = Anchors.flipIfNeeded(owner, side, width, height, offset, layer.getWidth(),
					layer.getHeight());
		}
		Point2D point = Anchors.place(owner, effectiveSide, align, width, height, offset,
				layer.getWidth(), layer.getHeight());
		content.setLayoutX(point.getX());
		content.setLayoutY(point.getY());
	}

	/** Removes the overlay without an exit animation (owner left the scene). */
	public void hideImmediately() {
		if (!showing) {
			return;
		}
		showing = false;
		detachSceneHandlers();
		removeNodes();
		content.setOpacity(1);
		onHide.accept(this);
	}

	private void removeNodes() {
		Pane host = layer;
		if (host != null) {
			host.getChildren().remove(content);
			if (block != null) {
				host.getChildren().remove(block);
				block = null;
			}
		}
		layer = null;
	}

	private void detachSceneHandlers() {
		Scene scene = owner.getScene();
		if (scene != null) {
			scene.removeEventFilter(MouseEvent.MOUSE_PRESSED, outsideClick);
			scene.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
		}
	}

	private void handleOutsideClick(MouseEvent event) {
		if (!showing) {
			return;
		}
		Node target = event.getPickResult() == null ? null : event.getPickResult().getIntersectedNode();
		if (target != null) {
			for (Node node = target; node != null; node = node.getParent()) {
				if (node == content || node == owner) {
					return;
				}
			}
		}
		hide();
	}
}
