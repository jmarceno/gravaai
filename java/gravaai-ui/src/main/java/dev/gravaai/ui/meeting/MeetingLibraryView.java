package dev.gravaai.ui.meeting;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.gravaai.core.result.Result;
import dev.gravaai.core.result.Result.Unit;
import dev.gravaai.services.meeting.MeetingLibraryEntry;
import dev.gravaai.ui.dashboard.SettingsRow;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Dialog;
import dev.gravaai.ui.kit.DropdownMenu;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The meetings library (port of the Meetings section of the dashboard: {@code useMeetingLibrary},
 * {@code MeetingThumbnail} and the library actions of {@code MeetingViewHost}): a card per meeting
 * with thumbnail, status/duration/notes badges, open, reveal, rename and delete.
 */
public final class MeetingLibraryView extends VBox {

	private final MeetingUiService service;
	private final MeetingViewActions actions;
	private final Consumer<Path> onOpen;

	private final FlowPane grid = new FlowPane();
	private final VBox host = new VBox(16);
	private final Label countLabel = new Label();
	private final Label statusLabel = new Label();
	private final ScrollPane scroll;

	private List<MeetingLibraryEntry> meetings = List.of();
	private boolean loading = true;
	private @Nullable String error;

	public MeetingLibraryView(MeetingUiService service, MeetingViewActions actions,
			Consumer<Path> onOpen) {
		this.service = service;
		this.actions = actions == null ? MeetingViewActions.none() : actions;
		this.onOpen = onOpen == null ? path -> { } : onOpen;
		getStyleClass().add("meeting-library");
		setSpacing(20);
		setPadding(new Insets(24, 24, 0, 24));

		Label title = new Label("Meetings");
		title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: "
				+ css(Theme.FG) + ";");
		countLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		Button importButton = new Button("Import", Button.Variant.DEFAULT, Button.Size.SM);
		importButton.setGraphic(Icon.of(Icons.UploadSimple, 16));
		importButton.setGraphicTextGap(8);
		importButton.setOnAction(event -> importFile());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(12, new VBox(2, title, countLabel), spacer, importButton);
		header.setAlignment(Pos.CENTER_LEFT);

		statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		statusLabel.setWrapText(true);

		grid.setHgap(24);
		grid.setVgap(24);
		grid.setPadding(new Insets(4, 4, 24, 4));
		grid.setAlignment(Pos.TOP_LEFT);
		host.getChildren().add(grid);
		scroll = new ScrollPane(host);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("custom-scrollbar");
		scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
		host.setStyle("-fx-background-color: " + css(Theme.BG) + ";");
		VBox.setVgrow(scroll, Priority.ALWAYS);

		getChildren().addAll(header, statusLabel, scroll);
		refresh();
	}

	/** Reloads the meeting list off the FX thread. */
	public void refresh() {
		loading = true;
		error = null;
		rebuild();
		CompletableFuture.supplyAsync(service::listMeetings).thenAcceptAsync(result -> {
			loading = false;
			if (result.isErr()) {
				error = result.errorMessage();
				meetings = List.of();
			} else {
				meetings = result.orElse(List.of());
			}
			rebuild();
		}, Platform::runLater);
	}

	private void rebuild() {
		grid.getChildren().clear();
		countLabel.setText(meetings.size() + (meetings.size() == 1 ? " meeting" : " meetings"));
		boolean visibleError = error != null && !error.isBlank();
		statusLabel.setVisible(visibleError);
		statusLabel.setManaged(visibleError);
		if (visibleError) {
			statusLabel.setText(error);
		}
		if (loading) {
			Label loadingLabel = new Label("Loading meetings…");
			loadingLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + css(Theme.MUTED) + ";");
			grid.getChildren().add(loadingLabel);
			return;
		}
		if (meetings.isEmpty()) {
			VBox empty = new VBox(8);
			empty.setAlignment(Pos.CENTER);
			empty.setPadding(new Insets(48));
			Label heading = new Label("No meetings yet.");
			heading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: "
					+ css(Theme.FG) + ";");
			Label hint = new Label(
					"Record with meeting mode, or import a recording to transcribe and write notes.");
			hint.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
			hint.setWrapText(true);
			hint.setMaxWidth(420);
			empty.getChildren().addAll(heading, hint);
			grid.getChildren().add(empty);
			return;
		}
		for (MeetingLibraryEntry entry : meetings) {
			grid.getChildren().add(card(entry));
		}
	}

	private Node card(MeetingLibraryEntry entry) {
		VBox card = new VBox(10);
		card.setPrefWidth(264);
		card.setMaxWidth(264);
		card.setStyle("-fx-background-color: transparent;");

		card.getChildren().add(thumbnail(entry));

		Label title = new Label(entry.title() == null || entry.title().isBlank() ? "Meeting"
				: entry.title());
		title.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: "
				+ css(Theme.FG) + ";");
		title.setMaxWidth(Double.MAX_VALUE);
		Label meta = new Label(metaLine(entry));
		meta.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		card.getChildren().addAll(title, meta);

		Button open = new Button("Open", Button.Variant.SECONDARY, Button.Size.SM);
		open.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(open, Priority.ALWAYS);
		open.setOnAction(event -> onOpen.accept(entry.mediaPath()));
		Button options = new Button("", Button.Variant.GHOST, Button.Size.ICON);
		options.setGraphic(Icon.of(Icons.DotsThree, 18, "bold"));
		options.setAccessibleText("Options for " + (entry.title() == null ? "meeting" : entry.title()));
		DropdownMenu menu = new DropdownMenu(options);
		menu.item("Show files", () -> {
			Result<Unit> result = service.revealMeeting(entry.mediaPath());
			if (result.isErr()) {
				Toast.show(this, result.errorMessage()).variant(Toast.Variant.DANGER);
			}
		});
		menu.item("Rename", () -> startRename(entry, card, title));
		menu.dangerItem("Delete", () -> confirmDelete(entry));
		HBox actions = new HBox(4, open, options);
		actions.setAlignment(Pos.CENTER_LEFT);
		card.getChildren().add(actions);
		return card;
	}

	private Node thumbnail(MeetingLibraryEntry entry) {
		// Same placeholder component as the dashboard grid (port of MeetingThumbnail.tsx).
		StackPane thumbnail = new StackPane(new dev.gravaai.ui.dashboard.MeetingThumbnail(entry));
		thumbnail.setPrefSize(264, 198);
		thumbnail.setMinSize(264, 198);
		thumbnail.setMaxSize(264, 198);
		thumbnail.setStyle("-fx-background-color: " + css(Theme.SURFACE) + "; -fx-background-radius: 12;");
		return thumbnail;
	}

	private void startRename(MeetingLibraryEntry entry, VBox card, Label title) {
		Input input = new Input();
		input.setText(entry.title() == null ? "" : entry.title());
		input.setMaxWidth(Double.MAX_VALUE);
		int index = card.getChildren().indexOf(title);
		card.getChildren().set(index, input);
		input.requestFocus();
		input.selectAll();
		Runnable commit = () -> {
			String next = input.getText() == null ? "" : input.getText().trim();
			card.getChildren().set(index, title);
			if (next.isEmpty() || next.equals(title.getText())) {
				return;
			}
			CompletableFuture.supplyAsync(() -> service.renameMeeting(entry.mediaPath(), next))
					.thenAcceptAsync(result -> {
						if (result.isErr()) {
							Toast.show(this, result.errorMessage()).variant(Toast.Variant.DANGER);
							return;
						}
						title.setText(result.orElse(next));
						refresh();
					}, Platform::runLater);
		};
		input.setOnAction(event -> commit.run());
		input.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				commit.run();
			}
		});
	}

	private void confirmDelete(MeetingLibraryEntry entry) {
		String name = entry.title() == null || entry.title().isBlank() ? "this meeting" : entry.title();
		Dialog dialog = new Dialog("Delete \u201c" + name + "\u201d?");
		dialog.width(420);
		dialog.description("The recording, transcript and notes move to the system trash. A meeting "
				+ "that is still being processed cannot be deleted.");
		Button cancel = new Button("Cancel", Button.Variant.GHOST, Button.Size.SM);
		cancel.setOnAction(event -> dialog.close());
		Button delete = new Button("Delete", Button.Variant.DESTRUCTIVE, Button.Size.SM);
		delete.setOnAction(event -> {
			dialog.close();
			CompletableFuture.supplyAsync(() -> service.deleteMeetings(List.of(entry.mediaPath())))
					.thenAcceptAsync(result -> {
						if (result.isErr()) {
							Toast.show(this, result.errorMessage()).variant(Toast.Variant.DANGER);
							return;
						}
						refresh();
					}, Platform::runLater);
		});
		dialog.footer(cancel, delete);
		dialog.show(this);
	}

	private void importFile() {
		Path picked = actions.importFileChooser().get();
		if (picked == null) {
			return;
		}
		CompletableFuture.supplyAsync(() -> service.importMeeting(picked)).thenAcceptAsync(result -> {
			if (result.isErr()) {
				Toast.show(this, result.errorMessage()).variant(Toast.Variant.DANGER);
				return;
			}
			Toast.show(this, "Meeting imported").variant(Toast.Variant.SUCCESS);
			refresh();
		}, Platform::runLater);
	}

	private static String metaLine(MeetingLibraryEntry entry) {
		List<String> parts = new ArrayList<>();
		if (entry.createdAt() != 0) {
			try {
				LocalDateTime date = LocalDateTime.ofInstant(
						Instant.ofEpochMilli(entry.createdAt()), ZoneId.systemDefault());
				parts.add(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
						.withLocale(Locale.getDefault()).format(date));
			} catch (RuntimeException error) {
				// Keep the line short when the timestamp is unreadable.
			}
		}
		String duration = MeetingText.formatMeetingDuration(entry.durationMs());
		if (duration != null) {
			parts.add(duration);
		}
		if (entry.hasVideo()) {
			parts.add("Video");
		} else {
			parts.add("Audio");
		}
		return String.join(" · ", parts);
	}

	private static String css(javafx.scene.paint.Color color) {
		return "#" + color.toString().substring(2, 8);
	}
}
