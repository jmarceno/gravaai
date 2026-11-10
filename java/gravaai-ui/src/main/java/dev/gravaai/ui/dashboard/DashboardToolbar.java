package dev.gravaai.ui.dashboard;

import org.jspecify.annotations.Nullable;

import dev.gravaai.ui.i18n.UiI18n;
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
	private final Button queueButton;
	private final javafx.scene.control.Label queueCountBadge = new javafx.scene.control.Label();
	private final StackPane queueButtonHost = new StackPane();
	private @Nullable Runnable openQueuePanel;

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

		queueButton = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		queueButton.getStyleClass().add("dashboard-queue");
		queueButton.icon(Icons.FilmSlate);
		queueButton.setAccessibleText(UiI18n.t("dialogs:export.queue.title"));
		dev.gravaai.ui.kit.Tooltip.install(queueButton, UiI18n.t("dialogs:export.queue.title"));
		queueCountBadge.getStyleClass().add("export-queue-badge");
		queueCountBadge.setMouseTransparent(true);
		queueCountBadge.setVisible(false);
		queueButtonHost.getChildren().addAll(queueButton, queueCountBadge);
		StackPane.setAlignment(queueCountBadge, Pos.TOP_RIGHT);
		StackPane.setMargin(queueCountBadge, new Insets(-5, -5, 0, 0));

		Button importButton = new Button(UiI18n.t("dashboard:toolbar.import"),
				Button.Variant.SECONDARY, Button.Size.SM);
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

		getChildren().addAll(searchHost, queueButtonHost, importButton);

		model.sectionProperty().addListener((observable, oldValue, newValue) -> updatePrompt(model));
		updatePrompt(model);
	}

	/** The export-queue button, left of Import: the app anchors the queue popover to it. */
	public javafx.scene.control.Button queueButton() {
		return queueButton;
	}

	/** Replaces the queue button action (the app owns the queue panel). */
	public void setOnOpenQueue(Runnable onOpenQueue) {
		openQueuePanel = onOpenQueue;
		queueButton.setOnAction(event -> {
			Runnable open = openQueuePanel;
			if (open != null) {
				open.run();
			}
		});
	}

	/** Shows how many exports are queued or running; 0 hides the badge. */
	public void setQueueCount(int count) {
		int shown = Math.max(0, count);
		queueCountBadge.setText(shown > 99 ? "99+" : String.valueOf(shown));
		queueCountBadge.setVisible(shown > 0);
	}

	private void updatePrompt(DashboardModel model) {
		if (model.isRaw()) {
			search.setPromptText(UiI18n.t("dashboard:toolbar.searchRaw"));
			search.setAccessibleText(UiI18n.t("dashboard:toolbar.searchRawAccessible"));
		} else if (model.isMeetings()) {
			search.setPromptText(UiI18n.t("dashboard:toolbar.searchMeetings"));
			search.setAccessibleText(UiI18n.t("dashboard:toolbar.searchMeetingsAccessible"));
		} else {
			search.setPromptText(UiI18n.t("dashboard:toolbar.searchProjects"));
			search.setAccessibleText(UiI18n.t("dashboard:toolbar.searchProjectsAccessible"));
		}
	}
}
