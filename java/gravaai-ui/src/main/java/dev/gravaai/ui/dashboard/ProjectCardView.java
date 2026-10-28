package dev.gravaai.ui.dashboard;

import java.util.List;

import javafx.animation.PauseTransition;

import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.RecordingEntry;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.DropdownMenu;
import dev.gravaai.ui.kit.Skeleton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

/**
 * One project / raw-file / meeting card (port of {@code ProjectCard.tsx},
 * {@code ProjectThumbnail.tsx}, {@code MeetingThumbnail.tsx}, {@code ProjectFolderChips.tsx} and
 * {@code RawRecordings.tsx}'s thumbnail).
 *
 * <p>aspect-[4/3] rounded-xl thumbnail, caption pt-5 (20) with the name at 12 px/500, the
 * {@code month day} date at 11 px and the folder chips; the options button opens the kit popover
 * with the same actions ({@code Open meeting} / {@code Move to trash} for meetings, port of
 * {@code entry.meeting ? ...}). Project and raw-recording cards play the muted five-second hover
 * preview through {@link ProjectHoverPreview} (pointer enter/leave, mouse only, skipped while
 * selecting/busy): raw cards play the recording itself like React's {@code RawThumbnail}
 * {@code <video>}; audio-only raw entries and meeting cards never preview.
 */
final class ProjectCardView extends VBox {

	private final DashboardModel model;
	private final DashboardDependencies dependencies;
	private final ProjectLibraryEntry entry;
	private final VBox caption = new VBox();
	private final Label nameLabel = new Label();
	private final VBox nameHost = new VBox();
	/** The thumbnail host (package-visible for the hover-preview test). */
	StackPane thumbnailHost;	/** {@code hovering} in ProjectCard.tsx: mouse-only pointer enter/leave over the whole card. */
	private boolean hovering;
	/** The last computed {@code previewActive} ({@code hovering && !selecting && !busy}). */
	private boolean previewActive;
	private PauseTransition hoverTimer;
	private ProjectHoverPreview hoverPreview;
	private final javafx.beans.InvalidationListener hoverGuardListener = observable ->
			refreshHoverPreview();
	private boolean hoverGuardAttached;

	ProjectCardView(DashboardModel model, DashboardDependencies dependencies, ProjectLibraryEntry entry) {
		this.model = model;
		this.dependencies = dependencies;
		this.entry = entry;
		getStyleClass().add("project-card");
		setSpacing(20);

		StackPane thumbnail = buildThumbnail();
		getChildren().add(thumbnail);

		caption.getStyleClass().add("project-card__caption");
		caption.setSpacing(4);
		nameHost.getChildren().add(nameLabel);
		nameLabel.getStyleClass().add("project-card__name");
		nameLabel.setText(entry.name());
		nameLabel.setMaxWidth(Double.MAX_VALUE);
		nameLabel.setTooltip(new javafx.scene.control.Tooltip(entry.name()));

		HBox meta = new HBox(8);
		meta.setAlignment(Pos.CENTER_LEFT);
		Label date = new Label(DashboardDates.shortDate(entry.updatedAt()));
		date.getStyleClass().add("project-card__date");
		meta.getChildren().add(date);
		meta.getChildren().add(buildFolderChips());
		meta.getChildren().add(buildAddFolderButton());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		meta.getChildren().add(spacer);
		meta.getChildren().add(buildOptionsButton());

		caption.getChildren().addAll(nameHost, meta);
		getChildren().add(caption);

		attachHoverPreview();
	}

	private StackPane buildThumbnail() {
		StackPane host = new StackPane();
		thumbnailHost = host;
		host.getStyleClass().add("project-thumbnail");
		host.setPrefSize(278, 209);
		host.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		host.setMaxSize(Double.MAX_VALUE, Region.USE_PREF_SIZE);
		Rectangle clip = new Rectangle();
		clip.setArcWidth(24);
		clip.setArcHeight(24);
		clip.widthProperty().bind(host.widthProperty());
		clip.heightProperty().bind(host.heightProperty());
		host.setClip(clip);
		host.setCursor(Cursor.HAND);
		host.setOnMouseClicked(event -> {
			if (model.selectingProperty().get()) {
				model.toggleSelected(entry.path());
				return;
			}
			openEntry();
		});
		String tooltip = model.selectingProperty().get() ? "Select " + entry.name() : entry.name();
		host.setAccessibleText(tooltip);

		Node artwork = buildArtwork();
		host.getChildren().add(artwork);
		if (model.selectingProperty().get()) {
			host.getChildren().add(buildSelectionMark());
		}
		return host;
	}

	// ── hover preview (ProjectCard.tsx previewActive + ProjectThumbnail.tsx) ────────

	/** Wires the mouse enter/leave state machine; listeners live while the card is in a scene. */
	private void attachHoverPreview() {
		// React guards on pointerType === "mouse"; JavaFX mouse events carry no pointer type,
		// so a touch tap also enters — the 300 ms delay plus the tap's click (open/toggle)
		// cleans the preview up before or right after it starts.
		setOnMouseEntered(event -> setHovering(true));
		setOnMouseExited(event -> setHovering(false));
		sceneProperty().addListener((observable, oldValue, newValue) -> {
			if (newValue != null) {
				attachHoverGuard();
			} else {
				detachHoverPreview();
			}
		});
		attachHoverGuard();
	}

	private void attachHoverGuard() {
		if (hoverGuardAttached) {
			return;
		}
		hoverGuardAttached = true;
		model.selectingProperty().addListener(hoverGuardListener);
		model.busyProperty().addListener(hoverGuardListener);
	}

	private void setHovering(boolean value) {
		if (hovering == value) {
			return;
		}
		hovering = value;
		refreshHoverPreview();
	}

	/**
	 * React {@code previewActive = hovering && !selecting && !busy}: only the edges drive the
	 * 300 ms load timer, so a busy/selecting change while the pass already self-finished does
	 * not restart it (the React effect does not re-run while {@code previewActive} is unchanged).
	 */
	private void refreshHoverPreview() {
		RecordingEntry raw = model.rawSource(entry.path());
		boolean active = hovering && (raw == null || !raw.isAudio()) && meetingSource() == null
				&& !model.selectingProperty().get() && !model.busyProperty().get();
		boolean was = previewActive;
		previewActive = active;
		if (active && !was) {
			if (hoverTimer == null) {
				hoverTimer = new PauseTransition(javafx.util.Duration.millis(300));
				hoverTimer.setOnFinished(event -> startHoverPreview());
			}
			hoverTimer.playFromStart();
		} else if (!active && was) {
			stopHoverTimer();
			stopHoverPreview();
		}
	}

	private void stopHoverTimer() {
		if (hoverTimer != null) {
			hoverTimer.stop();
		}
	}

	private void startHoverPreview() {
		if (hoverPreview != null) {
			return;
		}
		RecordingEntry raw = model.rawSource(entry.path());
		ProjectHoverPreview preview = raw != null
				? new ProjectHoverPreview(entry.path(), this::unmountHoverPreview)
				: new ProjectHoverPreview(dependencies.projects(), entry.path(),
						this::unmountHoverPreview);
		hoverPreview = preview;
		thumbnailHost.getChildren().add(preview);
		preview.start();
	}

	private void unmountHoverPreview() {
		if (hoverPreview != null) {
			thumbnailHost.getChildren().remove(hoverPreview);
			hoverPreview = null;
		}
	}

	private void stopHoverPreview() {
		if (hoverPreview != null) {
			hoverPreview.stop();
		}
	}

	/** The card left the scene: drop the model listeners and stop the pass. */
	private void detachHoverPreview() {
		if (hoverGuardAttached) {
			hoverGuardAttached = false;
			model.selectingProperty().removeListener(hoverGuardListener);
			model.busyProperty().removeListener(hoverGuardListener);
		}
		setHovering(false);
		stopHoverTimer();
		stopHoverPreview();
	}

	private Node buildArtwork() {
		MeetingLibraryEntry meeting = meetingSource();
		if (meeting != null) {
			// ProjectCard.tsx: entry.meeting ? <MeetingThumbnail/> : ... (before the raw branch).
			MeetingThumbnail thumbnail = new MeetingThumbnail(meeting);
			thumbnail.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
			return thumbnail;
		}
		if (entry.thumbnailPath() != null) {
			ImageView imageView = new ImageView();
			imageView.setPreserveRatio(false);
			imageView.setSmooth(true);
			imageView.setFitWidth(278);
			imageView.setFitHeight(209);
			imageView.getStyleClass().add("project-thumbnail__image");
			Image image = new Image(java.nio.file.Path.of(entry.thumbnailPath()).toUri().toString(),
					278, 209, true, true, true);
			if (image.isError()) {
				return placeholderIcon(Icons.ImageSquare);
			}
			imageView.setImage(image);
			imageView.setClip(new Rectangle(278, 209));
			return imageView;
		}
		RecordingEntry raw = model.rawSource(entry.path());
		if (raw != null && raw.isAudio()) {
			return placeholderIcon(Icons.MusicNotes);
		}
		if (raw != null) {
			return placeholderIcon(Icons.FilmStrip);
		}
		Skeleton skeleton = new Skeleton();
		skeleton.getStyleClass().add("project-thumbnail__skeleton");
		skeleton.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
		return skeleton;
	}

	private static Node placeholderIcon(String iconName) {
		Region icon = Icon.of(iconName, 32, "fill");
		icon.getStyleClass().add("project-thumbnail__icon");
		return icon;
	}

	private Node buildSelectionMark() {
		StackPane mark = new StackPane();
		mark.getStyleClass().add("project-card__selection");
		if (model.isSelected(entry.path())) {
			mark.getStyleClass().add("project-card__selection--checked");
			mark.getChildren().add(Icon.of(Icons.Check, 14, "bold"));
		}
		StackPane.setAlignment(mark, Pos.TOP_RIGHT);
		StackPane.setMargin(mark, new Insets(8));
		return mark;
	}

	private Node buildFolderChips() {
		HBox chips = new HBox(6);
		chips.setAlignment(Pos.CENTER_LEFT);
		List<ProjectFolder> assigned = model.folders().stream()
				.filter(folder -> folder.paths().contains(entry.path()))
				.toList();
		for (ProjectFolder folder : assigned) {
			chips.getChildren().add(folderChip(folder));
		}
		return chips;
	}

	private Button folderChip(ProjectFolder folder) {
		Button chip = new Button(folder.name(), Button.Variant.GHOST, Button.Size.SM);
		chip.getStyleClass().add("folder-chip");
		Icon icon = Icon.of(Icons.FolderSimple, 12, "fill");
		icon.setIconColor(javafx.scene.paint.Color.web(folder.color()));
		chip.setGraphic(icon);
		chip.setGraphicTextGap(6);
		chip.setOnAction(event -> model.assignFolder(entry.path(), folder.id()));
		return chip;
	}

	private Node buildAddFolderButton() {
		Button add = new Button("Add folder", Button.Variant.GHOST, Button.Size.SM);
		add.getStyleClass().add("add-folder-button");
		add.setGraphic(Icon.of(Icons.Plus, 12));
		add.setGraphicTextGap(6);
		DropdownMenu menu = new DropdownMenu(add);
		if (model.folders().isEmpty()) {
			menu.label("Create a folder in the sidebar");
		}
		for (ProjectFolder folder : model.folders()) {
			menu.item(folder.name(), () -> model.assignFolder(entry.path(), folder.id()));
		}
		if (!model.folders().isEmpty()) {
			menu.separator();
			menu.item("Remove from all folders", () -> model.assignFolder(entry.path(), "none"));
		}
		return add;
	}

	private Node buildOptionsButton() {
		Button options = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		options.getStyleClass().add("project-card__options");
		options.setGraphic(Icon.of(Icons.DotsThree, 20, "bold"));
		options.setAccessibleText("Options for " + entry.name());
		DropdownMenu menu = new DropdownMenu(options);
		menu.item(meetingSource() != null ? "Open meeting"
				: rawSource() ? "Preview file" : "Open project", this::openEntry);
		menu.item("Rename", this::startRename);
		menu.item("Show in folder", () -> dependencies.actions().revealInFolder(entry.path()));
		menu.separator();
		menu.dangerItem(meetingSource() != null ? "Move to trash"
				: rawSource() ? "Remove from library" : "Delete project", this::deleteEntry);
		return options;
	}

	private boolean rawSource() {
		return model.rawSource(entry.path()) != null;
	}

	private MeetingLibraryEntry meetingSource() {
		return model.meetingSource(entry.path());
	}

	private void openEntry() {
		if (meetingSource() != null) {
			// ProjectCard.tsx: entry.meeting -> requestOpenMeeting(entry.path).
			dependencies.actions().openMeeting(entry.path());
		} else if (rawSource()) {
			dependencies.actions().openRecording(entry.path());
		} else if (entry.isCurrent()) {
			dependencies.actions().openProject(entry.path());
		} else {
			dependencies.actions().openProject(entry.path());
		}
	}

	private void deleteEntry() {
		java.util.List<String> paths = java.util.List.of(entry.path());
		if (rawSource() || meetingSource() != null) {
			deleteLibraryEntry(paths);
			return;
		}
		DashboardDeleteDialog.show(this, paths, deleteRecordings -> {
			model.busyProperty().set(true);
			java.util.concurrent.CompletableFuture.runAsync(() -> {
				try {
					model.trashProjects(paths, deleteRecordings);
				} catch (java.io.IOException error) {
					javafx.application.Platform.runLater(() ->
							model.errorProperty().set("Could not delete: " + error.getMessage()));
				} finally {
					javafx.application.Platform.runLater(() -> model.busyProperty().set(false));
				}
			});
		});
	}

	/**
	 * Port of the React {@code deleteEntries} branch for raw files and meetings: no delete dialog,
	 * hide (raw) or move to the system trash (meetings).
	 */
	private void deleteLibraryEntry(java.util.List<String> paths) {
		model.busyProperty().set(true);
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				model.deleteLibraryEntries(paths);
			} catch (java.io.IOException error) {
				javafx.application.Platform.runLater(() ->
						model.errorProperty().set("Could not delete: " + error.getMessage()));
			} finally {
				javafx.application.Platform.runLater(() -> model.busyProperty().set(false));
			}
		});
	}

	private void startRename() {
		Input field = new Input(entry.name());
		field.getStyleClass().add("project-card__rename");
		field.setText(entry.name());
		field.setMaxWidth(Double.MAX_VALUE);
		nameHost.getChildren().setAll(field);
		field.requestFocus();
		field.selectAll();
		Runnable commit = () -> rename(field.getText());
		field.setOnAction(event -> commit.run());
		field.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				commit.run();
			}
		});
		field.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.ESCAPE) {
				nameHost.getChildren().setAll(nameLabel);
			}
		});
	}

	private void rename(String name) {
		if (name == null || name.trim().isEmpty() || name.trim().equals(entry.name())) {
			nameHost.getChildren().setAll(nameLabel);
			return;
		}
		model.busyProperty().set(true);
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				String target = model.renameProject(entry.path(), name);
				javafx.application.Platform.runLater(() -> {
					nameLabel.setText(name.trim());
					nameHost.getChildren().setAll(nameLabel);
					model.refreshVisible();
					model.busyProperty().set(false);
					if (!target.equals(entry.path())) {
						model.errorProperty().set("");
					}
				});
			} catch (java.io.IOException error) {
				javafx.application.Platform.runLater(() -> {
					nameHost.getChildren().setAll(nameLabel);
					model.errorProperty().set("Could not rename: " + error.getMessage());
					model.busyProperty().set(false);
				});
			}
		});
	}
}
