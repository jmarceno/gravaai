package dev.gravaai.ui.editor;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.project.ZoomDepth;
import dev.gravaai.core.project.ZoomMode;
import dev.gravaai.core.project.ZoomRegion;
import dev.gravaai.ui.editor.timeline.core.TimelineModel;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.ChoiceGroup;
import dev.gravaai.ui.kit.Label;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Inspector editor for the selected timeline zoom (port of the zoom section in the React
 * {@code SettingsPanel}). Changing the amount/mode and deleting go through
 * {@link ZoomCommands} so undo/redo and project persistence see the same state.
 */
public final class ZoomSettingsPanel extends VBox {

	private final EditorStore store;
	private final VBox content = new VBox(14);
	private ChoiceGroup modeGroup;
	private ChoiceGroup amountGroup;
	private Button deleteButton;

	public ZoomSettingsPanel(EditorStore store) {
		this.store = store;
		setSpacing(12);
		setFillWidth(true);
		getChildren().add(content);
		refresh();
	}

	/** Rebuilds the controls for the selected zoom (or the empty state). */
	public void refresh() {
		content.getChildren().clear();
		modeGroup = null;
		amountGroup = null;
		deleteButton = null;
		ZoomRegion region = selected();
		if (region == null) {
			Label empty = new Label("Select a zoom region to edit its amount.");
			empty.setWrapText(true);
			empty.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
			content.getChildren().add(empty);
			return;
		}

		ZoomMode mode = region.mode() == null ? ZoomMode.AUTO : region.mode();
		ZoomDepth depth = region.depth() == null ? ZoomDepth.DEFAULT : region.depth();

		Label modeLabel = sectionLabel("Mode");
		modeGroup = new ChoiceGroup();
		modeGroup.item(ZoomMode.AUTO.wire(), "Auto");
		modeGroup.item(ZoomMode.MANUAL.wire(), "Manual");
		modeGroup.setValue(mode.wire());
		modeGroup.onValueChange(value -> store.apply(
				new ZoomCommands.ChangeMode(ZoomMode.fromWire(value))));
		Label modeHint = new Label(mode == ZoomMode.MANUAL
				? "Set a fixed focus point for this zoom"
				: "Camera recenters when the cursor nears the edge of the zoomed view");
		modeHint.setWrapText(true);
		modeHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -g-muted;");

		Label amountLabel = sectionLabel("Amount");
		amountGroup = new ChoiceGroup();
		for (ZoomDepth option : ZoomDepth.values()) {
			amountGroup.item(Integer.toString(option.wire()),
					TimelineModel.formatZoomScale(option.scale()));
		}
		amountGroup.setValue(Integer.toString(depth.wire()));
		amountGroup.onValueChange(value -> store.apply(
				new ZoomCommands.ChangeDepth(ZoomDepth.fromWire(Integer.parseInt(value)))));

		deleteButton = new Button("Delete Zoom", Button.Variant.DESTRUCTIVE_SOFT,
				Button.Size.SM);
		deleteButton.setMaxWidth(Double.MAX_VALUE);
		deleteButton.setAlignment(Pos.CENTER_LEFT);
		deleteButton.setContentDisplay(ContentDisplay.LEFT);
		deleteButton.setOnAction(event -> store.apply(new ZoomCommands.Delete(region.id())));

		content.getChildren().addAll(modeLabel, modeGroup, modeHint, amountLabel, amountGroup,
				spacer(), deleteButton);
	}

	/** The amount chips in depth order (1.25x, 1.5x, 1.8x, 2.2x, 3.5x, 5x). */
	public ChoiceGroup amountGroup() {
		return amountGroup;
	}

	/** The Auto/Manual mode chips. */
	public ChoiceGroup modeGroup() {
		return modeGroup;
	}

	public Button deleteButton() {
		return deleteButton;
	}

	private ZoomRegion selected() {
		String id = store.selectedZoomId();
		if (id == null) {
			return null;
		}
		for (ZoomRegion region : store.state().zoomRegions()) {
			if (region.id().equals(id)) {
				return region;
			}
		}
		return null;
	}

	private static Label sectionLabel(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-font-size: 12px; -fx-font-weight: 600; -fx-text-fill: -g-muted;");
		return label;
	}

	private static Region spacer() {
		Region spacer = new Region();
		spacer.setMinHeight(6);
		spacer.setPadding(new Insets(3, 0, 3, 0));
		return spacer;
	}
}
