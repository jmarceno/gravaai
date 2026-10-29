package dev.gravaai.ui.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Kit;
import dev.gravaai.ui.kit.ColorPicker;
import dev.gravaai.ui.kit.Popover;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Navigation rail of the dashboard (port of {@code DashboardSidebar.tsx}, {@code FolderRow.tsx},
 * {@code FolderColors.tsx} and {@code SidebarCards.tsx}).
 *
 * <p>w-48 (192) / lg:w-56 (224), px-4 (16), pt-12 (48): logo row, "Record new" button, Home/Raw/
 * Meetings nav, the folder list with colour and rename menus, then Settings. The code-only sidebar
 * announcements are disabled in the React source, so this port renders nothing for them.
 */
public final class DashboardSidebar extends VBox {

	private final DashboardModel model;
	private final DashboardDependencies dependencies;
	private final Map<String, Button> navButtons = new LinkedHashMap<>();
	private final VBox folderRows = new VBox(4);

	public DashboardSidebar(DashboardModel model, DashboardDependencies dependencies) {
		this.model = model;
		this.dependencies = dependencies;
		getStyleClass().add("dashboard-sidebar");

		HBox logo = new HBox(10);
		logo.getStyleClass().add("dashboard-logo");
		logo.setAlignment(Pos.CENTER_LEFT);
		// The app logo (same art as the window/AppImage icon), not a generic glyph.
		javafx.scene.image.ImageView logoIcon = new javafx.scene.image.ImageView(new javafx.scene.image.Image(
				DashboardSidebar.class.getResource("/app-icons/gravaai-128.png").toExternalForm(),
				32, 32, true, true));
		VBox markHost = new VBox(logoIcon);
		markHost.getStyleClass().add("dashboard-logo__mark-host");
		markHost.setAlignment(Pos.CENTER);
		Label logoText = new Label("Gravaai");
		logoText.getStyleClass().add("dashboard-logo__text");
		logo.getChildren().addAll(markHost, logoText);

		Button record = new Button("Record new", Button.Variant.DEFAULT, Button.Size.SM);
		record.getStyleClass().add("dashboard-record");
		record.setGraphic(Icon.of(Icons.Plus, 16));
		record.setContentDisplay(ContentDisplay.LEFT);
		record.setGraphicTextGap(8);
		record.setMaxWidth(Double.MAX_VALUE);
		record.setOnAction(event -> dependencies.actions().showRecordingHud());
		// The sidebar has no spacing; without a gap the focus ring (drawn 2px outside the button)
		// spilled onto the Home row.
		VBox.setMargin(record, new Insets(0, 0, 12, 0));

		VBox nav = new VBox(4);
		nav.getStyleClass().add("dashboard-nav");
		nav.getChildren().addAll(
				navButton("Home", Icons.House, DashboardSection.PROJECTS),
				navButton("Raw", Icons.File, DashboardSection.RAW),
				navButton("Meetings", Icons.UsersGroup, DashboardSection.MEETINGS));

		HBox foldersHeader = new HBox();
		foldersHeader.getStyleClass().add("dashboard-folders-header");
		foldersHeader.setAlignment(Pos.CENTER_LEFT);
		Label foldersTitle = new Label("Folders");
		foldersTitle.getStyleClass().add("dashboard-folders-title");
		Region folderSpacer = new Region();
		HBox.setHgrow(folderSpacer, Priority.ALWAYS);
		Button addFolder = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		addFolder.getStyleClass().add("dashboard-add-folder");
		addFolder.setGraphic(Icon.of(Icons.Plus, 14));
		addFolder.setAccessibleText("New folder");
		addFolder.setOnAction(event -> model.createFolder());
		foldersHeader.getChildren().addAll(foldersTitle, folderSpacer, addFolder);

		folderRows.getStyleClass().add("dashboard-folders");
		ScrollPane foldersScroll = new ScrollPane(folderRows);
		foldersScroll.getStyleClass().addAll("dashboard-folders-scroll", "custom-scrollbar");
		foldersScroll.setFitToWidth(true);
		foldersScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		foldersScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		VBox.setVgrow(foldersScroll, Priority.ALWAYS);

		VBox bottom = new VBox(4);
		bottom.getStyleClass().add("dashboard-sidebar-bottom");
		bottom.getChildren().add(navButton("Settings", Icons.GearSix, DashboardSection.SETTINGS));

		getChildren().addAll(logo, record, nav, foldersHeader, foldersScroll, bottom);

		model.folders().addListener((ListChangeListener<ProjectFolder>) change -> rebuildFolders());
		model.sectionProperty().addListener((observable, oldValue, newValue) -> updateActive());
		rebuildFolders();
		updateActive();
	}

	private Button navButton(String label, String iconName, DashboardSection section) {
		Button button = new Button(label, Button.Variant.GHOST, Button.Size.SM);
		button.getStyleClass().add("dashboard-nav__button");
		button.setMaxWidth(Double.MAX_VALUE);
		button.setAlignment(Pos.CENTER_LEFT);
		button.setGraphic(Icon.of(iconName, 18));
		button.setContentDisplay(ContentDisplay.LEFT);
		button.setGraphicTextGap(12);
		button.setPrefHeight(40);
		button.setOnAction(event -> model.setSection(section));
		navButtons.put(section.wire(), button);
		return button;
	}

	private void updateActive() {
		for (Map.Entry<String, Button> entry : navButtons.entrySet()) {
			boolean active = model.section().wire().equals(entry.getKey());
			entry.getValue().setAriaPressed(active);
			Kit.setClass(entry.getValue(), "dashboard-nav__button--active", active);
		}
		for (Node node : folderRows.getChildren()) {
			if (node instanceof FolderRow row) {
				row.setActive(model.section() instanceof DashboardSection.Folder folder
						&& folder.id().equals(row.folder().id()));
			}
		}
	}

	private void rebuildFolders() {
		List<Node> rows = new ArrayList<>();
		for (ProjectFolder folder : model.folders()) {
			rows.add(new FolderRow(folder));
		}
		folderRows.getChildren().setAll(rows);
		updateActive();
	}

	/** One folder row with the colour picker and the rename/remove menu. */
	private final class FolderRow extends HBox {

		private final ProjectFolder folder;

		FolderRow(ProjectFolder folder) {
			this.folder = folder;
			getStyleClass().add("folder-row");
			setSpacing(4);
			setAlignment(Pos.CENTER_LEFT);

			ColorPicker colorPicker = new ColorPicker("", folder.color());
			colorPicker.getStyleClass().remove("button--secondary");
			colorPicker.getStyleClass().addAll("button--ghost", "button--icon-only",
					"folder-row__color");
			Icon folderIcon = Icon.of(Icons.FolderSimple, 18, "fill");
			folderIcon.setIconColor(Color.web(folder.color()));
			colorPicker.setGraphic(folderIcon);
			colorPicker.setMinWidth(28);
			colorPicker.setPrefWidth(28);
			colorPicker.setAccessibleText("Change color for " + folder.name());
			List<String> palette = new ArrayList<>(ProjectFolder.COLORS);
			for (String custom : model.preferences().colors()) {
				if (!palette.contains(custom)) {
					palette.add(custom);
				}
			}
			colorPicker.colors(palette.toArray(String[]::new));
			colorPicker.onColorChange(color -> {
				String hex = colorPicker.value();
				model.addCustomColor(hex);
				model.setFolderColor(folder.id(), hex);
				folderIcon.setIconColor(color);
			});

			Button name = new Button(folder.name(), Button.Variant.GHOST, Button.Size.SM);
			name.getStyleClass().add("folder-row__name");
			name.setMaxWidth(Double.MAX_VALUE);
			name.setAlignment(Pos.CENTER_LEFT);
			HBox.setHgrow(name, Priority.ALWAYS);
			name.setOnAction(event -> model.setSection(DashboardSection.folder(folder.id())));
			name.setOnMouseClicked(event -> {
				if (event.getClickCount() == 2) {
					startRename(name);
				}
			});

			Button options = new Button("", Button.Variant.GHOST, Button.Size.ICON);
			options.getStyleClass().add("folder-row__options");
			options.setGraphic(Icon.of(Icons.DotsThree, 16));
			options.setAccessibleText("Options for " + folder.name());
			Popover optionsPopover = new Popover(options);
			optionsPopover.side(dev.gravaai.ui.kit.Anchors.Side.BOTTOM);
			VBox menu = new VBox(2);
			menu.getStyleClass().add("dashboard-menu");
			menu.getChildren().addAll(
					menuItem("Rename", () -> startRename(name)),
					menuItem("Remove folder", () -> model.removeFolder(folder.id())));
			optionsPopover.content(menu);

			getChildren().addAll(colorPicker, name, options);
		}

		ProjectFolder folder() {
			return folder;
		}

		void setActive(boolean active) {
			Kit.setClass(this, "folder-row--active", active);
		}

		private void startRename(Button name) {
			Input field = new Input(folder.name());
			field.getStyleClass().add("folder-row__rename");
			TextField text = field;
			getChildren().set(getChildren().indexOf(name), text);
			TextField raw = field;
			text.requestFocus();
			text.selectAll();
			Runnable finish = () -> {
				int index = getChildren().indexOf(raw);
				if (index >= 0) {
					getChildren().set(index, name);
				}
				model.renameFolder(folder.id(), raw.getText());
			};
			text.setOnAction(event -> finish.run());
			text.focusedProperty().addListener((observable, wasFocused, focused) -> {
				if (!focused) {
					finish.run();
				}
			});
		}

		private Button menuItem(String text, Runnable action) {
			Button item = new Button(text, Button.Variant.GHOST, Button.Size.SM);
			item.getStyleClass().add("dashboard-menu__item");
			item.setMaxWidth(Double.MAX_VALUE);
			item.setAlignment(Pos.CENTER_LEFT);
			item.setOnAction(event -> action.run());
			return item;
		}
	}
}
