package dev.gravaai.ui.dashboard;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

/**
 * Search row of the dashboard (port of {@code DashboardToolbar.tsx}).
 *
 * <p>h-24 (96), px-7 (28) / lg:px-10 (40), gap-5 (20); search {@code h-11} (44) with a 14 px inset
 * magnifier; the import button is a secondary h-10 button with a 16 px icon.
 */
public final class DashboardToolbar extends HBox {

	private final Input search = new Input();

	public DashboardToolbar(DashboardModel model, DashboardDependencies dependencies) {
		getStyleClass().add("dashboard-toolbar");
		setAlignment(Pos.CENTER);
		setSpacing(20);

		search.getStyleClass().add("dashboard-search");
		search.setPrefHeight(44);
		search.setMaxWidth(Double.MAX_VALUE);
		search.textProperty().bindBidirectional(model.queryProperty());

		Icon magnifier = Icon.of(Icons.MagnifyingGlass, 16);
		magnifier.getStyleClass().add("dashboard-search__icon");
		magnifier.setIconColor(dev.gravaai.ui.kit.Theme.MUTED);
		StackPane searchHost = new StackPane(search, magnifier);
		searchHost.getStyleClass().add("dashboard-search-host");
		searchHost.setMaxWidth(Double.MAX_VALUE);
		StackPane.setAlignment(magnifier, Pos.CENTER_LEFT);
		StackPane.setMargin(magnifier, new Insets(0, 0, 0, 14));
		HBox.setHgrow(searchHost, Priority.ALWAYS);

		Button importButton = new Button("Import", Button.Variant.SECONDARY, Button.Size.SM);
		importButton.setGraphic(Icon.of(Icons.UploadSimple, 16));
		importButton.setContentDisplay(ContentDisplay.LEFT);
		importButton.setGraphicTextGap(8);
		importButton.getStyleClass().add("dashboard-import");
		importButton.setPrefHeight(40);
		// Dashboard.tsx overrides onImportFile with importMeeting in the Meetings section.
		importButton.setOnAction(event -> {
			if (model.isMeetings()) {
				dependencies.actions().importMeeting();
			} else {
				dependencies.actions().importFile();
			}
		});

		getChildren().addAll(searchHost, importButton);

		model.sectionProperty().addListener((observable, oldValue, newValue) -> updatePrompt(model));
		updatePrompt(model);
	}

	private void updatePrompt(DashboardModel model) {
		if (model.isRaw()) {
			search.setPromptText("Search raw files…");
			search.setAccessibleText("Search raw files");
		} else if (model.isMeetings()) {
			search.setPromptText("Search meetings…");
			search.setAccessibleText("Search meetings");
		} else {
			search.setPromptText("Search projects…");
			search.setAccessibleText("Search projects");
		}
	}
}
