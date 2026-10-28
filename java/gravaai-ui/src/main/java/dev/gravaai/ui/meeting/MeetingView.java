package dev.gravaai.ui.meeting;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import dev.gravaai.core.result.Result;
import dev.gravaai.services.meeting.MeetingJob;
import dev.gravaai.services.meeting.MeetingMeta;
import dev.gravaai.services.meeting.MeetingService;
import dev.gravaai.services.meeting.Subscription;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.kit.Toast;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Full view of one meeting (port of {@code MeetingView.tsx} + {@code MeetingViewHost.tsx}): header
 * with the back button, inline title editor and "Show files", a player area and the
 * transcript/notes pane.
 *
 * <p>Wiring for AppContext:
 * <pre>{@code
 * MeetingView view = MeetingView.create(meetingService, new MeetingViewActions(onClose, fileChooser, mediaHost));
 * // host view.ui() inside a KitRoot, then call view.open(mediaPath) (or let the service's
 * // open-request flow do it) after adding it to the scene.
 * }</pre>
 * The player is a seam ({@link MeetingMediaHost}); without it the view shows the player
 * placeholder and timestamps are not clickable, matching the P2 media staging.
 */
public final class MeetingView extends Region {

	private final MeetingUiService service;
	private final MeetingViewActions actions;

	private final VBox shell = new VBox();
	private final VBox titleBox = new VBox(2);
	private final Label titleLabel = new Label();
	private final Label metaLabel = new Label();
	private final Input titleInput = new Input();
	private final VBox playerHost = new VBox(10);
	private final MeetingNotesView notes;

	private Path mediaPath;
	private MeetingMeta meta;
	private boolean hasVideo;
	private boolean editingTitle;
	private Node player;
	private Subscription jobSubscription;
	private Subscription openSubscription;

	/** AppContext entry point (real service). */
	public static MeetingView create(MeetingService service, MeetingViewActions actions) {
		return new MeetingView(new ServiceMeetingUiService(service), actions);
	}

	/** Convenience for the app shell when no media/navigation callbacks are needed yet. */
	public static MeetingView create(MeetingService service) {
		return create(service, MeetingViewActions.none());
	}

	/** Test seam: any {@link MeetingUiService} implementation (deterministic snapshots/tests). */
	public static MeetingView create(MeetingUiService service, MeetingViewActions actions) {
		return new MeetingView(service, actions);
	}

	private MeetingView(MeetingUiService service, MeetingViewActions actions) {
		this.service = service;
		this.actions = actions == null ? MeetingViewActions.none() : actions;
		this.notes = new MeetingNotesView(service, seconds -> {
			if (this.actions.media() != null) {
				this.actions.media().seek(seconds);
			}
		});

		getStyleClass().add("meeting-view");
		setStyle("-fx-background-color: " + css(Theme.BG) + ";");
		shell.getStyleClass().add("meeting-view__shell");
		shell.setStyle("-fx-background-color: " + css(Theme.BG) + ";");

		shell.getChildren().addAll(buildHeader(), buildContent());
		getChildren().add(shell);

		titleInput.setVisible(false);
		titleInput.setManaged(false);
		titleInput.setOnAction(event -> commitTitle());
		titleInput.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				commitTitle();
			}
		});
		titleInput.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.ESCAPE) {
				cancelTitle();
			}
		});

		subscribeOpenRequests();
		service.takeOpenRequest().ifPresent(this::open);
		updateVisibility();
	}

	/** The view root; host it as a child of a {@code KitRoot}. */
	public Node ui() {
		return this;
	}

	/** Opens one meeting (called by the dashboard or the open-request flow). */
	public void open(Path newMediaPath) {
		if (newMediaPath == null) {
			close();
			return;
		}
		unsubscribeJob();
		mediaPath = newMediaPath;
		meta = null;
		hasVideo = hasVideoExtension(newMediaPath);
		editingTitle = false;
		titleLabel.setText("Meeting");
		metaLabel.setText("");
		titleInput.setVisible(false);
		titleInput.setManaged(false);
		titleLabel.setVisible(true);
		titleLabel.setManaged(true);
		rebuildPlayer();
		notes.open(newMediaPath);
		jobSubscription = service.onJobUpdate(
				update -> Platform.runLater(() -> onJobUpdate(update)));
		loadMeta();
		updateVisibility();
	}

	/** Closes the meeting view (back button); also notifies the app shell. */
	public void close() {
		unsubscribeJob();
		notes.close();
		mediaPath = null;
		meta = null;
		updateVisibility();
		actions.onClose().run();
	}

	/** Re-reads the metadata (job updates and renames do this automatically). */
	public void refresh() {
		loadMeta();
	}

	public Path mediaPath() {
		return mediaPath;
	}

	private Node buildHeader() {
		Button back = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		back.setGraphic(Icon.of(Icons.ArrowLeft, 20));
		back.setAccessibleText("Back to the library");
		// Keep the initial focus off the icon button: the kit focus ring paints over the ghost
		// background and would show a solid marker in the header.
		back.setFocusTraversable(false);
		back.setOnAction(event -> close());

		Label title = titleLabel;
		title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: "
				+ css(Theme.FG) + ";");
		title.setOnMouseClicked(event -> startTitleEdit());
		title.setAccessibleText("Rename");
		metaLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		titleBox.getChildren().addAll(title, titleInput, metaLabel);
		HBox.setHgrow(titleBox, Priority.ALWAYS);
		titleBox.setMaxWidth(Double.MAX_VALUE);

		Button reveal = new Button("Show files", Button.Variant.GHOST, Button.Size.SM);
		reveal.setGraphic(Icon.of(Icons.FolderOpen, 16));
		reveal.setGraphicTextGap(8);
		reveal.setOnAction(event -> {
			if (mediaPath != null) {
				service.revealMeeting(mediaPath);
			}
		});

		HBox header = new HBox(12, back, titleBox, reveal);
		header.setAlignment(Pos.CENTER_LEFT);
		header.setPadding(new Insets(12, 16, 12, 16));
		header.setStyle("-fx-border-color: transparent transparent " + css(Theme.SEPARATOR)
				+ " transparent; -fx-border-width: 0 0 1 0;");
		return header;
	}

	private Node buildContent() {
		VBox playerColumn = new VBox(10);
		playerColumn.setPrefWidth(480);
		playerColumn.setMinWidth(320);
		playerHost.setFillWidth(true);
		HBox.setHgrow(playerHost, Priority.NEVER);
		Label hint = new Label("Click a timestamp in the transcript to play from there.");
		hint.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		hint.setWrapText(true);
		playerColumn.getChildren().addAll(playerHost, hint);

		VBox notesHost = new VBox(notes);
		VBox.setVgrow(notes, Priority.ALWAYS);
		HBox.setHgrow(notesHost, Priority.ALWAYS);
		notesHost.setMaxWidth(Double.MAX_VALUE);

		HBox content = new HBox(16, playerColumn, notesHost);
		content.setPadding(new Insets(16));
		content.setAlignment(Pos.TOP_LEFT);
		VBox.setVgrow(content, Priority.ALWAYS);
		shell.setSpacing(0);
		return content;
	}

	private void rebuildPlayer() {
		playerHost.getChildren().clear();
		player = null;
		if (mediaPath == null) {
			return;
		}
		if (actions.media() != null) {
			player = actions.media().createPlayer(mediaPath, hasVideo);
			if (player != null) {
				playerHost.getChildren().add(player);
				return;
			}
		}
		VBox placeholder = new VBox(8);
		placeholder.setAlignment(Pos.CENTER);
		placeholder.setMinHeight(hasVideo ? 220 : 80);
		placeholder.setStyle("-fx-background-color: " + css(Theme.SURFACE) + "; -fx-background-radius: 12;");
		Icon icon = Icon.of(hasVideo ? Icons.VideoCamera : Icons.MusicNotes, 28, "fill");
		Label label = new Label("Player arrives with the media layer.");
		label.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		placeholder.getChildren().addAll(icon, label);
		playerHost.getChildren().add(placeholder);
	}

	private void loadMeta() {
		Path current = mediaPath;
		if (current == null) {
			return;
		}
		CompletableFuture.supplyAsync(() -> service.getFiles(current)).thenAcceptAsync(result -> {
			if (!current.equals(mediaPath)) {
				return;
			}
			if (result.isErr()) {
				Toast.show(this, result.errorMessage()).variant(Toast.Variant.DANGER);
				return;
			}
			MeetingService.FilesView files = result.orElse(null);
			meta = files == null ? null : files.meta();
			updateTitle();
		}, Platform::runLater);
	}

	private void updateTitle() {
		titleLabel.setText(MeetingText.meetingTitle(meta, null));
		StringBuilder metaLine = new StringBuilder();
		if (meta != null) {
			String createdAt = formatDateTime(meta.createdAt());
			String duration = MeetingText.formatMeetingDuration(meta.durationMs());
			String language = meta.language() == null || meta.language().isBlank() ? null
					: meta.language().toUpperCase(Locale.ROOT);
			for (String part : new String[] {createdAt, duration, language}) {
				if (part != null && !part.isBlank()) {
					if (metaLine.length() > 0) {
						metaLine.append(" · ");
					}
					metaLine.append(part);
				}
			}
		}
		metaLabel.setText(metaLine.toString());
		metaLabel.setVisible(metaLine.length() > 0);
		metaLabel.setManaged(metaLine.length() > 0);
	}

	private void startTitleEdit() {
		if (mediaPath == null) {
			return;
		}
		editingTitle = true;
		titleInput.setText(titleLabel.getText());
		titleInput.setVisible(true);
		titleInput.setManaged(true);
		titleLabel.setVisible(false);
		titleLabel.setManaged(false);
		titleInput.requestFocus();
		titleInput.selectAll();
	}

	private void commitTitle() {
		if (!editingTitle) {
			return;
		}
		String next = titleInput.getText() == null ? "" : titleInput.getText().trim();
		endTitleEdit();
		if (next.isEmpty() || next.equals(titleLabel.getText()) || mediaPath == null) {
			return;
		}
		Path current = mediaPath;
		CompletableFuture.supplyAsync(() -> service.renameMeeting(current, next))
				.thenAcceptAsync(result -> {
					if (result.isErr()) {
						Toast.show(this, result.errorMessage()).variant(Toast.Variant.DANGER);
						return;
					}
					titleLabel.setText(result.orElse(next));
					refresh();
				}, Platform::runLater);
	}

	private void cancelTitle() {
		editingTitle = false;
		titleInput.setVisible(false);
		titleInput.setManaged(false);
		titleLabel.setVisible(true);
		titleLabel.setManaged(true);
	}

	private void endTitleEdit() {
		editingTitle = false;
		titleInput.setVisible(false);
		titleInput.setManaged(false);
		titleLabel.setVisible(true);
		titleLabel.setManaged(true);
	}

	private void onJobUpdate(MeetingJob update) {
		if (mediaPath == null || update.mediaPath() == null || !update.mediaPath().equals(mediaPath)) {
			return;
		}
		if (update.status() == MeetingJob.Status.DONE) {
			loadMeta();
		}
	}

	private void subscribeOpenRequests() {
		openSubscription = service.onOpenRequested(
				() -> Platform.runLater(() -> service.takeOpenRequest().ifPresent(this::open)));
	}

	private void unsubscribeJob() {
		if (jobSubscription != null) {
			jobSubscription.close();
			jobSubscription = null;
		}
	}

	private void updateVisibility() {
		boolean open = mediaPath != null;
		setVisible(open);
		setManaged(open);
	}

	@Override
	protected void layoutChildren() {
		shell.resizeRelocate(0, 0, getWidth(), getHeight());
	}

	@Override
	protected double computePrefWidth(double height) {
		return 1200;
	}

	@Override
	protected double computePrefHeight(double width) {
		return 760;
	}

	private static boolean hasVideoExtension(Path mediaPath) {
		String name = mediaPath.getFileName() == null ? "" : mediaPath.getFileName().toString();
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm")
				|| lower.endsWith(".mkv");
	}

	private static String formatDateTime(String iso) {
		if (iso == null || iso.isBlank()) {
			return null;
		}
		try {
			LocalDateTime date = LocalDateTime.ofInstant(Instant.parse(iso), ZoneId.systemDefault());
			return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
					.withLocale(Locale.getDefault()).format(date);
		} catch (RuntimeException error) {
			return iso;
		}
	}

	private static String css(javafx.scene.paint.Color color) {
		return "#" + color.toString().substring(2, 8);
	}
}
