package dev.gravaai.ui.editor;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.function.Consumer;

import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Popover;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Projects popover (port of {@code ProjectBrowserDialog.tsx}): the list of saved projects with
 * thumbnails, current-project marker and dates, plus Import; each row tells the app to open that
 * project. Anchored to the header's Home button like the React {@code projectBrowserTriggerRef}.
 */
public final class ProjectBrowserDialog {

	/** One saved project ({@code ProjectLibraryEntry} subset the browser needs). */
	public record Entry(
			Path path, String name, long updatedAt, Path thumbnailPath, boolean current) {}

	private final Popover popover;
	private final Consumer<Path> onOpenProject;
	private final Runnable onImportFile;
	private final VBox content = new VBox(10);
	private final VBox rows = new VBox(2);
	private List<Entry> entries = List.of();

	public ProjectBrowserDialog(Node anchor, List<Entry> entries, Consumer<Path> onOpenProject,
			Runnable onImportFile) {
		this.entries = entries == null ? List.of() : entries;
		this.onOpenProject = onOpenProject == null ? path -> { } : onOpenProject;
		this.onImportFile = onImportFile;
		popover = new Popover(anchor);
		content.setPrefWidth(340);
		content.setPadding(new Insets(12));
		popover.content(content);
		rebuild();
	}

	/** Replaces the project list and re-renders the rows. */
	public void setEntries(List<Entry> nextEntries) {
		this.entries = nextEntries == null ? List.of() : nextEntries;
		rebuild();
	}

	public void toggle() {
		popover.toggle();
	}

	public void open() {
		popover.show();
	}

	public void close() {
		popover.hide();
	}

	public Popover popover() {
		return popover;
	}

	/** Opens the popover (used by the shell's Home button). */
	public static Node anchorOf(ProjectBrowserDialog dialog) {
		return dialog.popover.trigger();
	}

	private void rebuild() {
		content.getChildren().clear();
		Label title = new Label("Projects");
		title.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: -g-fg;");
		HBox header = new HBox(8, title);
		header.setAlignment(Pos.CENTER_LEFT);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		header.getChildren().add(spacer);
		if (onImportFile != null) {
			Button importButton = new Button("Import", Button.Variant.SECONDARY, Button.Size.SM);
			importButton.setOnAction(event -> onImportFile.run());
			header.getChildren().add(importButton);
		}
		content.getChildren().add(header);

		rows.getChildren().clear();
		if (entries.isEmpty()) {
			Label empty = new Label("No saved projects yet");
			empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			empty.setPadding(new Insets(24, 0, 24, 0));
			empty.setMaxWidth(Double.MAX_VALUE);
			empty.setAlignment(Pos.CENTER);
			rows.getChildren().add(empty);
		} else {
			for (Entry entry : entries) {
				rows.getChildren().add(row(entry));
			}
		}
		ScrollPane scroll = new ScrollPane(rows);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setMaxHeight(320);
		scroll.setStyle("-fx-background-color: transparent;");
		content.getChildren().add(scroll);
	}

	private Node row(Entry entry) {
		Button button = new Button("", Button.Variant.GHOST, Button.Size.SM);
		button.setMaxWidth(Double.MAX_VALUE);
		button.setAlignment(Pos.CENTER_LEFT);
		button.setPadding(new Insets(8));
		button.setOnAction(event -> {
			onOpenProject.accept(entry.path());
			close();
		});

		HBox row = new HBox(10);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setMinWidth(0);

		Region thumbnail = new Region();
		thumbnail.setMinSize(72, 40);
		thumbnail.setPrefSize(72, 40);
		thumbnail.setMaxSize(72, 40);
		thumbnail.setStyle("-fx-background-color: -g-default; -fx-background-radius: 6px;");
		if (entry.thumbnailPath() != null && entry.thumbnailPath().toFile().isFile()) {
			ImageView image = new ImageView(
					new Image(entry.thumbnailPath().toUri().toString(), 72, 40, false, true, true));
			image.setFitWidth(72);
			image.setFitHeight(40);
			ImageView layered = image;
			layered.setStyle("-fx-background-radius: 6px;");
			StackPaneHost host = new StackPaneHost(layered);
			host.setMinSize(72, 40);
			host.setPrefSize(72, 40);
			host.setMaxSize(72, 40);
			host.setStyle("-fx-background-color: -g-default; -fx-background-radius: 6px;");
			row.getChildren().addAll(host, texts(entry));
		} else {
			Label none = new Label("No preview");
			none.setStyle("-fx-font-size: 10px; -fx-text-fill: -g-muted;");
			Region placeholder = new Region();
			placeholder.setMinSize(72, 40);
			placeholder.setStyle("-fx-background-color: -g-default; -fx-background-radius: 6px;");
			javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(placeholder,
					none);
			stack.setMinSize(72, 40);
			stack.setPrefSize(72, 40);
			stack.setMaxSize(72, 40);
			row.getChildren().addAll(stack, texts(entry));
		}
		button.setGraphic(row);
		button.getStyleClass().add("editor-project-row");
		return button;
	}

	private Node texts(Entry entry) {
		Label name = new Label(entry.name());
		name.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-fg;");
		Label subtitle = new Label(entry.current() ? "Current project"
				: formatDate(entry.updatedAt()));
		subtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: -g-muted;");
		VBox texts = new VBox(2, name, subtitle);
		HBox.setHgrow(texts, Priority.ALWAYS);
		texts.setMinWidth(0);
		return texts;
	}

	/** {@code new Date(updatedAt).toLocaleDateString()} with the JVM locale. */
	static String formatDate(long updatedAt) {
		LocalDate date = Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault()).toLocalDate();
		return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
	}

	/** Small StackPane subclass so the cell style applies without importing in the row builder. */
	private static final class StackPaneHost extends javafx.scene.layout.StackPane {
		StackPaneHost(Node child) {
			super(child);
		}
	}
}
