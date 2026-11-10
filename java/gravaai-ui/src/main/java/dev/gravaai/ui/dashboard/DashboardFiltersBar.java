package dev.gravaai.ui.dashboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.concurrent.Threads;
import dev.gravaai.ui.i18n.UiI18n;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.DropdownMenu;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Kit;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Time filters, sort dropdown, trash mode and the selection row (port of
 * {@code DashboardFilters.tsx}).
 *
 * <p>Period chips are ghost buttons h-7 (28) rounded-lg (8) px-3 (12) text-xs (12); the selected
 * chip uses {@code bg-default/60}. The sort control is the HeroUI dropdown; the kit's Select keeps
 * the same overlay/menu styling until a DropdownMenu control exists.
 */
public final class DashboardFiltersBar extends VBox {

	private final List<Button> periodButtons = new ArrayList<>();
	private final Button trashButton;
	private final Button sortButton = new Button(UiI18n.t("dashboard:filters.lastEdited"),
			Button.Variant.GHOST, Button.Size.SM);
	private final VBox selectionRow = new VBox();
	private final Label selectedCount = new Label();
	private final Button exportSelected;
	private final Button deleteSelected;

	public DashboardFiltersBar(DashboardModel model, DashboardDependencies dependencies) {
		getStyleClass().add("dashboard-filters");
		setSpacing(12);

		HBox timeFilters = new HBox(8);
		timeFilters.setAlignment(Pos.CENTER_LEFT);
		timeFilters.setAccessibleText(UiI18n.t("dashboard:filters.timeFilters"));
		addPeriod(timeFilters, model, UiI18n.t("dashboard:filters.all"),
				DashboardLibraryFilters.Period.ALL);
		addPeriod(timeFilters, model, UiI18n.t("dashboard:filters.last7Days"),
				DashboardLibraryFilters.Period.WEEK);
		addPeriod(timeFilters, model, UiI18n.t("dashboard:filters.last30Days"),
				DashboardLibraryFilters.Period.MONTH);

		trashButton = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		trashButton.setGraphic(Icon.of(Icons.Trash, 16));
		Kit.classes(trashButton, "dashboard-trash-toggle");
		trashButton.setOnAction(event -> model.setSelecting(!model.selectingProperty().get()));
		timeFilters.getChildren().add(trashButton);

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		sortButton.getStyleClass().add("dashboard-sort");
		sortButton.setGraphic(Icon.of(Icons.CaretDown, 12));
		sortButton.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
		sortButton.setGraphicTextGap(8);
		sortButton.setPrefHeight(28);
		sortButton.setAccessibleText(UiI18n.t("dashboard:filters.sortProjects"));
		DropdownMenu sortMenu = new DropdownMenu(sortButton);
		sortMenu.item(UiI18n.t("dashboard:filters.lastEdited"),
				() -> model.setSort(DashboardLibraryFilters.Sort.RECENT));
		sortMenu.item(UiI18n.t("dashboard:filters.lastCreated"),
				() -> model.setSort(DashboardLibraryFilters.Sort.CREATED));
		sortMenu.item(UiI18n.t("dashboard:filters.name"),
				() -> model.setSort(DashboardLibraryFilters.Sort.NAME));

		HBox topRow = new HBox(12, timeFilters, spacer, sortButton);
		topRow.setAlignment(Pos.CENTER_LEFT);

		selectionRow.getStyleClass().add("dashboard-selection");
		selectionRow.setSpacing(12);
		Button selectAll = new Button(UiI18n.t("dashboard:filters.selectAll"),
				Button.Variant.GHOST, Button.Size.SM);
		selectAll.setOnAction(event -> model.selectAllVisible());
		Button cancel = new Button(UiI18n.t("common:actions.cancel"),
				Button.Variant.GHOST, Button.Size.SM);
		cancel.setOnAction(event -> model.setSelecting(false));
		exportSelected = new Button(UiI18n.t("dashboard:filters.exportSelected"),
				Button.Variant.SECONDARY, Button.Size.SM);
		exportSelected.getStyleClass().add("dashboard-export-selected");
		exportSelected.setOnAction(event -> {
			List<String> paths = List.copyOf(model.selectedPaths());
			if (!paths.isEmpty()) {
				dependencies.actions().exportProjectsToQueue(paths);
			}
		});
		deleteSelected = new Button(UiI18n.t("common:actions.delete"),
				Button.Variant.DESTRUCTIVE, Button.Size.SM);
		deleteSelected.setOnAction(event -> deleteSelection(model));
		HBox selectionControls = new HBox(12, selectAll, selectedCount, exportSelected,
				deleteSelected, cancel);
		selectionControls.setAlignment(Pos.CENTER_LEFT);
		selectionRow.getChildren().add(selectionControls);
		selectionRow.setVisible(false);
		selectionRow.setManaged(false);

		getChildren().addAll(topRow, selectionRow);

		model.selectingProperty().addListener((observable, oldValue, selected) -> {
			selectionRow.setVisible(selected);
			selectionRow.setManaged(selected);
			updateSelectionControls(model);
		});
		model.periodProperty().addListener((observable, oldValue, value) -> updatePeriods(model));
		model.sortProperty().addListener((observable, oldValue, value) -> {
			DashboardLibraryFilters.Sort next = value == null
					? DashboardLibraryFilters.Sort.RECENT : value;
			sortButton.setText(next == DashboardLibraryFilters.Sort.NAME
					? UiI18n.t("dashboard:filters.name")
					: next == DashboardLibraryFilters.Sort.CREATED
							? UiI18n.t("dashboard:filters.lastCreated")
							: UiI18n.t("dashboard:filters.lastEdited"));
		});
		model.sectionProperty().addListener((observable, oldValue, value) -> {
			trashButton.setAccessibleText(model.isRaw()
					? UiI18n.t("dashboard:filters.selectRawToRemove")
					: UiI18n.t("dashboard:filters.selectProjectsToDelete"));
			deleteSelected.setText(model.isRaw() ? UiI18n.t("dashboard:filters.remove")
					: UiI18n.t("common:actions.delete"));
		});
		model.selectedPaths().addListener((ListChangeListener<String>) change ->
				updateSelectionControls(model));
		updatePeriods(model);
	}

	private void deleteSelection(DashboardModel model) {
		List<String> paths = List.copyOf(model.selectedPaths());
		if (paths.isEmpty()) {
			return;
		}
		if (model.isRaw() || model.isMeetings()) {
			model.busyProperty().set(true);
			Threads.startVirtual("gravaai-dashboard-delete", () -> {
				try {
					model.deleteLibraryEntries(paths);
				} catch (IOException error) {
					Platform.runLater(() -> model.errorProperty().set(UiI18n.t(
							"dashboard:library.couldNotDelete",
							Map.of("error", String.valueOf(error.getMessage())))));
				} finally {
					Platform.runLater(() -> {
						model.setSelecting(false);
						model.busyProperty().set(false);
					});
				}
			});
			return;
		}
		DashboardDeleteDialog.show(this, paths, deleteRecordings -> {
			model.busyProperty().set(true);
			Threads.startVirtual("gravaai-dashboard-delete", () -> {
				try {
					model.trashProjects(paths, deleteRecordings);
					Platform.runLater(() -> model.setSelecting(false));
				} catch (IOException error) {
					Platform.runLater(() -> model.errorProperty().set(UiI18n.t(
							"dashboard:library.couldNotDelete",
							Map.of("error", String.valueOf(error.getMessage())))));
				} finally {
					Platform.runLater(() -> model.busyProperty().set(false));
				}
			});
		});
	}

	private void addPeriod(HBox row, DashboardModel model, String label,
			DashboardLibraryFilters.Period period) {
		Button button = new Button(label, Button.Variant.GHOST, Button.Size.SM);
		button.getStyleClass().add("period-button");
		button.setUserData(period.wire());
		button.setOnAction(event -> model.setPeriod(period));
		periodButtons.add(button);
		row.getChildren().add(button);
	}

	private void updatePeriods(DashboardModel model) {
		for (Button button : periodButtons) {
			boolean active = model.period().wire().equals(button.getUserData());
			Kit.setClass(button, "period-button--active", active);
			button.setAriaPressed(active);
		}
	}

	private void updateSelectionControls(DashboardModel model) {
		int count = model.selectedPaths().size();
		selectedCount.setText(UiI18n.t("dashboard:filters.selectedCount", java.util.Map.of("count", count)));
		boolean disabled = count == 0 || model.busyProperty().get();
		exportSelected.setDisable(disabled);
		deleteSelected.setDisable(disabled);
	}
}
