package dev.gravaai.ui.editor.clips;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import dev.gravaai.core.json.Json;
import dev.gravaai.services.recording.RecordingLibrary;
import dev.gravaai.ui.editor.EditorViewActions;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.DropdownMenu;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.kit.Toast;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The Clips panel (port of {@code RecordingLibraryPanel.tsx} + {@code useRecordingLibrary.ts}):
 * the recordings library mounted into the inspector slot with search, multi-select, reversible
 * Trash removal and drag-and-drop / double-click imports into the timeline.
 *
 * <p>The panel owns the library state (entries, selection, loading/busy/error, the undo stack and
 * the importing flags) and every toast; the app side answers through {@link Host} and reports the
 * import outcome through one {@code done} callback.
 */
public final class RecordingLibraryPanel extends VBox {

	/** Drag payload format ({@code RECORDING_DRAG_TYPE}): a JSON array of recording paths. */
	public static final DataFormat RECORDING_DRAG = new DataFormat("application/x-gravaai-recording");

	/** The app seams the panel needs (the {@code window.electronAPI} calls of the React hook). */
	public interface Host {

		/** {@code listRecordings}: newest-first library rows. */
		List<RecordingLibrary.RecordingLibraryEntry> listRecordings() throws IOException;

		/** {@code setRecordingsRemoved}; {@code false} when the operation failed. */
		boolean setRecordingsRemoved(List<String> paths, boolean removed) throws IOException;

		/** {@code addToTimeline}; exactly one {@code done} call, on the FX thread. */
		void addRecordingsToTimeline(List<String> paths, Integer index,
				Consumer<EditorViewActions.ClipImportResult> done);

		/** {@code cancelRecordingImport}. */
		void cancelRecordingImport();

		/** {@code getRecordingThumbnail}: a {@code data:image/jpeg;base64,…} URL. */
		java.util.concurrent.CompletableFuture<String> recordingThumbnail(String path);

		/** {@code openRecordingsFolder}. */
		void openRecordingsFolder();

		/** Closes the panel (Escape / the header X button). */
		void close();
	}

	private final Host host;
	private final Label title = new Label("Clips");
	private final Input search = new Input();
	private final Label count = new Label("");
	private final VBox rows = new VBox(4);
	private final ClipCheckbox selectAll = new ClipCheckbox();
	private final Button trashSelected;
	private final Button undoButton;
	private final Label footerHint = new Label("Drag to the timeline or click + to add.");
	private final Label emptyTitle = new Label();
	private final Label emptySubtitle = new Label();
	private final HBox emptyState = new HBox();
	private final HBox errorState = new HBox();
	private final Label errorLabel = new Label();
	private final Label loadingLabel = new Label("Loading videos…");
	private DropdownMenu actionsMenu;
	private int trashAllDisabledIndex = -1;

	private final List<RecordingLibrary.RecordingLibraryEntry> entries = new ArrayList<>();
	private final Set<String> selected = new LinkedHashSet<>();
	private final List<List<String>> removed = new ArrayList<>();
	private final BooleanProperty importing = new SimpleBooleanProperty();
	private final BooleanProperty cancelling = new SimpleBooleanProperty();
	private boolean loading;
	private boolean busy;
	private String error;
	private String query = "";

	/** Builds the panel; the caller mounts it and calls {@link #refresh()} when it opens. */
	public RecordingLibraryPanel(Host host) {
		this.host = host;
		getStyleClass().addAll("editor-inspector", "clips-panel");
		setSpacing(0);
		setFocusTraversable(true);

		// ---- header: title / actions dropdown / close --------------------------
		title.getStyleClass().add("clips-panel__title");
		Button dots = iconButton(Icons.DotsThree, "Clip library actions");
		actionsMenu = new DropdownMenu(dots);
		actionsMenu.item("Open recordings folder", Icons.FolderOpen, null, host::openRecordingsFolder);
		actionsMenu.dangerDisabledItem("Move all to Trash", Icons.Trash);
		trashAllDisabledIndex = 0;
		Button close = iconButton(Icons.X, "Close Clips");
		close.setOnAction(event -> host.close());

		HBox header = new HBox(8, title, dots, close);
		header.getStyleClass().add("clips-panel__header");
		header.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(title, Priority.ALWAYS);

		// ---- search ------------------------------------------------------------
		search.getStyleClass().add("clips-panel__search");
		search.setPromptText("Search videos");
		search.textProperty().addListener((observable, oldValue, newValue) -> {
			query = newValue == null ? "" : newValue;
			rebuild();
		});
		Icon magnifier = Icon.of(Icons.MagnifyingGlass, 14);
		magnifier.setIconColor(Theme.MUTED);
		StackPane searchHost = new StackPane(search, magnifier);
		searchHost.getStyleClass().add("clips-panel__search-host");
		searchHost.setMaxWidth(Double.MAX_VALUE);
		StackPane.setAlignment(magnifier, Pos.CENTER_LEFT);
		StackPane.setMargin(magnifier, new Insets(0, 0, 0, 12));
		HBox searchRow = new HBox(searchHost);
		searchRow.getStyleClass().add("clips-panel__search-row");
		HBox.setHgrow(searchHost, Priority.ALWAYS);

		// ---- select-all row ----------------------------------------------------
		selectAll.getStyleClass().add("clips-panel__select-all");
		selectAll.setOnAction(() -> {
			List<RecordingLibrary.RecordingLibraryEntry> visible = visibleEntries();
			boolean select = !allSelected(visible);
			Set<String> next = new LinkedHashSet<>(selected);
			for (RecordingLibrary.RecordingLibraryEntry entry : visible) {
				if (select) {
					next.add(entry.path());
				} else {
					next.remove(entry.path());
				}
			}
			selected.clear();
			selected.addAll(next);
			rebuild();
		});
		count.getStyleClass().add("clips-panel__count");
		trashSelected = iconButton(Icons.Trash, "Move selected videos to Trash");
		dev.gravaai.ui.kit.Tooltip.install(trashSelected, "Move selected to Trash");
		trashSelected.setOnAction(event -> remove(new ArrayList<>(selected)));
		HBox selectRow = new HBox(8, selectAll, count, trashSelected);
		selectRow.getStyleClass().add("clips-panel__select-row");
		selectRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(count, Priority.ALWAYS);

		// ---- rows --------------------------------------------------------------
		rows.getStyleClass().add("clips-panel__rows");
		ScrollPane scroll = new ScrollPane(rows);
		scroll.getStyleClass().add("clips-panel__scroll");
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		VBox.setVgrow(scroll, Priority.ALWAYS);

		// ---- footer ------------------------------------------------------------
		footerHint.getStyleClass().add("clips-panel__footer-hint");
		undoButton = new Button("Undo", Button.Variant.GHOST, Button.Size.SM);
		undoButton.icon(Icons.ArrowCounterClockwise);
		undoButton.setGraphicTextGap(6);
		dev.gravaai.ui.kit.Tooltip.install(undoButton, "Undo (⌘Z / Ctrl+Z)");
		undoButton.setOnAction(event -> undo());
		HBox footer = new HBox(8, footerHint, undoButton);
		footer.getStyleClass().add("clips-panel__footer");
		footer.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(footerHint, Priority.ALWAYS);

		// ---- empty / error / loading states ------------------------------------
		emptyState.getStyleClass().add("clips-panel__empty");
		emptyState.setAlignment(Pos.CENTER);
		emptyState.setSpacing(8);
		Icon filmstrip = Icon.of(Icons.FilmStrip, 28);
		filmstrip.setIconColor(Theme.MUTED);
		emptyTitle.getStyleClass().add("clips-panel__empty-title");
		emptySubtitle.getStyleClass().add("clips-panel__empty-subtitle");
		emptyState.getChildren().addAll(filmstrip, emptyTitle, emptySubtitle);

		errorState.getStyleClass().add("clips-panel__error");
		errorLabel.getStyleClass().add("clips-panel__error-text");
		errorLabel.setWrapText(true);
		Button retry = new Button("Retry", Button.Variant.GHOST, Button.Size.SM);
		retry.setOnAction(event -> refresh());
		errorState.getChildren().addAll(errorLabel, retry);

		loadingLabel.getStyleClass().add("clips-panel__loading");

		getChildren().addAll(header, searchRow, selectRow, scroll, footer);
		setOnKeyPressed(this::handleKeyPressed);
		rebuild();
	}

	/** Whether an import is running (the shell shows the overlay from this). */
	public BooleanProperty importingProperty() {
		return importing;
	}

	/** Whether a cancel was requested (the overlay's Cancel button state). */
	public BooleanProperty cancellingProperty() {
		return cancelling;
	}

	/** Reloads the library rows ({@code refresh}). */
	public void refresh() {
		loading = true;
		error = null;
		rebuild();
		Thread.ofVirtual().name("gravaai-clips-refresh").start(() -> {
			try {
				List<RecordingLibrary.RecordingLibraryEntry> next = host.listRecordings();
				Platform.runLater(() -> {
					loading = false;
					entries.clear();
					entries.addAll(next);
					Set<String> known = new LinkedHashSet<>();
					for (RecordingLibrary.RecordingLibraryEntry entry : next) {
						known.add(entry.path());
					}
					selected.retainAll(known);
					rebuild();
				});
			} catch (IOException | RuntimeException failure) {
				Platform.runLater(() -> {
					loading = false;
					error = message(failure);
					rebuild();
				});
			}
		});
	}

	/** The single import entry point: the + button, double-click and the shell drop. */
	public void addToTimeline(List<String> paths, Integer index) {
		if (busy || importing.get() || paths == null || paths.isEmpty()) {
			return;
		}
		importing.set(true);
		cancelling.set(false);
		rebuild();
		host.addRecordingsToTimeline(paths, index, result -> {
			importing.set(false);
			cancelling.set(false);
			if (result != null) {
				if (result.success()) {
					// React toasts success on every commit, including a cancelled one that
					// completed at least one import.
					String message = result.completed() == 1
							? "Video added to timeline"
							: result.completed() + " videos added to timeline";
					toast(message, Toast.Variant.SUCCESS);
				} else if (!result.cancelled()) {
					toast("Could not add video: "
							+ (result.error() == null ? "Unknown error" : result.error()),
							Toast.Variant.DANGER);
				}
			}
			rebuild();
		});
	}

	/** Requests cancellation of the running import (the overlay's Cancel button). */
	public void cancelImport() {
		if (!importing.get() || cancelling.get()) {
			return;
		}
		cancelling.set(true);
		host.cancelRecordingImport();
	}

	/** Toast guarded for a detached panel (Escape can close it mid-import). */
	private void toast(String title, Toast.Variant variant) {
		if (getScene() == null) {
			return;
		}
		Toast.show(this, title).variant(variant);
	}

	private void handleKeyPressed(KeyEvent event) {
		if (event.getTarget() instanceof javafx.scene.control.TextInputControl) {
			return;
		}
		if (event.getCode() == KeyCode.ESCAPE) {
			event.consume();
			host.close();
			return;
		}
		if (event.getCode() == KeyCode.Z && (event.isControlDown() || event.isMetaDown())
				&& !event.isShiftDown()) {
			event.consume();
			undo();
			return;
		}
		if ((event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE)
				&& !selected.isEmpty()) {
			event.consume();
			remove(new ArrayList<>(selected));
		}
	}

	private void remove(List<String> paths) {
		if (busy || importing.get() || paths.isEmpty()) {
			return;
		}
		busy = true;
		rebuild();
		Thread.ofVirtual().name("gravaai-clips-remove").start(() -> {
			boolean success;
			String failureMessage = null;
			try {
				success = host.setRecordingsRemoved(paths, true);
			} catch (IOException | RuntimeException failure) {
				success = false;
				failureMessage = message(failure);
			}
			boolean done = success;
			String message = failureMessage;
			Platform.runLater(() -> {
				busy = false;
				if (done) {
					removed.add(paths);
					entries.removeIf(entry -> paths.contains(entry.path()));
					selected.clear();
				} else {
					toast("Could not remove videos: " + message, Toast.Variant.DANGER);
				}
				rebuild();
			});
		});
	}

	private void undo() {
		if (busy || importing.get() || removed.isEmpty()) {
			return;
		}
		List<String> paths = removed.get(removed.size() - 1);
		busy = true;
		rebuild();
		Thread.ofVirtual().name("gravaai-clips-undo").start(() -> {
			boolean success;
			String failureMessage = null;
			try {
				success = host.setRecordingsRemoved(paths, false);
			} catch (IOException | RuntimeException failure) {
				success = false;
				failureMessage = message(failure);
			}
			boolean done = success;
			String message = failureMessage;
			Platform.runLater(() -> {
				busy = false;
				if (done) {
					removed.remove(removed.size() - 1);
					refresh();
				} else {
					toast("Could not restore videos: " + message, Toast.Variant.DANGER);
					rebuild();
				}
			});
		});
	}

	private List<RecordingLibrary.RecordingLibraryEntry> visibleEntries() {
		String needle = query.toLowerCase();
		List<RecordingLibrary.RecordingLibraryEntry> visible = new ArrayList<>();
		for (RecordingLibrary.RecordingLibraryEntry entry : entries) {
			if (entry.name().toLowerCase().contains(needle)) {
				visible.add(entry);
			}
		}
		return visible;
	}

	private boolean allSelected(List<RecordingLibrary.RecordingLibraryEntry> visible) {
		return !visible.isEmpty() && visible.stream().allMatch(entry -> selected.contains(entry.path()));
	}

	private boolean locked() {
		return busy || importing.get();
	}

	/** Rebuilds the state-dependent parts: select row, rows, empty/error states and footer. */
	private void rebuild() {
		List<RecordingLibrary.RecordingLibraryEntry> visible = visibleEntries();
		boolean locked = locked();

		// select-all row
		boolean any = allSelected(visible);
		boolean some = !any && !selected.isEmpty();
		selectAll.setState(any, some);
		selectAll.setCheckDisabled(visible.isEmpty() || locked);
		if (!selected.isEmpty()) {
			count.setText(selected.size() + " selected");
		} else {
			count.setText(visible.size() + (visible.size() == 1 ? " recording" : " recordings"));
		}
		trashSelected.setVisible(!selected.isEmpty());
		trashSelected.setDisable(locked);

		// dropdown state
		if (trashAllDisabledIndex >= 0) {
			actionsMenu.setDisabled(trashAllDisabledIndex, entries.isEmpty() || locked);
		}

		// rows
		rows.getChildren().clear();
		loadingLabel.setVisible(loading);
		loadingLabel.setManaged(loading);
		if (loading) {
			rows.getChildren().add(loadingLabel);
		}
		errorState.setVisible(error != null);
		errorState.setManaged(error != null);
		if (error != null) {
			errorLabel.setText(error);
			rows.getChildren().add(errorState);
		}
		if (!loading && error == null && visible.isEmpty()) {
			emptyTitle.setText(query.isBlank() ? "Your recordings appear here" : "No matching videos");
			emptySubtitle.setText(query.isBlank() ? "Record a video to get started." : "Try another search.");
			emptyState.setVisible(true);
			emptyState.setManaged(true);
			rows.getChildren().add(emptyState);
		} else {
			emptyState.setVisible(false);
			emptyState.setManaged(false);
		}
		for (RecordingLibrary.RecordingLibraryEntry entry : visible) {
			rows.getChildren().add(row(entry, locked));
		}

		// footer
		boolean canUndo = !removed.isEmpty();
		footerHint.setText(canUndo ? "Moved to Trash" : "Drag to the timeline or click + to add.");
		undoButton.setVisible(canUndo);
		undoButton.setManaged(canUndo);
		undoButton.setDisable(locked);
	}

	private HBox row(RecordingLibrary.RecordingLibraryEntry entry, boolean locked) {
		ClipCheckbox checkbox = new ClipCheckbox();
		checkbox.setState(selected.contains(entry.path()), false);
		checkbox.setCheckDisabled(locked);
		checkbox.setOnAction(() -> toggle(entry.path()));

		Button main = new Button("", Button.Variant.GHOST, Button.Size.SM);
		main.getStyleClass().add("clips-row__main");
		main.setDisable(locked);
		Label name = new Label(entry.name());
		name.getStyleClass().add("clips-row__name");
		dev.gravaai.ui.kit.Tooltip.install(name, entry.name());
		Label meta = new Label(dateLabel(entry.createdAt()) + " · "
				+ String.format(java.util.Locale.getDefault(), "%.1f MB", entry.bytes() / 1048576.0));
		meta.getStyleClass().add("clips-row__meta");
		VBox text = new VBox(2, name, meta);
		Thumbnail thumbnail = new Thumbnail(entry.path());
		HBox mainContent = new HBox(12, thumbnail, text);
		mainContent.getStyleClass().add("clips-row__content");
		mainContent.setAlignment(Pos.CENTER_LEFT);
		main.setGraphic(mainContent);
		main.setOnAction(event -> toggle(entry.path()));
		main.setOnMouseClicked(event -> {
			if (event.getClickCount() == 2 && !locked) {
				addToTimeline(List.of(entry.path()), null);
			}
		});

		Button add = iconButton(Icons.Plus, "Add " + entry.name() + " to timeline");
		dev.gravaai.ui.kit.Tooltip.install(add, "Add to timeline");
		add.setDisable(locked);
		add.setOnAction(event -> addToTimeline(List.of(entry.path()), null));

		HBox row = new HBox(12, checkbox, main, add);
		row.getStyleClass().add("clips-row");
		row.setAlignment(Pos.CENTER_LEFT);
		if (selected.contains(entry.path())) {
			row.getStyleClass().add("clips-row--selected");
		}
		HBox.setHgrow(main, Priority.ALWAYS);
		row.setOnDragDetected(event -> {
			if (locked) {
				return;
			}
			List<String> paths = new ArrayList<>();
			if (selected.contains(entry.path())) {
				for (RecordingLibrary.RecordingLibraryEntry item : entries) {
					if (selected.contains(item.path())) {
						paths.add(item.path());
					}
				}
			} else {
				paths.add(entry.path());
			}
			var dragboard = row.startDragAndDrop(TransferMode.COPY);
			dragboard.setContent(Map.of(RECORDING_DRAG, Json.stringify(paths)));
			event.consume();
		});
		return row;
	}

	private void toggle(String path) {
		if (selected.contains(path)) {
			selected.remove(path);
		} else {
			selected.add(path);
		}
		rebuild();
	}

	/** A lazy 16:9 still for one library row (port of {@code RecordingThumbnail.tsx}). */
	private final class Thumbnail extends StackPane {

		private final ImageView image = new ImageView();
		private final Icon fallback = Icon.of(Icons.FilmStrip, 20);
		private final Region pulse = new Region();
		private final FadeTransition pulseAnimation = new FadeTransition(javafx.util.Duration.millis(1000));

		Thumbnail(String path) {
			getStyleClass().add("clips-thumb");
			image.setFitWidth(64);
			image.setFitHeight(36);
			image.setPreserveRatio(true);
			image.setVisible(false);
			fallback.setIconColor(Theme.MUTED);
			fallback.setVisible(false);
			pulse.getStyleClass().add("clips-thumb__pulse");
			pulseAnimation.setNode(pulse);
			pulseAnimation.setFromValue(1);
			pulseAnimation.setToValue(0.4);
			pulseAnimation.setCycleCount(FadeTransition.INDEFINITE);
			pulseAnimation.setAutoReverse(true);
			pulseAnimation.play();
			getChildren().addAll(pulse, fallback, image);
			Thread.ofVirtual().name("gravaai-clips-thumb").start(() -> {
				try {
					String data = host.recordingThumbnail(path).join();
					String base64 = data.substring(data.indexOf(',') + 1);
					Image decoded = new Image(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
					Platform.runLater(() -> show(decoded, true));
				} catch (RuntimeException failure) {
					Platform.runLater(() -> show(null, false));
				}
			});
		}

		private void show(Image decoded, boolean ok) {
			pulseAnimation.stop();
			pulse.setVisible(false);
			if (ok && decoded != null && !decoded.isError()) {
				image.setImage(decoded);
				image.setVisible(true);
			} else {
				fallback.setVisible(true);
			}
		}
	}

	/** The HeroUI checkbox metrics: 16×16 rounded-md control with check/dash indicators. */
	static final class ClipCheckbox extends StackPane {

		private final Icon check = Icon.of(Icons.Check, 10);
		private final Region dash = new Region();
		private boolean selected;
		private boolean indeterminate;
		private boolean disabled;
		private Runnable onAction;

		ClipCheckbox() {
			getStyleClass().add("clips-checkbox");
			check.getStyleClass().add("clips-checkbox__mark");
			check.setIconColor(Theme.ACCENT_FOREGROUND);
			dash.getStyleClass().add("clips-checkbox__dash");
			setMinSize(16, 16);
			setPrefSize(16, 16);
			setMaxSize(16, 16);
			getChildren().addAll(check, dash);
			addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
				if (!disabled && onAction != null) {
					onAction.run();
					event.consume();
				}
			});
			update();
		}

		void setState(boolean selected, boolean indeterminate) {
			this.selected = selected;
			this.indeterminate = indeterminate;
			update();
		}

		void setCheckDisabled(boolean disabled) {
			this.disabled = disabled;
			update();
		}

		void setOnAction(Runnable onAction) {
			this.onAction = onAction;
		}

		private void update() {
			getStyleClass().removeAll("clips-checkbox--selected", "clips-checkbox--indeterminate",
					"clips-checkbox--disabled");
			if (selected) {
				getStyleClass().add("clips-checkbox--selected");
			}
			if (indeterminate) {
				getStyleClass().add("clips-checkbox--indeterminate");
			}
			if (disabled) {
				getStyleClass().add("clips-checkbox--disabled");
			}
			check.setVisible(selected && !indeterminate);
			dash.setVisible(indeterminate);
		}
	}

	private static Button iconButton(String iconName, String tooltip) {
		Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		button.icon(iconName);
		button.getStyleClass().add("clips-panel__icon-button");
		dev.gravaai.ui.kit.Tooltip.install(button, tooltip);
		return button;
	}

	private static String dateLabel(long createdAt) {
		return DateTimeFormatter.ofPattern("MMM d")
				.withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochMilli(createdAt));
	}

	private static String message(Throwable failure) {
		return failure.getMessage() == null ? failure.toString() : failure.getMessage();
	}
}
