package dev.gravaai.ui.kit;

import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Small helpers shared by the kit controls (plan §5.12): overlay-layer lookup, CSS classes and
 * spacing shorthands. Everything here is scene-graph plumbing, never behaviour.
 */
public final class Kit {

	private Kit() {}

	/** Adds style classes, skipping duplicates. */
	public static <T extends Node> T classes(T node, String... names) {
		for (String name : names) {
			if (name != null && !name.isBlank() && !node.getStyleClass().contains(name)) {
				node.getStyleClass().add(name);
			}
		}
		return node;
	}

	/** Adds or removes a style class. */
	public static void setClass(Node node, String name, boolean enabled) {
		if (enabled) {
			classes(node, name);
		} else {
			node.getStyleClass().remove(name);
		}
	}

	/** Walks up to the {@link KitRoot} that hosts this node. */
	public static KitRoot root(Node node) {
		Objects.requireNonNull(node, "node");
		Node current = node;
		while (current != null) {
			if (current instanceof KitRoot kitRoot) {
				return kitRoot;
			}
			current = current.getParent();
		}
		throw new IllegalStateException(
				"No KitRoot found above " + node + ". Wrap the scene root in new KitRoot(content) so the "
						+ "kit can host popovers, dialogs and toasts in the window (plan §5.12).");
	}

	/** The popover/tooltip layer of the window that hosts {@code node}. */
	public static Pane popoverLayer(Node node) {
		return root(node).popoverLayer();
	}

	/** The dialog layer of the window that hosts {@code node}. */
	public static Pane dialogLayer(Node node) {
		return root(node).dialogLayer();
	}

	/** The toast layer of the window that hosts {@code node}. */
	public static Pane toastLayer(Node node) {
		return root(node).toastLayer();
	}

	/** A plain {@link Label} with optional extra style classes. */
	public static Label text(String text, String... extraClasses) {
		Label label = new Label(text);
		classes(label, extraClasses);
		return label;
	}

	/** A HeroUI-styled form label ({@code .g-label}). */
	public static Label formLabel(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("g-label");
		return label;
	}

	/** A vertical box with the given spacing and children. */
	public static VBox vbox(double spacing, Node... children) {
		VBox box = new VBox(spacing, children);
		return box;
	}

	/** A horizontal spacer that grows. */
	public static Region spacer() {
		Region spacer = new Region();
		HBoxGrow.set(spacer);
		return spacer;
	}

	/** Name avoids importing HBox just for the constant in this helper. */
	private static final class HBoxGrow {
		private static void set(Region region) {
			javafx.scene.layout.HBox.setHgrow(region, Priority.ALWAYS);
		}
	}
}
