package dev.gravaai.ui.export;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.services.export.ExportQueue;
import dev.gravaai.services.export.ExportQueue.Entry;
import dev.gravaai.services.export.ExportQueue.State;
import dev.gravaai.ui.i18n.UiI18n;
import dev.gravaai.ui.kit.Anchors;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Popover;
import dev.gravaai.ui.kit.Toast;
import dev.gravaai.ui.kit.Tooltip;
import javafx.animation.Animation;
import javafx.animation.Transition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * The batch export queue popover (card "Batch export queue"): a 380 px panel anchored to the queue
 * button of the dashboard toolbar or the editor header. It renders the {@link ExportQueue} snapshot
 * in queue order — label, detail, destination file name, localized state, progress for the running
 * export and the failure reason for a failed one — and offers the per-state actions: move up/down
 * and cancel for queued entries, cancel for the running one, Copy file and Show in folder for every
 * completed one, plus "Add project", the active count line and "Clear finished" in the footer.
 *
 * <p>The panel owns no export logic: every button delegates to the queue, and every change is
 * followed by {@link #refresh()}. The queue notifies from any thread (the export thread for
 * progress and completion), so the change listener is marshalled to the FX thread with
 * {@link Platform#runLater}. {@link #close()} hides the popover and drops that listener; it never
 * cancels queued exports, which keep running after the panel goes away.
 */
public final class ExportQueuePanel {

	/** App-side actions the panel cannot perform itself. */
	public interface Callbacks {

		/** "Add project": opens the app's project chooser and queues the chosen projects. */
		void addProjects();

		/** Reveals a published export in the system file manager (Show in folder). */
		void revealFile(Path file);
	}

	/** Matches the export popover so the two panels feel like one family. */
	private static final double WIDTH = 380;

	/** Fill fraction shown while a running export has no progress snapshot yet. */
	private static final double INDETERMINATE_FILL_FRACTION = 0.4;

	/** The localized text of one queue state. */
	private static String stateText(State state) {
		return switch (state) {
			case QUEUED -> UiI18n.t("dialogs:export.queue.stateQueued");
			case RUNNING -> UiI18n.t("dialogs:export.queue.stateRunning");
			case COMPLETED -> UiI18n.t("dialogs:export.queue.stateCompleted");
			case FAILED -> UiI18n.t("dialogs:export.queue.stateFailed");
			case CANCELLED -> UiI18n.t("dialogs:export.queue.stateCancelled");
		};
	}

	private final ExportQueue queue;
	private final Callbacks callbacks;
	private final Popover popover;
	private final VBox content = new VBox();
	private final VBox list = new VBox();
	private final Label emptyLabel = new Label(UiI18n.t("dialogs:export.queue.empty"));
	private final Label countLabel = new Label();
	private final HBox footer = new HBox();
	private final List<RowView> rows = new ArrayList<>();
	private @Nullable List<RowKey> structure;
	private @Nullable AutoCloseable subscription;
	private boolean closed;

	/**
	 * @param trigger the button the popover is anchored to (the header/toolbar queue button)
	 * @param queue the app-owned queue; the panel only reads it and calls its actions
	 * @param callbacks the app-side add/reveal actions
	 */
	public ExportQueuePanel(Node trigger, ExportQueue queue, Callbacks callbacks) {
		this.queue = Objects.requireNonNull(queue, "queue");
		this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
		Node triggerNode = Objects.requireNonNull(trigger, "trigger");
		// Popover installs its own click-to-toggle only for triggers that do not own an action.
		// Give the panel a default toggle now so an app that wires setOnOpenQueue() after building
		// the panel does not end up with both a click handler and an action toggling twice.
		if (triggerNode instanceof ButtonBase button && button.getOnAction() == null) {
			button.setOnAction(event -> toggle());
		}
		popover = new Popover(triggerNode);
		popover.side(Anchors.Side.BOTTOM).align(Anchors.Align.END).offset(10);

		content.getStyleClass().add("export-queue");
		content.setPrefWidth(WIDTH);
		content.setMaxWidth(WIDTH);
		content.setSpacing(0);

		emptyLabel.getStyleClass().add("export-queue__empty");
		emptyLabel.setWrapText(true);
		emptyLabel.setMaxWidth(WIDTH - 32);
		emptyLabel.setVisible(false);
		emptyLabel.setManaged(false);

		list.getStyleClass().add("export-queue__list");

		Button clearFinished = new Button(UiI18n.t("dialogs:export.queue.clearFinished"),
				Button.Variant.GHOST, Button.Size.SM);
		clearFinished.getStyleClass().add("export-queue__action");
		clearFinished.setOnAction(event -> {
			queue.clearFinished();
			refresh();
		});
		Tooltip.install(clearFinished, UiI18n.t("dialogs:export.queue.clearFinished"));
		footer.getStyleClass().add("export-queue__footer");
		footer.setAlignment(Pos.CENTER_LEFT);
		footer.getChildren().add(clearFinished);
		footer.setVisible(false);
		footer.setManaged(false);

		content.getChildren().addAll(buildHeader(), emptyLabel, list, footer);
		popover.content(content);
		subscription = queue.addChangeListener(() -> Platform.runLater(this::refresh));
		refresh();
	}

	/** Opens the popover (refreshing the snapshot first). */
	public void show() {
		refresh();
		popover.show();
	}

	/** Closes the popover without dropping the queue subscription. */
	public void hide() {
		popover.hide();
	}

	/** Opens the popover when closed and closes it when open. */
	public void toggle() {
		if (popover.isShowing()) {
			hide();
		} else {
			show();
		}
	}

	/** {@code true} while the popover is open. */
	public boolean isShowing() {
		return popover.isShowing();
	}

	/**
	 * Rebuilds the row list from the queue snapshot. Safe to call on the FX thread at any time; the
	 * queue listener and the panel's own action buttons call it after every change.
	 */
	public void refresh() {
		if (closed) {
			return;
		}
		List<Entry> entries = queue.entries();
		updateCount(entries);
		boolean finished = entries.stream().anyMatch(Entry::isFinished);
		footer.setVisible(finished);
		footer.setManaged(finished);
		List<RowKey> nextStructure = structureOf(entries);
		if (nextStructure.equals(structure)) {
			updateProgress(entries);
			return;
		}
		structure = nextStructure;
		disposeRows();
		list.getChildren().clear();
		boolean empty = entries.isEmpty();
		emptyLabel.setVisible(empty);
		emptyLabel.setManaged(empty);
		list.setVisible(!empty);
		list.setManaged(!empty);
		if (!empty) {
			for (int index = 0; index < entries.size(); index++) {
				RowView row = buildRow(entries, index);
				rows.add(row);
				list.getChildren().add(row.node());
			}
		}
		if (popover.isShowing()) {
			popover.relayout();
		}
	}

	/** Hides the popover and removes the queue listener (the app owns the queue from then on). */
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		popover.hide();
		AutoCloseable current = subscription;
		subscription = null;
		if (current != null) {
			try {
				current.close();
			} catch (Exception ignored) {
				// A listener handle that fails to close cannot keep the queue alive; best effort.
			}
		}
		disposeRows();
	}

	// ---- header / footer / count ----

	private VBox buildHeader() {
		Label title = new Label(UiI18n.t("dialogs:export.queue.title"));
		title.getStyleClass().add("export-queue__heading");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button addProject = new Button(UiI18n.t("dialogs:export.queue.addProject"),
				Button.Variant.SECONDARY, Button.Size.SM);
		addProject.getStyleClass().add("export-queue__action");
		addProject.setGraphic(Icon.of(Icons.Plus, 14));
		addProject.setGraphicTextGap(6);
		addProject.setOnAction(event -> callbacks.addProjects());
		Tooltip.install(addProject, UiI18n.t("dialogs:export.queue.addProject"));
		HBox titleRow = new HBox(8, title, spacer, addProject);
		titleRow.setAlignment(Pos.CENTER_LEFT);

		countLabel.getStyleClass().add("export-queue__count");
		VBox header = new VBox(4, titleRow, countLabel);
		header.getStyleClass().add("export-queue__header");
		return header;
	}

	/** The "N queued · Exporting X of Y" line; hidden when nothing is pending. */
	private void updateCount(List<Entry> entries) {
		long queued = entries.stream().filter(entry -> entry.state() == State.QUEUED).count();
		int active = (int) entries.stream().filter(entry -> entry.isPending()).count();
		int runningPosition = 0;
		int seen = 0;
		for (Entry entry : entries) {
			if (!entry.isPending()) {
				continue;
			}
			seen++;
			if (entry.state() == State.RUNNING) {
				runningPosition = seen;
				break;
			}
		}
		List<String> parts = new ArrayList<>(2);
		if (queued > 0) {
			parts.add(UiI18n.t("dialogs:export.queue.countQueued",
					Map.of("count", queued)));
		}
		if (runningPosition > 0) {
			parts.add(UiI18n.t("dialogs:export.queue.countRunning",
					Map.of("current", runningPosition, "total", active)));
		}
		boolean visible = !parts.isEmpty();
		countLabel.setText(String.join(" · ", parts));
		countLabel.setVisible(visible);
		countLabel.setManaged(visible);
	}

	// ---- rows ----

	private RowView buildRow(List<Entry> entries, int index) {
		Entry entry = entries.get(index);
		VBox node = new VBox();
		node.getStyleClass().add("export-queue__row");
		node.getStyleClass().add("export-queue__row--" + entry.state().name().toLowerCase(Locale.ROOT));

		Label title = new Label(entry.label());
		title.getStyleClass().add("export-queue__row-title");
		title.setMaxWidth(Double.MAX_VALUE);
		VBox info = new VBox(2, title);
		if (!entry.detail().isBlank()) {
			Label detail = new Label(entry.detail());
			detail.getStyleClass().add("export-queue__row-detail");
			detail.setMaxWidth(Double.MAX_VALUE);
			detail.setMinWidth(0);
			info.getChildren().add(detail);
		}
		destinationFileName(entry).ifPresent(name -> {
			Label destination = new Label(name);
			destination.getStyleClass().add("export-queue__row-destination");
			destination.setMaxWidth(Double.MAX_VALUE);
			destination.setMinWidth(0);
			destination.setTextOverrun(OverrunStyle.ELLIPSIS);
			destination.setTooltip(new javafx.scene.control.Tooltip(entry.destination().toString()));
			info.getChildren().add(destination);
		});
		info.setMinWidth(0);
		HBox.setHgrow(info, Priority.ALWAYS);

		Label stateLabel = new Label(stateText(entry.state()));
		stateLabel.getStyleClass().add("export-queue__row-state");
		stateLabel.getStyleClass().add(
				"export-queue__row-state--" + entry.state().name().toLowerCase(Locale.ROOT));
		stateLabel.setMinWidth(Region.USE_PREF_SIZE);

		HBox top = new HBox(8, info, stateLabel);
		top.setAlignment(Pos.TOP_LEFT);
		node.getChildren().add(top);

		Pane track = null;
		Region fill = null;
		Label percent = null;
		Transition animation = null;
		if (entry.state() == State.RUNNING) {
			track = buildTrack();
			fill = new Region();
			fill.getStyleClass().add("export-queue__fill");
			fill.setMinHeight(6);
			fill.setPrefHeight(6);
			fill.setMaxHeight(6);
			track.getChildren().add(fill);
			percent = new Label();
			percent.getStyleClass().add("export-queue__percent");
			HBox progressRow = new HBox(8, track, percent);
			progressRow.setAlignment(Pos.CENTER_LEFT);
			HBox.setHgrow(track, Priority.ALWAYS);
			ExportProgress progress = entry.progress();
			if (progress == null) {
				// No snapshot yet: the HeroUI indeterminate slide, 40% wide, 1.5 s infinite.
				fill.prefWidthProperty().bind(track.widthProperty().multiply(INDETERMINATE_FILL_FRACTION));
				percent.setVisible(false);
				percent.setManaged(false);
				animation = indeterminateSlide(fill);
				animation.play();
			} else {
				applyProgress(track, fill, percent, progress);
			}
			node.getChildren().add(progressRow);
		} else if (entry.state() == State.FAILED) {
			Label error = new Label(entry.error() == null ? "" : entry.error());
			error.getStyleClass().add("export-queue__row-error");
			error.setWrapText(true);
			error.setMaxWidth(Double.MAX_VALUE);
			node.getChildren().add(error);
		}

		@Nullable Node actions = buildActions(entries, index, entry);
		if (actions != null) {
			node.getChildren().add(actions);
		}
		return new RowView(entry.id(), entry.state(), node, track, fill, percent, animation);
	}

	/** Per-state action row; {@code null} when the state has no action. */
	private @Nullable HBox buildActions(List<Entry> entries, int index, Entry entry) {
		HBox actions = new HBox();
		actions.getStyleClass().add("export-queue__actions");
		actions.setAlignment(Pos.CENTER_LEFT);
		switch (entry.state()) {
			case QUEUED -> {
				Runnable moveUp = () -> {
					queue.move(entry.id(), -1);
					refresh();
				};
				Runnable moveDown = () -> {
					queue.move(entry.id(), 1);
					refresh();
				};
				Runnable cancel = () -> {
					queue.cancel(entry.id());
					refresh();
				};
				Button up = iconButton(Icons.CaretUpIcon,
						UiI18n.t("dialogs:export.queue.moveUp"), moveUp);
				up.setDisable(!hasQueuedNeighbour(entries, index, -1));
				Button down = iconButton(Icons.CaretDown,
						UiI18n.t("dialogs:export.queue.moveDown"), moveDown);
				down.setDisable(!hasQueuedNeighbour(entries, index, 1));
				actions.getChildren().addAll(up, down,
						textButton(UiI18n.t("dialogs:export.queue.cancel"),
								Button.Variant.GHOST, cancel));
			}
			case RUNNING -> actions.getChildren().add(
					textButton(UiI18n.t("dialogs:export.queue.cancel"),
							Button.Variant.GHOST, () -> {
								queue.cancel(entry.id());
								refresh();
							}));
			case COMPLETED -> {
				@Nullable Path result = entry.result();
				Button copy = textButton(UiI18n.t("dialogs:export.queue.copyFile"),
						Button.Variant.OUTLINE, () -> copyResult(result));
				copy.setDisable(result == null);
				Button reveal = textButton(UiI18n.t("dialogs:export.showInFolder"),
						Button.Variant.GHOST, () -> revealResult(result));
				reveal.setDisable(result == null);
				actions.getChildren().addAll(copy, reveal);
			}
			case FAILED, CANCELLED -> {
				return null;
			}
		}
		return actions;
	}

	private Button iconButton(String icon, String tooltip, Runnable action) {
		Button button = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		button.getStyleClass().add("export-queue__icon-button");
		button.setGraphic(Icon.of(icon, 14));
		button.setAccessibleText(tooltip);
		button.setOnAction(event -> action.run());
		Tooltip.install(button, tooltip);
		return button;
	}

	private Button textButton(String label, Button.Variant variant, Runnable action) {
		Button button = new Button(label, variant, Button.Size.SM);
		button.getStyleClass().add("export-queue__action");
		button.setOnAction(event -> action.run());
		return button;
	}

	private void copyResult(@Nullable Path result) {
		if (result == null) {
			return;
		}
		ClipboardContent clipboard = new ClipboardContent();
		clipboard.putString(result.toAbsolutePath().toString());
		Clipboard.getSystemClipboard().setContent(clipboard);
		Toast.show(content, UiI18n.t("dialogs:export.queue.copied"));
	}

	private void revealResult(@Nullable Path result) {
		if (result != null) {
			callbacks.revealFile(result);
		}
	}

	// ---- progress ----

	private static Pane buildTrack() {
		Pane track = new Pane();
		track.getStyleClass().add("export-queue__track");
		track.setMinHeight(6);
		track.setPrefHeight(6);
		track.setMaxHeight(6);
		track.setMinWidth(0);
		// HBox gives the bar the leftover width (Hgrow); a non-zero pref keeps the initial pass
		// from measuring a zero-wide track while the fill width is still bound to it.
		track.setPrefWidth(1);
		track.setMaxWidth(Double.MAX_VALUE);
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(track.widthProperty());
		clip.heightProperty().bind(track.heightProperty());
		track.setClip(clip);
		return track;
	}

	/** Applies one resolved snapshot to a running row (fill fraction + percent label). */
	private static void applyProgress(Pane track, Region fill, Label percent,
			ExportProgress progress) {
		double fraction = clamp(progress.percentage() / 100.0);
		fill.prefWidthProperty().bind(track.widthProperty().multiply(fraction));
		percent.setText(Math.round(Math.max(0, Math.min(100, progress.percentage()))) + "%");
	}

	private static double clamp(double fraction) {
		return Math.max(0, Math.min(1, fraction));
	}

	/** One cycle of the indeterminate fill sliding across the track (progress-bar.css). */
	private static Transition indeterminateSlide(Region fill) {
		return new Transition() {
			{
				setCycleDuration(Duration.seconds(1.5));
				setCycleCount(Animation.INDEFINITE);
				setInterpolator(javafx.animation.Interpolator.ofSpline(0.65, 0, 0.35, 1));
			}

			@Override
			protected void interpolate(double fraction) {
				double width = fill.getWidth();
				fill.setTranslateX(-width + fraction * 4.5 * width);
			}
		};
	}

	/** Updates the progress of already-built running rows without rebuilding the list. */
	private void updateProgress(List<Entry> entries) {
		for (RowView row : rows) {
			if (row.state() != State.RUNNING || row.fill() == null || row.percent() == null) {
				continue;
			}
			for (Entry entry : entries) {
				if (entry.id() != row.id()) {
					continue;
				}
				ExportProgress progress = entry.progress();
				if (progress != null && row.track() != null) {
					row.fill().prefWidthProperty().unbind();
					row.fill().setTranslateX(0);
					applyProgress(row.track(), row.fill(), row.percent(), progress);
					row.percent().setVisible(true);
					row.percent().setManaged(true);
				}
				break;
			}
		}
	}

	// ---- structure / lifecycle helpers ----

	private static List<RowKey> structureOf(List<Entry> entries) {
		List<RowKey> keys = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			keys.add(new RowKey(entry.id(), entry.state(), entry.progress() == null));
		}
		return List.copyOf(keys);
	}

	private void disposeRows() {
		for (RowView row : rows) {
			if (row.animation() != null) {
				row.animation().stop();
			}
		}
		rows.clear();
	}

	private static Optional<String> destinationFileName(Entry entry) {
		Path destination = entry.destination();
		Path name = destination.getFileName();
		return name == null ? Optional.empty() : Optional.of(name.toString());
	}

	private static boolean hasQueuedNeighbour(List<Entry> entries, int index, int step) {
		for (int candidate = index + step; candidate >= 0 && candidate < entries.size();
				candidate += step) {
			if (entries.get(candidate).state() == State.QUEUED) {
				return true;
			}
		}
		return false;
	}

	/** Identity + presentation state of one row; a change rebuilds the whole list. */
	private record RowKey(long id, State state, boolean indeterminate) {
	}

	/**
	 * One built row and the nodes its progress-only refresh touches; the animation is stopped when
	 * the row goes away ({@link #disposeRows()}).
	 */
	private record RowView(long id, State state, VBox node, @Nullable Pane track,
			@Nullable Region fill, @Nullable Label percent, @Nullable Transition animation) {
	}
}
