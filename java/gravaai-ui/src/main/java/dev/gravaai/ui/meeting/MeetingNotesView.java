package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.MeetingJobMode;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.services.meeting.Subscription;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.kit.Toast;
import dev.gravaai.ui.kit.ToggleGroup;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Transcript and notes pane of a meeting (port of {@code MeetingNotes.tsx} and
 * {@code useMeeting.ts}): live job progress, the notes/transcript tabs, edit/copy, re-run stages
 * and the clickable timestamp markdown.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class MeetingNotesView extends VBox {

	private enum Tab {
		NOTES,
		TRANSCRIPT
	}

	private final MeetingUiService service;
	private final Consumer<Double> onSeek;

	private final VBox jobHost = new VBox();
	private final Label errorLabel = new Label();
	private final ToggleGroup tabs = new ToggleGroup(ToggleGroup.Mode.SINGLE);
	private final HBox actions = new HBox(6);
	private final VBox contentHost = new VBox();
	private final ScrollPane scroll = new ScrollPane(contentHost);
	private final TextArea editor = new TextArea();
	private final VBox emptyHost = new VBox(12);
	private final MeetingMarkdownView markdown = new MeetingMarkdownView();

	private @Nullable Path mediaPath;
	private MeetingService.@Nullable FilesView files;
	private @Nullable MeetingJob job;
	private Tab tab = Tab.NOTES;
	private @Nullable Tab editing;
	private @Nullable String draft;
	private boolean loading;
	private @Nullable Subscription jobSubscription;

	public MeetingNotesView(MeetingUiService service, Consumer<Double> onSeek) {
		this.service = service;
		this.onSeek = onSeek;
		getStyleClass().add("meeting-notes");
		setSpacing(12);

		jobHost.setSpacing(8);
		errorLabel.setStyle("-fx-text-fill: " + css(Theme.DANGER) + "; -fx-font-size: 12px;");
		errorLabel.setVisible(false);
		errorLabel.setManaged(false);

		tabs.item(Tab.NOTES.name().toLowerCase(), "Notes");
		tabs.item(Tab.TRANSCRIPT.name().toLowerCase(), "Transcript");
		tabs.setSelected("notes");
		tabs.getStyleClass().add("meeting-notes__tabs");
		tabs.onValueChange(value -> {
			tab = "transcript".equals(value) ? Tab.TRANSCRIPT : Tab.NOTES;
			editing = null;
			rebuildActions();
			rebuildContent();
		});
		HBox tabsBar = new HBox(tabs);
		tabsBar.setAlignment(Pos.CENTER_LEFT);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		actions.setAlignment(Pos.CENTER_RIGHT);
		HBox toolbar = new HBox(8, tabsBar, spacer, actions);
		toolbar.setAlignment(Pos.CENTER_LEFT);
		toolbar.setPadding(new Insets(0, 0, 2, 0));

		editor.setWrapText(true);
		editor.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 12px; -fx-control-inner-background: "
				+ css(Theme.FIELD) + "; -fx-text-fill: " + css(Theme.FG) + ";");
		editor.setVisible(false);
		editor.setManaged(false);
		VBox.setVgrow(editor, Priority.ALWAYS);

		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("custom-scrollbar");
		scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		contentHost.setSpacing(2);
		contentHost.setPadding(new Insets(0, 4, 0, 0));
		contentHost.setStyle("-fx-background-color: " + css(Theme.BG) + ";");

		getChildren().addAll(jobHost, errorLabel, toolbar, editor, scroll);
		rebuildContent();
		rebuildActions();
	}

	/** Changes the meeting shown; null clears the pane. */
	public void open(Path newMediaPath) {
		unsubscribe();
		mediaPath = newMediaPath;
		files = null;
		job = null;
		editing = null;
		loading = newMediaPath != null;
		if (newMediaPath == null) {
			rebuildContent();
			rebuildActions();
			rebuildJobStatus();
			return;
		}
		refresh();
		loadLatestJob();
		jobSubscription = service.onJobUpdate(update -> Platform.runLater(() -> onJobUpdate(update)));
	}

	/** Stops listening (view closed). */
	public void close() {
		unsubscribe();
		mediaPath = null;
	}

	public @Nullable Path mediaPath() {
		return mediaPath;
	}

	private void unsubscribe() {
		if (jobSubscription != null) {
			jobSubscription.close();
			jobSubscription = null;
		}
	}

	private void refresh() {
		Path current = mediaPath;
		if (current == null) {
			return;
		}
		CompletableFuture.supplyAsync(() -> service.getFiles(current)).thenAcceptAsync(result -> {
			if (!current.equals(mediaPath)) {
				return;
			}
			loading = false;
			if (result.isErr()) {
				showError(result.errorMessage());
			} else {
				error(null);
				files = result.orElse(null);
			}
			rebuildJobStatus();
			rebuildContent();
			rebuildActions();
		}, Platform::runLater);
	}

	private void loadLatestJob() {
		Path current = mediaPath;
		if (current == null) {
			return;
		}
		CompletableFuture.supplyAsync(service::listJobs).thenAcceptAsync(jobs -> {
			if (!current.equals(mediaPath) || jobs == null) {
				return;
			}
			MeetingJob latest = null;
			for (MeetingJob candidate : jobs) {
				if (candidate.mediaPath() != null && candidate.mediaPath().equals(current)) {
					latest = candidate;
				}
			}
			job = latest;
			rebuildJobStatus();
			rebuildContent();
			rebuildActions();
		}, Platform::runLater);
	}

	private void onJobUpdate(MeetingJob update) {
		if (mediaPath == null || update.mediaPath() == null || !update.mediaPath().equals(mediaPath)) {
			return;
		}
		job = update;
		rebuildJobStatus();
		rebuildContent();
		rebuildActions();
		// Reload as soon as a stage has written its file, and at the end.
		if (update.stage() == MeetingJob.Stage.SUMMARIZE || update.stage() == MeetingJob.Stage.TITLE
				|| !update.isActive()) {
			refresh();
		}
	}

	private boolean busy() {
		return job != null && job.isActive();
	}

	private void rebuildJobStatus() {
		jobHost.getChildren().clear();
		if (busy()) {
			VBox card = new VBox(6);
			card.setPadding(new Insets(12));
			card.setStyle("-fx-background-color: rgba(35,40,51,0.5); -fx-background-radius: 12;"
					+ " -fx-border-color: " + css(Theme.SEPARATOR) + "; -fx-border-radius: 12;");
			Label stage = new Label(MeetingText.stageLabel(job.stage()));
			stage.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: "
					+ css(Theme.FG) + ";");
			Label message = new Label(job.message() == null ? "" : job.message());
			message.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
			Button cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.SM);
			cancel.setOnAction(event -> cancel());
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			HBox top = new HBox(8, new VBox(2, stage, message), spacer, cancel);
			top.setAlignment(Pos.CENTER_LEFT);
			HBox bar = new HBox();
			bar.setMinHeight(4);
			bar.setPrefHeight(4);
			bar.setStyle("-fx-background-color: " + css(Theme.DEFAULT) + "; -fx-background-radius: 4;");
			Region fill = new Region();
			fill.setStyle("-fx-background-color: " + css(Theme.ACCENT) + "; -fx-background-radius: 4;");
			if (job.progress() == null) {
				fill.prefWidthProperty().bind(bar.widthProperty().divide(3));
				fill.setOpacity(0.6);
			} else {
				double fraction = Math.max(0, Math.min(100, job.progress())) / 100.0;
				fill.prefWidthProperty().bind(bar.widthProperty().multiply(fraction));
			}
			bar.getChildren().add(fill);
			card.getChildren().addAll(top, bar);
			jobHost.getChildren().add(card);
			return;
		}
		String failed = job != null && job.status() == MeetingJob.Status.FAILED ? job.error()
				: files != null && files.meta() != null && files.meta().lastJob() != null
						&& "failed".equals(files.meta().lastJob().status())
								? files.meta().lastJob().error()
								: null;
		boolean interrupted = files != null && files.interrupted();
		if (failed == null && !interrupted) {
			return;
		}
		VBox card = new VBox(4);
		card.setPadding(new Insets(12));
		card.setStyle("-fx-background-color: rgba(229,101,110,0.08); -fx-background-radius: 12;"
				+ " -fx-border-color: rgba(229,101,110,0.4); -fx-border-radius: 12;");
		Label title = new Label(interrupted ? "Processing was interrupted" : "Processing failed");
		title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: "
				+ css(Theme.DANGER) + ";");
		card.getChildren().add(title);
		if (failed != null) {
			Label detail = new Label(failed);
			detail.setWrapText(true);
			detail.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
			card.getChildren().add(detail);
		}
		MeetingJobMode mode = job != null && job.mode() != null ? job.mode()
				: files != null && files.meta() != null && files.meta().lastJob() != null
						? MeetingJobMode.fromWire(files.meta().lastJob().mode())
						: MeetingJobMode.FULL;
		Button retry = new Button("Retry", Button.Variant.SECONDARY, Button.Size.SM);
		retry.setOnAction(event -> run(mode == null ? MeetingJobMode.FULL : mode));
		HBox row = new HBox(retry);
		row.setAlignment(Pos.CENTER_RIGHT);
		card.getChildren().add(row);
		jobHost.getChildren().add(card);
	}

	private void rebuildContent() {
		if (editing != null) {
			editor.setVisible(true);
			editor.setManaged(true);
			scroll.setVisible(false);
			scroll.setManaged(false);
			editor.setText(draft == null ? "" : draft);
			return;
		}
		editor.setVisible(false);
		editor.setManaged(false);
		scroll.setVisible(true);
		scroll.setManaged(true);
		String content = tab == Tab.NOTES
				? files == null ? null : files.notes()
				: files == null ? null : files.transcript();
		if (content != null && !content.isBlank()) {
			markdown.refresh(content);
			contentHost.getChildren().setAll(markdown);
			return;
		}
		if (loading) {
			contentHost.getChildren().setAll(new Label());
			return;
		}
		contentHost.getChildren().setAll(emptyState());
	}

	private Node emptyState() {
		emptyHost.getChildren().clear();
		emptyHost.setAlignment(Pos.CENTER);
		emptyHost.setPadding(new Insets(40, 12, 40, 12));
		Label message = new Label();
		message.setStyle("-fx-font-size: 13px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		message.setWrapText(true);
		Button action = new Button("", Button.Variant.DEFAULT, Button.Size.SM);
		action.setDisable(busy());
		if (tab == Tab.TRANSCRIPT) {
			message.setText("No transcript yet.");
			action.setText("Transcribe");
			action.setOnAction(event -> run(MeetingJobMode.TRANSCRIBE));
		} else if (files != null && files.transcript() != null) {
			message.setText("No notes yet.");
			action.setText("Write notes");
			action.setOnAction(event -> run(MeetingJobMode.SUMMARIZE));
		} else {
			message.setText("Transcribe the recording, then write the notes.");
			action.setText("Transcribe and write notes");
			action.setOnAction(event -> run(MeetingJobMode.FULL));
		}
		emptyHost.getChildren().addAll(message, action);
		return emptyHost;
	}

	private void rebuildActions() {
		actions.getChildren().clear();
		boolean hasContent = files != null && currentContent() != null
				&& !currentContent().isBlank();
		if (editing != null) {
			Button discard = new Button("Discard", Button.Variant.GHOST, Button.Size.SM);
			discard.setOnAction(event -> {
				editing = null;
				draft = null;
				rebuildContent();
				rebuildActions();
			});
			Button save = new Button("Save", Button.Variant.DEFAULT, Button.Size.SM);
			save.setOnAction(event -> saveEdit());
			actions.getChildren().addAll(discard, save);
			return;
		}
		if (hasContent) {
			Button copy = new Button("Copy", Button.Variant.GHOST, Button.Size.SM);
			copy.setOnAction(event -> copy());
			Button edit = new Button("Edit", Button.Variant.GHOST, Button.Size.SM);
			edit.setDisable(busy());
			edit.setOnAction(event -> startEdit());
			actions.getChildren().addAll(copy, edit);
			if (tab == Tab.NOTES && files.transcript() != null && files.notes() != null) {
				Button rewrite = new Button("Rewrite notes", Button.Variant.GHOST, Button.Size.SM);
				rewrite.setDisable(busy());
				rewrite.setOnAction(event -> run(MeetingJobMode.SUMMARIZE));
				actions.getChildren().add(rewrite);
			} else if (tab == Tab.TRANSCRIPT && files.transcript() != null) {
				Button redo = new Button("Redo transcript and notes", Button.Variant.GHOST,
						Button.Size.SM);
				redo.setDisable(busy());
				redo.setOnAction(event -> run(MeetingJobMode.FULL));
				actions.getChildren().add(redo);
			}
		}
	}

	private @Nullable String currentContent() {
		if (files == null) {
			return null;
		}
		return tab == Tab.NOTES ? files.notes() : files.transcript();
	}

	private void startEdit() {
		editing = tab;
		draft = currentContent();
		rebuildContent();
		rebuildActions();
	}

	private void saveEdit() {
		Tab editingTab = editing;
		Path current = mediaPath;
		if (editingTab == null || current == null) {
			return;
		}
		MeetingService.FileKind kind = editingTab == Tab.NOTES
				? MeetingService.FileKind.NOTES
				: MeetingService.FileKind.TRANSCRIPT;
		String content = draft == null ? "" : draft;
		CompletableFuture.supplyAsync(() -> service.saveFile(current, kind, content))
				.thenAcceptAsync(result -> {
					if (result.isErr()) {
						showError(result.errorMessage());
						return;
					}
					editing = null;
					draft = null;
					Toast.show(this, editingTab == Tab.NOTES ? "Notes saved" : "Transcript saved")
							.variant(Toast.Variant.SUCCESS);
					refresh();
				}, Platform::runLater);
	}

	private void copy() {
		String content = currentContent();
		if (content == null) {
			return;
		}
		ClipboardContent clipboard = new ClipboardContent();
		clipboard.putString(content);
		Clipboard.getSystemClipboard().setContent(clipboard);
		Toast.show(this, tab == Tab.NOTES ? "Notes copied" : "Transcript copied")
				.variant(Toast.Variant.SUCCESS);
	}

	private void run(MeetingJobMode mode) {
		Path current = mediaPath;
		if (current == null) {
			return;
		}
		CompletableFuture.supplyAsync(() -> service.runJob(current, mode)).thenAcceptAsync(result -> {
			if (result.isErr()) {
				showError(result.errorMessage());
				return;
			}
			job = result.orElse(null);
			rebuildJobStatus();
			rebuildContent();
			rebuildActions();
		}, Platform::runLater);
	}

	private void cancel() {
		MeetingJob current = job;
		if (current == null || !current.isActive()) {
			return;
		}
		CompletableFuture.runAsync(() -> service.cancelJob(current.id()));
	}

	private void error(@Nullable String message) {
		boolean visible = message != null && !message.isBlank();
		errorLabel.setText(visible ? message : "");
		errorLabel.setVisible(visible);
		errorLabel.setManaged(visible);
	}

	private void showError(String message) {
		error(message);
		Toast.show(this, message).variant(Toast.Variant.DANGER);
	}

	private static String css(javafx.scene.paint.Color color) {
		return "#" + color.toString().substring(2, 8);
	}
}
