package dev.gravaai.ui.editor.clips;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Kit;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Label;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Full-window clip-import blocker (port of the importing overlay in {@code EditorShell.tsx}):
 * a dimmed backdrop with a centred card, a status message and a Cancel button. It swallows every
 * mouse event so the editor stays frozen while the import pipeline runs.
 */
public final class ClipImportOverlay extends StackPane {

	private final Button cancel;

	public ClipImportOverlay(Runnable onCancel) {
		getStyleClass().add("clip-import-overlay");
		setAlignment(Pos.CENTER);
		setPickOnBounds(true);

		Label title = new Label("Adding video…");
		title.getStyleClass().add("clip-import-overlay__title");
		Label description = new Label("Preparing footage and audio for your timeline.");
		description.getStyleClass().add("clip-import-overlay__description");
		description.setWrapText(true);

		cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.SM);
		cancel.getStyleClass().add("clip-import-overlay__cancel");
		cancel.setOnAction(event -> onCancel.run());

		VBox card = new VBox(8, title, description, cancel);
		card.getStyleClass().add("clip-import-overlay__card");
		card.setAlignment(Pos.CENTER);
		getChildren().add(card);
	}

	/** Disables the Cancel button once a cancellation was requested (React {@code cancelling}). */
	public void setCancelling(boolean cancelling) {
		cancel.setDisable(cancelling);
	}

	/**
	 * Shows the overlay in the window's dialog layer; the host node picks the {@link KitRoot}.
	 * Returns the overlay so the caller can hide it again with {@link #hide()}.
	 */
	public static ClipImportOverlay show(Node host, Runnable onCancel) {
		ClipImportOverlay overlay = new ClipImportOverlay(onCancel);
		KitRoot root = Kit.root(host);
		Pane layer = root.dialogLayer();
		layer.getChildren().add(overlay);
		overlay.fit = (observable, oldValue, newValue) ->
				overlay.resizeRelocate(0, 0, layer.getWidth(), layer.getHeight());
		layer.widthProperty().addListener(overlay.fit);
		layer.heightProperty().addListener(overlay.fit);
		overlay.resizeRelocate(0, 0, layer.getWidth(), layer.getHeight());
		return overlay;
	}

	/** Removes the overlay from its layer. */
	public void hide() {
		Pane parent = getParent() instanceof Pane pane ? pane : null;
		if (parent != null) {
			if (fit != null) {
				parent.widthProperty().removeListener(fit);
				parent.heightProperty().removeListener(fit);
			}
			parent.getChildren().remove(this);
		}
	}

	private ChangeListener<Number> fit;
}
