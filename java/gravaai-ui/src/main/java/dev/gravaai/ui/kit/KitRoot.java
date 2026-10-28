package dev.gravaai.ui.kit;

import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Scene root for every Gravaai window (plan §5.12).
 *
 * <p>JavaFX has no portal layer, so overlays are nodes inside the window: this root stacks the
 * window content under three overlay layers (popovers/tooltips, dialogs, toasts — the same order as
 * HeroUI's {@code --z-index-overlay} / {@code --z-index-toast}). Every kit overlay needs this root;
 * it never opens a second OS window.
 *
 * <pre>{@code
 * KitRoot root = new KitRoot(content);
 * Scene scene = new Scene(root, 1280, 800);
 * Theme.install(scene);
 * }</pre>
 */
public final class KitRoot extends Pane {

	private final Node content;
	private final Pane popoverLayer = layer();
	private final Pane dialogLayer = layer();
	private final Pane toastLayer = layer();

	public KitRoot(Node content) {
		if (content == null) {
			throw new IllegalArgumentException("KitRoot content must not be null");
		}
		this.content = content;
		getStyleClass().add("kit-root");
		getChildren().addAll(content, popoverLayer, dialogLayer, toastLayer);
	}

	private static Pane layer() {
		Pane pane = new Pane();
		pane.setPickOnBounds(false);
		return pane;
	}

	/** The window content (stretched to the whole root). */
	public Node content() {
		return content;
	}

	/** Light-dismiss overlays: popovers, dropdowns, select lists, tooltips. */
	public Pane popoverLayer() {
		return popoverLayer;
	}

	/** Modal overlays: dialogs and their backdrops. */
	public Pane dialogLayer() {
		return dialogLayer;
	}

	/** The in-window toast stack. Always the topmost layer. */
	public Pane toastLayer() {
		return toastLayer;
	}

	@Override
	protected void layoutChildren() {
		double width = getWidth();
		double height = getHeight();
		content.resizeRelocate(0, 0, width, height);
		popoverLayer.resizeRelocate(0, 0, width, height);
		dialogLayer.resizeRelocate(0, 0, width, height);
		toastLayer.resizeRelocate(0, 0, width, height);
	}

	/** Wraps a content node; convenience for window constructors. */
	public static KitRoot of(Node content) {
		return new KitRoot(content);
	}
}
