package dev.gravaai.ui.editor.transcript;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.event.Subscription;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.ui.editor.captions.CaptionListModel;
import dev.gravaai.ui.editor.timeline.PlaybackSeek;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Theme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Transcript editing panel (port of {@code TranscriptPanel.tsx}, plan §5.13/P3.1).
 *
 * <p>Flattens the project captions into source-time words, groups them into speech paragraphs,
 * marks words cut out of the timeline, selects words (click / Shift range / Ctrl toggle, filler
 * button) and cuts the selection through a {@link TranscriptCutController} (the default one applies
 * {@code ClipCommands.CutSourceRanges} to the {@link EditorStore}, so undo restores footage).
 * Clicks seek through the clip-speed-aware {@link TranscriptTimeMap}; the word under the playhead is
 * highlighted. Caption generation is driven through {@link TranscriptUiService} and the panel shows
 * its progress/errors and applies the generated cues through the supplied sink.
 */
public final class TranscriptPanel extends VBox {

	private final EditorStore store;
	private final TranscriptUiService service;
	private final DoubleSupplier timelinePlayheadMs;
	private final BooleanSupplier playing;
	private final PlaybackSeek seek;
	private final TranscriptCutController cutController;
	private final Consumer<List<CaptionCue>> captionsSink;
	private final Subscription storeSubscription;
	private final AutoCloseable serviceSubscription;

	private final Button fillerButton = new Button("Select filler words", Button.Variant.SECONDARY,
			Button.Size.SM);
	private final Label hintLabel = new Label();
	private final VBox generationBox = new VBox(6);
	private final Label generationMessage = new Label();
	private final ProgressBar generationProgress = new ProgressBar(0);
	private final Button cancelGenerationButton = new Button("Cancel", Button.Variant.GHOST,
			Button.Size.SM);
	private final Label emptyLabel = new Label("No transcript yet. Generate captions first.");
	private final Button generateButton = new Button("Generate captions", Button.Variant.SECONDARY,
			Button.Size.SM);
	private final VBox contentHost = new VBox(8);
	private final ScrollPane scroll;
	private final Button clearButton = new Button("Clear selection", Button.Variant.GHOST,
			Button.Size.SM);
	private final Button cutButton = new Button("Cut selected (0)", Button.Variant.DEFAULT,
			Button.Size.SM);
	private final Label feedbackLabel = new Label();
	private final Map<String, javafx.scene.control.Button> wordButtons = new LinkedHashMap<>();
	private final List<String> paragraphHeaders = new ArrayList<>();

	private List<TranscriptWords.Word> words = List.of();
	private Set<String> cutKeys = Set.of();
	private Set<String> selected = new LinkedHashSet<>();
	private @Nullable String anchorKey;
	private @Nullable String activeWordKey;
	private @Nullable String feedback;
	private boolean feedbackDanger;
	private @Nullable Object appliedDoneStatus;

	/**
	 * @param cutController optional cut controller; null uses the store-backed one
	 * @param captionsSink optional sink for generated cues; null writes them to the store
	 */
	public TranscriptPanel(EditorStore store, TranscriptUiService service,
			DoubleSupplier timelinePlayheadMs, BooleanSupplier playing, PlaybackSeek seek,
			@Nullable TranscriptCutController cutController,
			@Nullable Consumer<List<CaptionCue>> captionsSink) {
		this.store = store;
		this.service = service;
		this.timelinePlayheadMs = timelinePlayheadMs == null ? () -> 0.0 : timelinePlayheadMs;
		this.playing = playing == null ? () -> false : playing;
		this.seek = seek == null ? seconds -> { } : seek;
		this.cutController = cutController == null ? new StoreTranscriptCutController(store)
				: cutController;
		this.captionsSink = captionsSink == null ? store::setAutoCaptions : captionsSink;

		getStyleClass().add("transcript-panel");
		setSpacing(10);
		setPadding(new Insets(12));
		setStyle("-fx-background-color: " + css(Theme.BG) + ";");
		setFocusTraversable(true);

		hintLabel.setText(
				"Click a word, Shift-click a range, then press Delete to cut it from the video.");
		hintLabel.setWrapText(true);
		hintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		fillerButton.setOnAction(event -> selectFillers());

		generationMessage.setWrapText(true);
		generationMessage.setStyle("-fx-font-size: 12px; -fx-text-fill: " + css(Theme.FG) + ";");
		generationProgress.setPrefWidth(Double.MAX_VALUE);
		generationProgress.setStyle("-fx-accent: " + css(Theme.ACCENT) + ";");
		cancelGenerationButton.setOnAction(event -> service.cancel());
		HBox progressRow = new HBox(8, generationProgress, cancelGenerationButton);
		progressRow.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(generationProgress, Priority.ALWAYS);
		generationBox.getChildren().addAll(generationMessage, progressRow);
		generationBox.setStyle("-fx-background-color: " + css(Theme.SURFACE)
				+ "; -fx-background-radius: 10; -fx-padding: 8 10 8 10;");
		generationBox.setVisible(false);
		generationBox.setManaged(false);

		VBox header = new VBox(6, fillerButton, hintLabel, generationBox);
		header.setPadding(new Insets(0, 0, 4, 0));

		contentHost.setPadding(new Insets(2));
		VBox emptyState = new VBox(10, emptyLabel, generateButton);
		emptyState.setAlignment(Pos.CENTER);
		emptyState.setPadding(new Insets(40, 12, 40, 12));
		emptyLabel.setWrapText(true);
		emptyLabel.setMaxWidth(260);
		emptyLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		generateButton.setOnAction(event -> service.generateCaptions());
		contentHost.getChildren().add(emptyState);

		scroll = new ScrollPane(contentHost);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("custom-scrollbar");
		scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
		contentHost.setStyle("-fx-background-color: " + css(Theme.BG) + ";");
		VBox.setVgrow(scroll, Priority.ALWAYS);

		clearButton.setOnAction(event -> {
			clearSelection();
			requestFocus();
		});
		cutButton.setOnAction(event -> cutSelected());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		feedbackLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + css(Theme.MUTED) + ";");
		HBox footer = new HBox(8, feedbackLabel, spacer, clearButton, cutButton);
		footer.setAlignment(Pos.CENTER_LEFT);
		footer.setPadding(new Insets(8, 0, 0, 0));

		getChildren().addAll(header, scroll, footer);

		setOnKeyPressed(this::handleKeyPressed);
		wordButtons.clear();
		this.storeSubscription = store.onChange(changed -> Platform.runLater(this::refresh));
		this.serviceSubscription = service.addListener(status -> Platform.runLater(() -> {
			onServiceStatus(status);
			refresh();
		}));
		refresh();
	}

	public TranscriptPanel(EditorStore store, TranscriptUiService service) {
		this(store, service, () -> 0.0, () -> false, seconds -> { }, null, null);
	}

	/** Rebuilds words/selection/progress from the store and service state. */
	public void refresh() {
		List<CaptionCue> cues = store.state().autoCaptions();
		List<ClipRegion> clips = store.state().clipRegions();
		words = TranscriptWords.flatten(cues);
		Set<String> nextCutKeys = new LinkedHashSet<>();
		for (TranscriptWords.Word word : words) {
			if (!TranscriptWords.isWordKept(word, clips)) {
				nextCutKeys.add(word.key());
			}
		}
		cutKeys = Set.copyOf(nextCutKeys);
		selected.retainAll(keptWordKeys());
		if (anchorKey != null && !keptWordKeys().contains(anchorKey)) {
			anchorKey = null;
		}
		TranscriptTimeMap timeMap = new TranscriptTimeMap(clips);
		TranscriptWords.Word active = playing.getAsBoolean()
				? timeMap.activeWord(words, timelinePlayheadMs.getAsDouble()) : null;
		activeWordKey = active == null ? null : active.key();
		rebuildWords(clips);
		updateActions();
		updateGenerationStatus();
	}

	private void rebuildWords(List<ClipRegion> clips) {
		wordButtons.clear();
		paragraphHeaders.clear();
		TranscriptTimeMap timeMap = new TranscriptTimeMap(clips);
		contentHost.getChildren().clear();
		if (words.isEmpty()) {
			VBox emptyState = new VBox(10, emptyLabel, generateButton);
			emptyState.setAlignment(Pos.CENTER);
			emptyState.setPadding(new Insets(40, 12, 40, 12));
			contentHost.getChildren().add(emptyState);
			return;
		}
		boolean firstParagraph = true;
		for (List<TranscriptWords.Word> paragraph : TranscriptWords.paragraphs(words)) {
			VBox block = new VBox(2);
			TranscriptWords.Word firstWord = paragraph.get(0);
			String speaker = service.speakerForCue(firstWord.cueId());
			String time = CaptionListModel.formatTimecode(firstWord.startMs());
			Label header = new Label(speaker == null || speaker.isBlank() ? time : time + " · " + speaker);
			header.setStyle("-fx-font-size: 10px; -fx-text-fill: " + css(Theme.MUTED) + ";");
			paragraphHeaders.add(header.getText());
			FlowPane flow = new FlowPane(3, 2);
			flow.setPrefWrapLength(100);
			for (TranscriptWords.Word word : paragraph) {
				javafx.scene.control.Button node = new javafx.scene.control.Button(word.text());
				node.setAccessibleText(word.key());
				node.setFocusTraversable(false);
				node.setMnemonicParsing(false);
				styleWord(node, word);
				node.setOnAction(event -> handleWordClick(word, false, false));
				wordButtons.put(word.key(), node);
				flow.getChildren().add(node);
			}
			if (!firstParagraph) {
				block.setPadding(new Insets(6, 0, 0, 0));
			}
			firstParagraph = false;
			block.getChildren().addAll(header, flow);
			contentHost.getChildren().add(block);
		}
		// Keep the transcript mapping available to the active-word scroll in tests/shells.
		timeMap.activeWord(words, timelinePlayheadMs.getAsDouble());
	}

	private void styleWord(javafx.scene.control.Button node, TranscriptWords.Word word) {
		boolean cut = cutKeys.contains(word.key());
		boolean isSelected = !cut && selected.contains(word.key());
		boolean active = !cut && word.key().equals(activeWordKey);
		String background = isSelected ? "rgba(47,179,163,0.28)" : "transparent";
		String textColor = cut ? css(Theme.MUTED) : css(Theme.FG);
		node.setStyle("-fx-background-color: " + background + "; -fx-background-radius: 4;"
				+ " -fx-padding: 0 2 0 2; -fx-font-size: 13px; -fx-text-fill: " + textColor + ";"
				+ (cut ? " -fx-strikethrough: true;" : "")
				+ (active ? " -fx-underline: true;" : ""));
	}

	private void handleWordClick(TranscriptWords.Word word, boolean shift, boolean toggle) {
		requestFocus();
		TranscriptTimeMap timeMap = new TranscriptTimeMap(store.state().clipRegions());
		if (cutKeys.contains(word.key())) {
			Double seconds = timeMap.seekSecondsForSourceMs(word.startMs());
			if (seconds != null) {
				seek.seek(seconds);
			}
			return;
		}
		if (shift && anchorKey != null) {
			int anchorIndex = indexOf(anchorKey);
			int index = indexOf(word.key());
			if (anchorIndex >= 0 && index >= 0) {
				int from = Math.min(anchorIndex, index);
				int to = Math.max(anchorIndex, index);
				Set<String> range = new LinkedHashSet<>();
				for (int cursor = from; cursor <= to; cursor++) {
					TranscriptWords.Word candidate = words.get(cursor);
					if (!cutKeys.contains(candidate.key())) {
						range.add(candidate.key());
					}
				}
				selected = range;
			}
		} else if (toggle) {
			if (!selected.remove(word.key())) {
				selected.add(word.key());
			}
			anchorKey = word.key();
		} else {
			selected = new LinkedHashSet<>(List.of(word.key()));
			anchorKey = word.key();
		}
		Double seconds = timeMap.seekSecondsForSourceMs(word.startMs());
		if (seconds != null) {
			seek.seek(seconds);
		}
		refresh();
	}

	private int indexOf(String key) {
		for (int index = 0; index < words.size(); index++) {
			if (words.get(index).key().equals(key)) {
				return index;
			}
		}
		return -1;
	}

	private void handleKeyPressed(KeyEvent event) {
		if (event.getCode() == KeyCode.ESCAPE) {
			clearSelection();
			refresh();
			event.consume();
			return;
		}
		if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
			if (!selected.isEmpty() && !event.isControlDown() && !event.isMetaDown()) {
				cutSelected();
				event.consume();
			}
		}
	}

	private void cutSelected() {
		Set<String> spoken = new LinkedHashSet<>(selected);
		spoken.removeAll(cutKeys);
		if (spoken.isEmpty()) {
			return;
		}
		List<dev.gravaai.core.editor.TranscriptCut.SourceRange> ranges = TranscriptWords
				.wordsToCutRanges(words, spoken, TranscriptWords.DEFAULT_TRAILING_GAP_MS);
		boolean applied = cutController.cut(ranges);
		if (!applied) {
			feedback = "That would remove the whole video.";
			feedbackDanger = true;
			refresh();
			return;
		}
		int count = spoken.size();
		feedback = count == 1 ? "Cut 1 word" : "Cut " + count + " words";
		feedbackDanger = false;
		selected.clear();
		anchorKey = null;
		refresh();
	}

	private void selectFillers() {
		Set<String> fillers = TranscriptWords.selectFillerWords(words, language());
		Set<String> kept = new LinkedHashSet<>();
		for (String key : fillers) {
			if (!cutKeys.contains(key)) {
				kept.add(key);
			}
		}
		if (kept.isEmpty()) {
			feedback = "No filler words found.";
			feedbackDanger = false;
		} else {
			selected = kept;
			anchorKey = kept.iterator().next();
			feedback = kept.size() == 1 ? "Selected 1 filler word"
					: "Selected " + kept.size() + " filler words";
			feedbackDanger = false;
		}
		refresh();
	}

	private void clearSelection() {
		selected.clear();
		anchorKey = null;
		refresh();
	}

	private void updateActions() {
		int count = selected.size();
		cutButton.setText("Cut selected (" + count + ")");
		cutButton.setDisable(count == 0);
		clearButton.setDisable(count == 0);
		fillerButton.setDisable(words.isEmpty());
		if (feedback == null) {
			feedback = "";
			feedbackDanger = false;
		}
		feedbackLabel.setText(feedback);
		feedbackLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: "
				+ (feedbackDanger ? css(Theme.DANGER) : css(Theme.MUTED)) + ";");
	}

	private void updateGenerationStatus() {
		TranscriptUiService.Status status = service.status();
		boolean generating = status != null && status.generating();
		generationBox.setVisible(generating);
		generationBox.setManaged(generating);
		if (generating) {
			generationProgress.setProgress(Math.max(0.05, status.progress() / 100.0));
			generationMessage.setText(status.message() == null ? "Generating captions…"
					: status.message() + " " + status.progress() + "%");
		}
		generateButton.setDisable(generating);
	}

	private void onServiceStatus(TranscriptUiService.Status status) {
		if (status == null) {
			return;
		}
		if (status.state() == TranscriptUiService.State.DONE && status.cues() != null
				&& !status.cues().isEmpty() && appliedDoneStatus != status) {
			appliedDoneStatus = status;
			captionsSink.accept(status.cues());
			feedback = status.message();
			feedbackDanger = false;
		} else if (status.state() == TranscriptUiService.State.ERROR) {
			feedback = status.error() != null && !status.error().isBlank()
					? "Caption generation failed: " + status.error()
					: status.message();
			feedbackDanger = true;
		} else if (status.state() == TranscriptUiService.State.CANCELLED) {
			feedback = status.message();
			feedbackDanger = false;
		}
	}

	private Set<String> keptWordKeys() {
		Set<String> kept = new LinkedHashSet<>();
		for (TranscriptWords.Word word : words) {
			if (!cutKeys.contains(word.key())) {
				kept.add(word.key());
			}
		}
		return kept;
	}

	private String language() {
		var settings = store.state().autoCaptionSettings();
		return settings == null || settings.language() == null ? "auto" : settings.language();
	}

	public void dispose() {
		storeSubscription.close();
		try {
			serviceSubscription.close();
		} catch (Exception ignored) {
			// best-effort unsubscribe
		}
	}

	/* ---------- inspection (tests/shell) ---------- */

	public int wordCount() {
		return words.size();
	}

	public List<String> wordKeys() {
		return words.stream().map(TranscriptWords.Word::key).toList();
	}

	public Set<String> selectedWordKeys() {
		return Set.copyOf(selected);
	}

	public Set<String> cutWordKeys() {
		return cutKeys;
	}

	public boolean isWordCut(String key) {
		return cutKeys.contains(key);
	}

	public @Nullable String activeWordKey() {
		return activeWordKey;
	}

	public int paragraphCount() {
		return TranscriptWords.paragraphs(words).size();
	}

	public String cutButtonText() {
		return cutButton.getText();
	}

	public String feedbackText() {
		return feedbackLabel.getText();
	}

	public javafx.scene.control.@Nullable Button wordButton(String key) {
		return wordButtons.get(key);
	}

	/** Test/shell seam: selects a word as a click (optionally Shift-range or Ctrl-toggle) plus seek. */
	public void clickWord(String key, boolean shift, boolean toggle) {
		for (TranscriptWords.Word word : words) {
			if (word.key().equals(key)) {
				handleWordClick(word, shift, toggle);
				refresh();
				return;
			}
		}
	}

	/** Paragraph header lines (time + optional speaker), for tests and shells. */
	public List<String> paragraphHeaderTexts() {
		return List.copyOf(paragraphHeaders);
	}

	/** Test/shell seam: the Cancel button of the generation progress row. */
	public javafx.scene.control.Button cancelGenerationButton() {
		return cancelGenerationButton;
	}

	public javafx.scene.control.Button generateButton() {
		return generateButton;
	}

	public javafx.scene.control.Button fillerButton() {
		return fillerButton;
	}

	public javafx.scene.control.Button cutSelectedButton() {
		return cutButton;
	}

	public javafx.scene.control.Button clearSelectionButton() {
		return clearButton;
	}

	public String generationMessageText() {
		return generationMessage.getText();
	}

	public boolean isGenerating() {
		return generationBox.isVisible();
	}

	private static String css(javafx.scene.paint.Color color) {
		return "#" + color.toString().substring(2, 8);
	}
}
