package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Separator;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Editor top bar (port of {@code layout/EditorHeader.tsx}): breadcrumb Home / project name, Clips
 * toggle, undo/redo, the Save button and the Export menu.
 */
public final class EditorHeaderView extends HBox {

	private final EditorStore store;
	private final EditorViewActions actions;
	private final StackPane titleHost = new StackPane();
	private final Label projectName = new Label("Untitled Project");
	private final Button undo;
	private final Button redo;
	private final Button clips;
	private final Button home;
	private final Button save;
	private final Button export;
	private String projectNameValue = "Untitled Project";

	public EditorHeaderView(EditorStore store, EditorViewActions actions) {
		this.store = store;
		this.actions = actions;
		getStyleClass().add("editor-header");
		setAlignment(Pos.CENTER_LEFT);

		home = new Button("Home", Button.Variant.GHOST, Button.Size.SM);
		home.icon(Icons.House, "fill");
		home.setGraphicTextGap(8);
		home.setContentDisplay(ContentDisplay.LEFT);
		home.setOnAction(event -> actions.goHome());

		Label slash = new Label("/");
		slash.getStyleClass().add("editor-header__slash");

		projectName.getStyleClass().add("editor-header__project-name");
		titleHost.setAlignment(Pos.CENTER_LEFT);
		titleHost.getChildren().add(projectButton());

		Separator divider = Separator.vertical();
		divider.setMinHeight(20);
		divider.setPrefHeight(20);

		clips = new Button("Clips", Button.Variant.SECONDARY, Button.Size.SM);
		clips.icon(Icons.FilmStrip);
		clips.getStyleClass().add("editor-header__clips");
		clips.setGraphicTextGap(8);
		clips.setContentDisplay(ContentDisplay.LEFT);
		clips.setOnAction(event -> {
			boolean open = !this.clipsOpen;
			if (onToggleClips != null) {
				onToggleClips.accept(open);
			} else {
				actions.toggleClips(open);
				setClipsOpen(open);
			}
		});

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		undo = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		undo.icon(Icons.ArrowCounterClockwise);
		undo.setOnAction(event -> {
			store.undo();
			refresh();
		});
		dev.gravaai.ui.kit.Tooltip.install(undo, "Undo");

		redo = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		redo.icon(Icons.ArrowClockwise);
		redo.setOnAction(event -> {
			store.redo();
			refresh();
		});
		dev.gravaai.ui.kit.Tooltip.install(redo, "Redo");

		save = new Button("Save", Button.Variant.SECONDARY, Button.Size.SM);
		save.getStyleClass().add("editor-header__save");
		save.icon(Icons.DownloadSimple);
		save.setGraphicTextGap(8);
		save.setContentDisplay(ContentDisplay.LEFT);
		save.setAccessibleText("Save project");
		save.setOnAction(event -> {
			if (onSave != null) {
				onSave.run();
			} else {
				actions.save();
			}
		});
		// Changes that are not on disk tint the button (kit accent-soft, the same language as the
		// active rail icons), so the state reads without moving anything.
		dev.gravaai.ui.kit.Tooltip.install(save, "Save (Ctrl + S)");

		export = new Button("Export", Button.Variant.DEFAULT, Button.Size.MD);
		export.getStyleClass().add("editor-header__export");
		export.icon(Icons.CloudArrowUp, "fill");
		export.setGraphicTextGap(8);
		export.setContentDisplay(ContentDisplay.LEFT);
		export.setOnAction(event -> {
			if (openExportMenu != null) {
				openExportMenu.run();
			}
		});

		browser = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		browser.icon(Icons.CaretDown, "regular");
		browser.setAccessibleText("Projects");
		browser.setOnAction(event -> openProjectBrowser());

		getChildren().addAll(home, slash, titleHost, browser, divider, clips, spacer, undo, redo,
				save, export);
		refresh();
	}

	private boolean clipsOpen;
	private boolean dirty;
	private @Nullable Runnable openExportMenu;
	private @Nullable Runnable onSave;
	private @Nullable Runnable projectBrowserOpener;
	private Button browser;
	private java.util.function.@Nullable Consumer<Boolean> onToggleClips;

	/** Replaces the Clips toggle action (the shell owns the panel swap). */
	public void setOnToggleClips(java.util.function.Consumer<Boolean> onToggleClips) {
		this.onToggleClips = onToggleClips;
	}

	private Button projectButton() {
		Button button = new Button();
		button.getStyleClass().add("editor-header__project");
		button.setGraphic(projectName);
		button.setOnAction(event -> startRename());
		return button;
	}

	/** Sets the project display name shown in the breadcrumb. */
	public void setProjectName(String name) {
		projectNameValue = name == null || name.isBlank() ? "Untitled Project" : name;
		projectName.setText(projectNameValue);
	}

	public Button browserButton() {
		return browser;
	}

	public String projectName() {
		return projectNameValue;
	}

	/** The Home button: goes to the dashboard. */
	public Button homeButton() {
		return home;
	}

	/** Replaces the Home button action (shell goes to the dashboard). */
	public void setOnGoHome(Runnable goHome) {
		home.setOnAction(event -> {
			if (goHome != null) {
				goHome.run();
			} else {
				actions.goHome();
			}
		});
	}

	/** The project title host: the shell anchors the project-browser popover to it. */
	public StackPane projectTitleHost() {
		return titleHost;
	}

	/** Replaces the project-browser trigger (shell opens the project browser popover). */
	public void setOnOpenProjects(Runnable openProjects) {
		projectBrowserOpener = openProjects;
	}

	/** Opens the project browser via the shell-wired trigger (project title click). */
	public void openProjectBrowser() {
		if (projectBrowserOpener != null) {
			projectBrowserOpener.run();
		} else {
			actions.openProjectBrowser();
		}
	}

	/** The Export button: the shell anchors the export popover to it. */
	public Button exportButton() {
		return export;
	}

	/** Replaces the Export button action (the shell owns the export popover). */
	public void setOnOpenExportMenu(Runnable openExportMenu) {
		this.openExportMenu = openExportMenu;
	}

	/** The Save button, next to Export: the app saves the project through it. */
	public Button saveButton() {
		return save;
	}

	/** Replaces the Save button action (the app owns saving; the default uses the actions seam). */
	public void setOnSave(Runnable onSave) {
		this.onSave = onSave;
	}

	/** Marks changes that are not on disk: tints the Save button and says so to readers. */
	public void setDirty(boolean dirty) {
		this.dirty = dirty;
		save.getStyleClass().remove("is-dirty");
		if (dirty) {
			save.getStyleClass().add("is-dirty");
		}
		save.setAccessibleText(dirty
				? "Save project, unsaved changes"
				: "Save project");
	}

	/** True while the project has changes that are not on disk. */
	public boolean dirty() {
		return dirty;
	}

	/** Highlights the Clips toggle to match the clips panel state. */
	public void setClipsOpen(boolean open) {
		this.clipsOpen = open;
		clips.getStyleClass().remove("is-active");
		if (open) {
			clips.getStyleClass().add("is-active");
		}
	}

	public boolean clipsOpen() {
		return clipsOpen;
	}

	private void startRename() {
		Input input = new Input();
		input.setText(projectNameValue);
		input.getStyleClass().add("editor-header__rename");
		titleHost.getChildren().setAll(input);
		input.requestFocus();
		input.selectAll();

		input.setOnAction(event -> {
			String value = input.getText() == null ? "" : input.getText().trim();
			titleHost.getChildren().setAll(projectButton());
			if (!value.isEmpty() && !value.equals(projectNameValue)) {
				actions.renameProject(value);
			}
		});
		input.setOnKeyPressed(event -> {
			if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
				titleHost.getChildren().setAll(projectButton());
			}
		});
	}

	/** Refreshes the undo/redo enabled state. */
	public void refresh() {
		undo.setDisable(store.history().past().isEmpty());
		redo.setDisable(store.history().future().isEmpty());
	}
}
