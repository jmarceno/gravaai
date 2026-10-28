package dev.gravaai.ui.kit;

import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.layout.VBox;

/**
 * HeroUI Popover port (plan §5.12), source {@code src/components/ui/popover.tsx} +
 * {@code @heroui/styles/components/popover.css}.
 *
 * <p>Opens on trigger click, fixed placement with collision flip, closes on outside click/Escape.
 * The body keeps HeroUI's {@code Popover.Dialog} padding (p-4 = 16 px) and radius 24.
 */
public final class Popover {

	private final VBox body = new VBox();
	private final Popup popup;
	private final Node trigger;
	private Consumer<Boolean> onOpenChange = open -> {};

	public Popover(Node trigger) {
		this.trigger = trigger;
		body.getStyleClass().addAll("popover", "popover__dialog");
		popup = new Popup(trigger, body);
		popup.onHide(closed -> onOpenChange.accept(false));
		installTriggerToggle();
	}

	/**
	 * Installs the default click-to-open handler, unless a Button trigger already owns an
	 * action. Action-managed triggers open/close the popover themselves via {@link #toggle()};
	 * listening to MOUSE_CLICKED as well would toggle twice for one user click.
	 */
	private void installTriggerToggle() {
		if (trigger instanceof ButtonBase button && button.getOnAction() != null) {
			return;
		}
		trigger.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> toggle());
	}

	/** Replaces the popover content. */
	public Popover content(Node... nodes) {
		body.getChildren().setAll(nodes);
		return this;
	}

	public Popover side(Anchors.Side side) {
		popup.side(side);
		return this;
	}

	public Popover align(Anchors.Align align) {
		popup.align(align);
		return this;
	}

	public Popover offset(double offset) {
		popup.offset(offset);
		return this;
	}

	/** The React wrapper defaults to a modal popover; keep the light-dismiss behaviour. */
	public Popover modal(boolean modal) {
		popup.modal(modal);
		return this;
	}

	public Popover onOpenChange(Consumer<Boolean> listener) {
		this.onOpenChange = listener == null ? open -> {} : listener;
		return this;
	}

	public void show() {
		if (popup.isShowing()) {
			return;
		}
		popup.show();
		onOpenChange.accept(true);
	}

	public void hide() {
		popup.hide();
	}

	/** Re-measures and re-places the popover while showing (content swaps that change the size). */
	public void relayout() {
		popup.relayout();
	}

	public void toggle() {
		if (popup.isShowing()) {
			hide();
		} else {
			show();
		}
	}

	public boolean isShowing() {
		return popup.isShowing();
	}

	public Region content() {
		return body;
	}

	public Node trigger() {
		return trigger;
	}
}
