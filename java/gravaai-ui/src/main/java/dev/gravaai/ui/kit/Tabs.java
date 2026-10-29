package dev.gravaai.ui.kit;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * HeroUI Tabs port (plan §5.12), source {@code src/components/ui/tabs.tsx} +
 * {@code @heroui/styles/components/tabs.css}.
 *
 * <p>List container {@code bg-default} radius 20, list padding 4, tabs 32 px high radius 24
 * px-4 text-sm medium; the segment indicator slides between tabs over 250 ms with the
 * ease-out-fluid curve. Panel p-2 with a 16 px gap above it.
 */
public final class Tabs extends VBox {

	private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

	private record Tab(String value, javafx.scene.control.ToggleButton button, @Nullable Node content) {}

	private final Track track = new Track();
	private final StackPane panel = new StackPane();
	private final List<Tab> tabs = new ArrayList<>();
	private String value = "";
	private Consumer<String> onValueChange = value -> {};

	public Tabs() {
		getStyleClass().add("tabs");
		setSpacing(16);
		panel.getStyleClass().add("tabs__panel");
		panel.setAlignment(Pos.TOP_LEFT);
		getChildren().addAll(track, panel);
	}

	/** Adds a tab with a text label and no panel content yet. */
	public javafx.scene.control.ToggleButton addTab(String tabValue, String label) {
		return addTab(tabValue, new Label(label));
	}

	/** Adds a tab whose button content is {@code labelNode}. */
	public javafx.scene.control.ToggleButton addTab(String tabValue, Node labelNode) {
		javafx.scene.control.ToggleButton button = new javafx.scene.control.ToggleButton();
		button.getStyleClass().add("tabs__tab");
		button.setGraphic(labelNode);
		button.setFocusTraversable(true);
		Tab tab = new Tab(tabValue, button, null);
		tabs.add(tab);
		button.setOnAction(event -> setValue(tabValue));
		track.rebuild();
		return button;
	}

	/** Panel content for a tab value. */
	public Tabs content(String tabValue, Node node) {
		for (int i = 0; i < tabs.size(); i++) {
			if (tabs.get(i).value().equals(tabValue)) {
				tabs.set(i, new Tab(tabValue, tabs.get(i).button(), node));
				if (tabValue.equals(value)) {
					showPanel();
				}
				return this;
			}
		}
		throw new IllegalArgumentException("Unknown tab: " + tabValue);
	}

	/** Selects a tab by value. */
	public void setValue(String tabValue) {
		if (tabValue == null || tabValue.equals(value)) {
			return;
		}
		value = tabValue;
		for (Tab tab : tabs) {
			boolean selected = tab.value().equals(value);
			tab.button().setSelected(selected);
			tab.button().pseudoClassStateChanged(SELECTED, selected);
		}
		track.moveIndicator(true);
		showPanel();
		onValueChange.accept(value);
	}

	public String value() {
		return value;
	}

	public Tabs onValueChange(Consumer<String> listener) {
		this.onValueChange = listener == null ? value -> {} : listener;
		return this;
	}

	private void showPanel() {
		for (Tab tab : tabs) {
			if (tab.value().equals(value)) {
				if (tab.content() == null) {
					panel.getChildren().clear();
				} else {
					panel.getChildren().setAll(tab.content());
				}
				return;
			}
		}
	}

	/** A tab with an icon (TabsTrigger with icon + label). */
	public javafx.scene.control.ToggleButton addTab(String tabValue, String label, String iconName) {
		HBox content = new HBox(8);
		content.setAlignment(Pos.CENTER);
		content.getChildren().addAll(Icon.of(iconName, 16), new Label(label));
		return addTab(tabValue, content);
	}

	/** The list container (for tests/snapshot). */
	public Region listContainer() {
		return track;
	}

	private final class Track extends Pane {
		private final DoubleProperty indicatorX = new SimpleDoubleProperty(0);
		private final DoubleProperty indicatorWidth = new SimpleDoubleProperty(0);
		private final Region indicator = new Region();

		Track() {
			getStyleClass().add("tabs__list-container");
			setMinHeight(40);
			setPrefHeight(40);
			setMaxHeight(40);
			indicator.getStyleClass().add("tabs__indicator");
			indicator.setMouseTransparent(true);
			getChildren().add(indicator);
			indicatorX.addListener((observable, oldValue, newValue) -> requestLayout());
			indicatorWidth.addListener((observable, oldValue, newValue) -> requestLayout());
		}

		void rebuild() {
			getChildren().removeIf(node -> node != indicator);
			for (Tab tab : tabs) {
				getChildren().add(tab.button());
			}
			requestLayout();
		}

		void moveIndicator(boolean animate) {
			Tab selected = null;
			for (Tab tab : tabs) {
				if (tab.value().equals(value)) {
					selected = tab;
					break;
				}
			}
			if (selected == null) {
				return;
			}
			Tab target = selected;
			if (target.button().getWidth() <= 0) {
				javafx.application.Platform.runLater(() -> moveIndicator(false));
				return;
			}
			double buttonX = target.button().getLayoutX();
			double buttonWidth = target.button().getWidth();
			if (!animate || indicatorWidth.get() <= 0) {
				indicatorX.set(buttonX);
				indicatorWidth.set(buttonWidth);
				return;
			}
			Timeline timeline = new Timeline(
					new KeyFrame(Duration.ZERO, new KeyValue(indicatorX, indicatorX.get()),
							new KeyValue(indicatorWidth, indicatorWidth.get())),
					new KeyFrame(Motion.TABS_INDICATOR, new KeyValue(indicatorX, buttonX, Motion.EASE_OUT_FLUID),
							new KeyValue(indicatorWidth, buttonWidth, Motion.EASE_OUT_FLUID)));
			timeline.play();
		}

		@Override
		protected void layoutChildren() {
			double x = 4;
			double y = 4;
			double height = 32;
			for (Tab tab : tabs) {
				javafx.scene.control.ToggleButton button = tab.button();
				button.applyCss();
				double width = Math.max(button.prefWidth(-1), 48);
				button.resizeRelocate(x, y, width, height);
				x += width;
			}
			setPrefWidth(x + 4);
			indicator.resizeRelocate(indicatorX.get(), y, indicatorWidth.get(), height);
		}
	}
}
