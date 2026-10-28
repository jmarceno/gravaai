package dev.gravaai.ui.kit;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

/**
 * HeroUI Accordion port (plan §5.12), source {@code src/components/ui/accordion.tsx} +
 * {@code @heroui/styles/components/accordion.css}.
 *
 * <p>Trigger px-4 py-4 text-sm medium with a 16 px chevron indicator that rotates 180° when
 * expanded; items are separated by a 1 px line; the panel animates height and opacity over 200 ms
 * (ease-out-quad for the height, ease-out for the opacity). Only one item is expanded unless
 * {@link #allowsMultiple(boolean)}.
 */
public final class Accordion extends VBox {

	private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

	private final List<Item> items = new ArrayList<>();
	private boolean allowsMultiple;

	public Accordion() {
		getStyleClass().add("accordion");
		setSpacing(0);
	}

	/** Adds an item with a text trigger and body content. */
	public Item addItem(String title, Node content) {
		Item item = new Item(title, content);
		items.add(item);
		getChildren().add(item);
		return item;
	}

	/** Allows several panels to be open at once. */
	public Accordion allowsMultiple(boolean allowsMultiple) {
		this.allowsMultiple = allowsMultiple;
		return this;
	}

	/** One accordion item (HeroUI Accordion.Item + Heading + Trigger + Panel + Body). */
	public final class Item extends VBox {

		private final HBox trigger = new HBox();
		private final Label titleLabel = new Label();
		private final Icon indicator = Icon.of(Icons.CaretDown, 16);
		private final VBox panel = new VBox();
		private final VBox body = new VBox();
		private final DoubleProperty panelHeight = new SimpleDoubleProperty(0);
		private boolean expanded;

		Item(String title, Node content) {
			getStyleClass().add("accordion__item");
			titleLabel.setText(title);
			indicator.getStyleClass().add("accordion__indicator");
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			trigger.getStyleClass().add("accordion__trigger");
			trigger.setAlignment(Pos.CENTER_LEFT);
			trigger.setSpacing(8);
			trigger.setFocusTraversable(true);
			trigger.getChildren().addAll(titleLabel, spacer, indicator);
			trigger.setOnMouseClicked(event -> toggle());
			trigger.setOnKeyPressed(event -> {
				if (event.getCode() == javafx.scene.input.KeyCode.SPACE
						|| event.getCode() == javafx.scene.input.KeyCode.ENTER) {
					toggle();
					event.consume();
				}
			});

			panel.getStyleClass().add("accordion__panel");
			body.getStyleClass().add("accordion__body");
			if (content != null) {
				body.getChildren().add(content);
			}
			panel.getChildren().add(body);
			panel.setVisible(false);
			panel.setManaged(false);
			Rectangle clip = new Rectangle();
			clip.widthProperty().bind(panel.widthProperty());
			clip.heightProperty().bind(panel.heightProperty());
			panel.setClip(clip);
			panel.maxHeightProperty().bind(panelHeight);

			getChildren().addAll(trigger, panel);
		}

		/** Expands or collapses the item. */
		public void toggle() {
			setExpanded(!expanded);
		}

		public boolean isExpanded() {
			return expanded;
		}

		public void setExpanded(boolean expand) {
			if (expanded == expand) {
				return;
			}
			expanded = expand;
			trigger.pseudoClassStateChanged(SELECTED, expand);
			if (expand) {
				if (!allowsMultiple) {
					for (Item item : items) {
						if (item != this) {
							item.collapse();
						}
					}
				}
				panel.setManaged(true);
				panel.setVisible(true);
				applyCss();
				layout();
				double target = body.prefHeight(panel.getWidth() > 0 ? panel.getWidth() : getWidth());
				panelHeight.set(0);
				panel.setOpacity(0);
				Timeline height = new Timeline(new KeyFrame(Motion.ACCORDION,
						new KeyValue(panelHeight, target, Motion.EASE_OUT_QUAD)));
				FadeTransition fade = Motion.fadeIn(panel, Motion.ACCORDION, Motion.EASE_OUT);
				new ParallelTransition(height, fade).play();
			} else {
				collapse();
			}
		}

		private void collapse() {
			if (!expanded) {
				return;
			}
			expanded = false;
			trigger.pseudoClassStateChanged(SELECTED, false);
			double from = panel.getHeight();
			Timeline height = new Timeline(new KeyFrame(Motion.ACCORDION,
					new KeyValue(panelHeight, 0, Motion.EASE_OUT_QUAD)));
			FadeTransition fade = Motion.fadeOut(panel, Motion.ACCORDION, Motion.EASE_OUT);
			fade.setFromValue(panel.getOpacity());
			ParallelTransition collapse = new ParallelTransition(height, fade);
			collapse.setOnFinished(event -> {
				if (!expanded) {
					panel.setManaged(false);
					panel.setVisible(false);
					panel.setOpacity(1);
				}
			});
			panelHeight.set(from);
			collapse.play();
		}

		/** Replaces the body content. */
		public Item content(Node content) {
			body.getChildren().setAll(content);
			return this;
		}

		/** The panel (height measurement in tests). */
		public Region panel() {
			return panel;
		}
	}
}
