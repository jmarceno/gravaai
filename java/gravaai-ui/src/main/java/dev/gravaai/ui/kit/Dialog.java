package dev.gravaai.ui.kit;

import org.jspecify.annotations.Nullable;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * In-window modal dialog (plan §5.12: "Dialog (in-window modal overlay with backdrop, NOT a separate
 * OS window)"). Source {@code src/components/ui/dialog.tsx} +
 * {@code @heroui/styles/components/modal.css}.
 *
 * <p>Backdrop {@code rgba(0,0,0,0.6)}, panel bg-overlay radius 24 padding 24, enter 250 ms
 * ease-out-quad fade + zoom 1.05→1, backdrop 150 ms fade, exit 100 ms. Escape and backdrop click
 * dismiss; the close button matches Modal.CloseTrigger.
 *
 * <pre>{@code
 * Dialog dialog = new Dialog("Remove project?");
 * dialog.description("This cannot be undone.");
 * dialog.footer(new Button("Cancel").text("Cancel"), dangerButton);
 * dialog.show(anyNodeInsideKitRoot);
 * }</pre>
 */
public final class Dialog {

	private final String title;
	private final VBox panel = new VBox();
	private final HBox header = new HBox();
	private final VBox headerText = new VBox();
	private final Label titleLabel = new Label();
	private final Label descriptionLabel = new Label();
	private final VBox body = new VBox();
	private final HBox footer = new HBox();
	private Node[] content = new Node[0];
	private Node[] footerNodes = new Node[0];
	private String description = "";
	private boolean dismissable = true;
	private double width = 512;
	private Runnable onClose = () -> {};
	private @Nullable KitRoot root;
	private @Nullable StackPane container;
	private @Nullable Region backdrop;
	private javafx.beans.value.@Nullable ChangeListener<Number> layerFitListener;

	private final EventHandler<KeyEvent> escape = event -> {
		if (event.getCode() == KeyCode.ESCAPE && dismissable) {
			close();
			event.consume();
		}
	};

	public Dialog(String title) {
		this.title = title;
		titleLabel.setText(title);
		titleLabel.getStyleClass().add("dialog__title");
		titleLabel.setWrapText(true);
		descriptionLabel.getStyleClass().add("dialog__description");
		descriptionLabel.setWrapText(true);
		descriptionLabel.setVisible(false);
		descriptionLabel.setManaged(false);
		headerText.getStyleClass().add("dialog__header");
		headerText.getChildren().addAll(titleLabel, descriptionLabel);
		HBox.setHgrow(headerText, Priority.ALWAYS);
		header.getChildren().add(headerText);
		header.setAlignment(Pos.TOP_LEFT);
		header.setSpacing(8);

		body.getStyleClass().add("dialog__body");
		footer.getStyleClass().add("dialog__footer");
		footer.setAlignment(Pos.CENTER_RIGHT);

		panel.getStyleClass().add("dialog");
		panel.setFillWidth(true);
		panel.setMaxWidth(width);
		panel.setPrefWidth(width);
		panel.setFocusTraversable(true);
		// StackPane would stretch a managed child to the full layer height: keep the panel at
		// its content height and let the StackPane alignment centre it.
		panel.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
	}

	/** Secondary text under the title (HeroUI Description). */
	public Dialog description(String text) {
		this.description = text == null ? "" : text;
		descriptionLabel.setText(description);
		descriptionLabel.setVisible(!description.isEmpty());
		descriptionLabel.setManaged(!description.isEmpty());
		return this;
	}

	/** Dialog body nodes. */
	public Dialog content(Node... nodes) {
		this.content = nodes;
		return this;
	}

	/** Footer buttons, right aligned like HeroUI Modal.Footer. */
	public Dialog footer(Node... nodes) {
		this.footerNodes = nodes;
		return this;
	}

	/** Max panel width in px (HeroUI {@code size="lg"} is the app default: 512). */
	public Dialog width(double width) {
		this.width = width;
		panel.setPrefWidth(width);
		panel.setMaxWidth(width);
		return this;
	}

	public Dialog dismissable(boolean dismissable) {
		this.dismissable = dismissable;
		return this;
	}

	public Dialog onClose(Runnable onClose) {
		this.onClose = onClose == null ? () -> {} : onClose;
		return this;
	}

	/** Adds the dialog to the window that hosts {@code owner}. */
	public Dialog show(Node owner) {
		root = Kit.root(owner);
		Pane layer = root.dialogLayer();
		layer.setPickOnBounds(true);
		layer.getChildren().clear();

		StackPane dialogContainer = new StackPane();
		container = dialogContainer;
		dialogContainer.setPadding(new Insets(40));
		dialogContainer.setPickOnBounds(true);
		backdrop = new Region();
		backdrop.getStyleClass().add("dialog__backdrop");
		backdrop.setPickOnBounds(true);
		backdrop.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
			if (dismissable && event.getTarget() == backdrop) {
				close();
			}
		});

		body.getChildren().setAll(content);
		footer.getChildren().setAll(footerNodes);
		footer.setVisible(footerNodes.length > 0);
		footer.setManaged(footerNodes.length > 0);
		Button closeButton = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		closeButton.getStyleClass().add("dialog__close-button");
		closeButton.icon(Icons.X);
		closeButton.setOnAction(event -> close());
		closeButton.setFocusTraversable(false);
		header.getChildren().add(closeButton);
		header.setAlignment(Pos.TOP_LEFT);
		headerText.setAlignment(Pos.TOP_LEFT);

		panel.getChildren().setAll(header);
		if (!(content.length == 0)) {
			panel.getChildren().add(body);
		}
		if (footerNodes.length > 0) {
			panel.getChildren().add(footer);
		}
		dialogContainer.getChildren().addAll(backdrop, panel);
		// Plain Pane layout would shrink a managed child to its preferred size on the next pulse.
		dialogContainer.setManaged(false);
		layer.getChildren().add(dialogContainer);
		backdrop.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		// The dialog layer is a plain Pane: it does not lay out its children, and on the first
		// show its size can still be 0x0, so size the container here and keep it in sync.
		layerFitListener = (observable, oldValue, newValue) -> dialogContainer.resizeRelocate(0, 0,
				Math.max(1, layer.getWidth()), Math.max(1, layer.getHeight()));
		layer.widthProperty().addListener(layerFitListener);
		layer.heightProperty().addListener(layerFitListener);
		layerFitListener.changed(null, null, null);

		// Enter: backdrop 150 ms fade, panel 250 ms ease-out-quad fade + zoom.
		backdrop.setOpacity(0);
		panel.setOpacity(0);
		panel.setScaleX(1.05);
		panel.setScaleY(1.05);
		FadeTransition backdropIn = Motion.fadeIn(backdrop, Motion.BACKDROP_ENTER, Motion.EASE_OUT);
		FadeTransition panelIn = Motion.fadeIn(panel, Motion.DIALOG_ENTER, Motion.EASE_OUT_QUAD);
		ScaleTransition zoom = new ScaleTransition(Motion.DIALOG_ENTER, panel);
		zoom.setToX(1);
		zoom.setToY(1);
		zoom.setInterpolator(Motion.EASE_OUT_QUAD);
		ParallelTransition enter = new ParallelTransition(backdropIn, panelIn, zoom);
		enter.play();

		if (owner.getScene() != null) {
			owner.getScene().addEventFilter(KeyEvent.KEY_PRESSED, escape);
		}
		panel.requestFocus();
		panel.applyCss();
		panel.autosize();
		return this;
	}

	/** Closes with the HeroUI exit timing and removes the overlay. */
	public void close() {
		if (root == null || container == null) {
			return;
		}
		KitRoot closingRoot = root;
		StackPane closingContainer = container;
		root = null;
		container = null;
		if (closingContainer.getScene() != null) {
			closingContainer.getScene().removeEventFilter(KeyEvent.KEY_PRESSED, escape);
		}
		Pane closingLayer = closingRoot.dialogLayer();
		if (layerFitListener != null) {
			closingLayer.widthProperty().removeListener(layerFitListener);
			closingLayer.heightProperty().removeListener(layerFitListener);
			layerFitListener = null;
		}
		FadeTransition fade = Motion.fadeOut(closingContainer, Motion.DIALOG_EXIT, Motion.EASE_OUT_QUAD);
		fade.setOnFinished(event -> {
			Pane layer = closingRoot.dialogLayer();
			layer.getChildren().remove(closingContainer);
			if (layer.getChildren().isEmpty()) {
				layer.setPickOnBounds(false);
			}
			onClose.run();
		});
		fade.play();
	}

	/** The dialog panel (height measurement in tests). */
	public Region panel() {
		return panel;
	}

	/** The title text. */
	public String title() {
		return title;
	}
}
