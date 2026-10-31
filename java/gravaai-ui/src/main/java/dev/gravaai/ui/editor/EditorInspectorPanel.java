package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

import dev.gravaai.core.editor.AnnotationCommands;
import dev.gravaai.core.editor.AudioCommands;
import dev.gravaai.core.editor.CaptionCommands;
import dev.gravaai.core.editor.ClipCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.KeystrokeCommands;
import dev.gravaai.core.editor.SelectionCommands;
import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.AutoCaptionAnimation;
import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.CursorClickEffectStyle;
import dev.gravaai.core.project.CursorMotionPresets;
import dev.gravaai.core.project.KeystrokeElement;
import dev.gravaai.core.project.KeystrokeOverlaySettings;
import dev.gravaai.core.project.Padding;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.WebcamCorner;
import dev.gravaai.core.project.WebcamOverlaySettings;
import dev.gravaai.core.project.WebcamPositionPreset;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.ChoiceGroup;
import dev.gravaai.ui.kit.Icon;
import dev.gravaai.ui.kit.Icons;
import dev.gravaai.ui.kit.Input;
import dev.gravaai.ui.kit.Label;
import dev.gravaai.ui.kit.Select;
import dev.gravaai.ui.kit.Slider;
import dev.gravaai.ui.kit.Switch;
import dev.gravaai.ui.kit.TextArea;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Right-hand inspector of the editor shell (port of the settings half of
 * {@code layout/EditorSidebar.tsx} + the section controls in {@code SettingsPanel.tsx}).
 *
 * <p>Every control edits the shared {@link EditorStore} through {@link EditorMutations}, so the
 * preview, undo/redo and project saving see the same state.
 */
public final class EditorInspectorPanel extends VBox {

	/** A transient inspector editor driven by the timeline selection, independent of the rail. */
	public enum SelectionEditor {
		NONE,
		ANNOTATION,
		ZOOM,
		CLIP,
		AUDIO,
		CAPTION,
		KEYSTROKE,
		MULTI
	}

	private final EditorStore store;
	private final EditorViewActions actions;
	private final EditorPlaybackPort playback;
	private final Label title = new Label("Scene");
	private final VBox content = new VBox(20);
	private final ScrollPane scroll;
	private EditorSidebarRail.Section section = EditorSidebarRail.Section.SCENE;
	private boolean advanced;
	private java.util.function.Supplier<java.nio.file.Path> recordingPath = () -> null;
	private final java.util.Map<EditorSidebarRail.Section, Boolean> advancedSections =
			new java.util.EnumMap<>(EditorSidebarRail.Section.class);
	private final Switch advancedSwitch = new Switch();
	private final Label advancedLabel = new Label("Advanced");
	private dev.gravaai.ui.editor.transcript.@Nullable CaptionModelUiService captionModels;
	private @Nullable AutoCloseable captionModelSubscription;
	private dev.gravaai.ui.editor.transcript.@Nullable TranscriptPanel transcriptPanel;
	private Runnable onAddCustomFont = () -> { };
	private boolean annotationMode;
	private @Nullable AnnotationSettingsPanel annotationPanel;
	private SelectionEditor selectionEditor = SelectionEditor.NONE;
	private @Nullable ZoomSettingsPanel zoomPanel;
	private Runnable onShortcutsConfig = () -> { };
	private Runnable onTutorial = () -> { };

	public EditorInspectorPanel(EditorStore store, EditorViewActions actions,
			EditorPlaybackPort playback) {
		this.store = store;
		this.actions = actions;
		this.playback = playback;
		getStyleClass().add("editor-inspector");

		title.getStyleClass().add("editor-inspector__title");
		HBox header = new HBox(8);
		header.getStyleClass().add("editor-inspector__header");
		header.setAlignment(Pos.CENTER_LEFT);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		advancedLabel.getStyleClass().add("editor-inspector__advanced-label");
		advancedSwitch.setChecked(false);
		// Each section remembers its own Advanced state (EditorSidebar advancedSections).
		advancedSwitch.onCheckedChange(value -> {
			if (value == advanced) {
				return;
			}
			advancedSections.put(section, value);
			advanced = value;
			rebuild();
		});
		header.getChildren().addAll(title, spacer, advancedLabel, advancedSwitch);

		content.setFillWidth(true);
		content.setPadding(new Insets(0, 0, 0, 0));
		scroll = new ScrollPane(content);
		scroll.getStyleClass().add("editor-inspector__scroll");
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		scroll.setMinHeight(0);

		getChildren().addAll(header, scroll);
		rebuild();
	}

	/** Called by the Settings section's "Add custom font" entry (shell owns the dialog). */
	public void setOnAddCustomFont(Runnable openDialog) {
		this.onAddCustomFont = openDialog == null ? () -> { } : openDialog;
	}

	/** Called by the Settings section's "Customize shortcuts" entry (shell owns the dialog). */
	public void setOnShortcutsConfig(Runnable openDialog) {
		this.onShortcutsConfig = openDialog == null ? () -> { } : openDialog;
	}

	/** Called by the Settings section's "Tutorial" entry (shell owns the dialog). */
	public void setOnTutorial(Runnable openDialog) {
		this.onTutorial = openDialog == null ? () -> { } : openDialog;
	}

	/** Shows the annotation appearance editor (React annotation section). */
	public void showAnnotationSettings() {
		this.annotationMode = true;
		this.selectionEditor = SelectionEditor.ANNOTATION;
		rebuild();
	}

	/** Shows the editor for the selected zoom while preserving the rail's active section. */
	public void showZoomSettings() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.ZOOM;
		rebuild();
	}

	/** Shows the editor for the selected clip. */
	public void showClipSettings() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.CLIP;
		rebuild();
	}

	/** Shows the editor for the selected audio region. */
	public void showAudioSettings() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.AUDIO;
		rebuild();
	}

	/** Shows the editor for the selected caption cue. */
	public void showCaptionSettings() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.CAPTION;
		rebuild();
	}

	/** Shows the editor for the selected keystroke press. */
	public void showKeystrokeSettings() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.KEYSTROKE;
		rebuild();
	}

	/** Shows the editor for a mixed multi-selection (count + batched delete). */
	public void showMultiSelection() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.MULTI;
		rebuild();
	}

	/**
	 * Picks the editor from the store's timeline selection. A multi-selection of one kind reuses
	 * that kind's editor (edits batch over all selected ids); a mixed multi-selection gets the
	 * MULTI editor. No selection falls back to the rail section that was active before the
	 * selection, so a click on empty timeline space cannot leave a stale editor behind.
	 */
	public void showSelectionSettings() {
		String multiKind = multiSelectionKind();
		if (SelectionCommands.TimelineSelections.ZOOM.equals(multiKind)) {
			showZoomSettings();
			return;
		}
		if (SelectionCommands.TimelineSelections.ANNOTATION.equals(multiKind)) {
			showAnnotationSettings();
			return;
		}
		if (SelectionCommands.TimelineSelections.AUDIO.equals(multiKind)) {
			showAudioSettings();
			return;
		}
		if (multiKind != null) {
			showMultiSelection();
			return;
		}
		if (store.selectedAnnotationId() != null) {
			showAnnotationSettings();
		} else if (store.selectedZoomId() != null) {
			showZoomSettings();
		} else if (store.selectedClipId() != null) {
			showClipSettings();
		} else if (store.selectedAudioId() != null) {
			showAudioSettings();
		} else if (store.selectedCaptionId() != null) {
			showCaptionSettings();
		} else if (store.selectedKeystrokeId() != null) {
			showKeystrokeSettings();
		} else if (isSelectionMode()) {
			show(section);
		}
	}

	/** Switches the visible section. */
	public void show(EditorSidebarRail.Section nextSection) {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.NONE;
		this.section = nextSection;
		rebuild();
	}

	/** The selection-driven editor currently shown, or {@link SelectionEditor#NONE} for a rail section. */
	public SelectionEditor selectionEditor() {
		return selectionEditor;
	}

	public EditorSidebarRail.Section section() {
		return section;
	}

	public boolean advanced() {
		return advanced;
	}

	/** Rebuilds the current section from the store (used after undo/redo). */
	public void refresh() {
		rebuild();
	}

	/** {@code hasAdvanced}: the sections whose SettingsPanel content has advanced controls. */
	static boolean hasAdvanced(EditorSidebarRail.Section section) {
		return switch (section) {
			// Only sections with controls rendered behind the Advanced switch. Cursor and
			// Settings currently have no advanced-only rows, so showing the toggle there is inert.
			case SCENE, WEBCAM, CAPTIONS -> true;
			case CURSOR, TRANSCRIPT, MEETING, SETTINGS -> false;
		};
	}

	/**
	 * Connects caption generation (Captions section) and the transcript panel (Transcript
	 * section) to the app's auto-caption controller.
	 */
	public void setCaptionModelService(
			dev.gravaai.ui.editor.transcript.CaptionModelUiService service) {
		closeQuietly(captionModelSubscription);
		captionModelSubscription = null;
		if (transcriptPanel != null) {
			transcriptPanel.dispose();
			transcriptPanel = null;
		}
		captionModels = service;
		if (service != null) {
			captionModelSubscription = service.addListener(state -> javafx.application.Platform
					.runLater(() -> {
						if (!annotationMode && section == EditorSidebarRail.Section.CAPTIONS) {
							rebuild();
						}
					}));
		}
		rebuild();
	}

	private static void closeQuietly(@Nullable AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception ignored) {
			// Unsubscribing is best effort.
		}
	}

	private void rebuild() {
		boolean selectionMode = isSelectionMode();
		advanced = !selectionMode && advancedSections.getOrDefault(section, false);
		boolean showAdvanced = !selectionMode && hasAdvanced(section);
		advancedLabel.setVisible(showAdvanced);
		advancedLabel.setManaged(showAdvanced);
		advancedSwitch.setVisible(showAdvanced);
		advancedSwitch.setManaged(showAdvanced);
		advancedSwitch.setChecked(advanced);
		title.setText(selectionMode ? selectionTitle() : section.label());
		content.getChildren().setAll(sectionContent());
		scroll.setVvalue(0);
	}

	private boolean isSelectionMode() {
		return annotationMode || selectionEditor != SelectionEditor.NONE;
	}

	private String selectionTitle() {
		return switch (selectionEditor) {
			case ANNOTATION -> "Annotation";
			case ZOOM -> "Zoom";
			case CLIP -> "Clip";
			case AUDIO -> "Audio";
			case CAPTION -> "Caption";
			case KEYSTROKE -> "Keystroke";
			case MULTI -> "Multiple";
			case NONE -> section.label();
		};
	}

	private @Nullable Node sectionContent() {
	if (annotationMode) {
		if (annotationPanel == null) {
			annotationPanel = new AnnotationSettingsPanel(store, actions);
		} else {
			annotationPanel.refresh();
		}
		return annotationPanel;
	}
	if (selectionEditor == SelectionEditor.ZOOM) {
		return zoomSelectionSection();
	}
	if (selectionEditor == SelectionEditor.CLIP) {
		return clipSelectionSection();
	}
	if (selectionEditor == SelectionEditor.AUDIO) {
		return audioSelectionSection();
	}
	if (selectionEditor == SelectionEditor.CAPTION) {
		return captionSelectionSection();
	}
	if (selectionEditor == SelectionEditor.KEYSTROKE) {
		return keystrokeSelectionSection();
	}
	if (selectionEditor == SelectionEditor.MULTI) {
		return multiSelectionSection();
	}
		return switch (section) {
			case SCENE -> sceneSection();
			case CURSOR -> cursorSection();
			case WEBCAM -> webcamSection();
			case CAPTIONS -> captionsSection();
			case TRANSCRIPT -> transcriptSection();
			case MEETING -> meetingSection();
			case SETTINGS -> settingsSection();
		};
	}

	// ---------------------------------------------------------------------
	// Timeline selection editors
	// ---------------------------------------------------------------------

	private Node zoomSelectionSection() {
		if (zoomPanel == null) {
			zoomPanel = new ZoomSettingsPanel(store);
		} else {
			zoomPanel.refresh();
		}
		return zoomPanel;
	}

	/** The zoom editor when it has been built (test/diagnostic hook). */
	public @Nullable ZoomSettingsPanel zoomSettingsPanel() {
		return zoomPanel;
	}

	private @Nullable Node clipSelectionSection() {
		ClipRegion clip = selectedClip();
		if (clip == null) {
			return emptySelection("Select a clip to edit its speed and audio.");
		}
		VBox root = new VBox(14);
		double speedValue = Double.isFinite(clip.speed()) && clip.speed() > 0 ? clip.speed() : 1;
		Slider speed = new Slider("Speed");
		speed.range(0.25, 4).step(0.25).formatter(
				value -> formatMultiplier(value)).sliderControl();
		speed.setValue(speedValue);
		speed.onValueCommit(value -> store.apply(new ClipCommands.ChangeSpeed(value)));

		Switch mute = new Switch("Mute clip");
		mute.setChecked(Boolean.TRUE.equals(clip.muted()));
		mute.onCheckedChange(value -> store.apply(new ClipCommands.SetMuted(value)));
		root.getChildren().addAll(speed, mute, deleteSelectionButton("Delete Clip",
				() -> store.apply(new ClipCommands.Delete(clip.id()))));
		return root;
	}

	private @Nullable Node audioSelectionSection() {
		AudioRegion audio = selectedAudio();
		if (audio == null) {
			return emptySelection("Select an audio region to edit its volume.");
		}
		List<String> multiIds = multiIdsOfKind(SelectionCommands.TimelineSelections.AUDIO);
		VBox root = new VBox(14);
		if (multiIds.size() > 1) {
			Label multi = new Label(
					multiIds.size() + " audio regions selected — edits apply to all.");
			multi.setWrapText(true);
			multi.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			root.getChildren().add(multi);
		}
		Slider volume = new Slider("Volume");
		volume.range(0, 1).step(0.01)
				.formatter(value -> Math.round(value * 100) + "%").sliderControl();
		volume.setValue(audio.volume());
		volume.onValueCommit(value -> applyMulti(SelectionCommands.TimelineSelections.AUDIO,
				id -> new AudioCommands.ChangeVolume(id, value),
				new AudioCommands.ChangeVolume(value)));

		Switch normalize = new Switch("Normalize");
		normalize.setChecked(Boolean.TRUE.equals(audio.normalize()));
		normalize.onCheckedChange(value -> applyMulti(SelectionCommands.TimelineSelections.AUDIO,
				id -> new AudioCommands.SetNormalize(id, value),
				new AudioCommands.SetNormalize(value)));
		root.getChildren().addAll(volume, normalize,
				deleteSelectionButton(multiIds.size() > 1
						? "Delete " + multiIds.size() + " Audio Regions" : "Delete Audio",
						() -> applyMulti(SelectionCommands.TimelineSelections.AUDIO,
								AudioCommands.Delete::new,
								new AudioCommands.Delete(audio.id()))));
		return root;
	}

	/** The multi-selection section for a mixed selection: count + batched delete. */
	private Node multiSelectionSection() {
		VBox root = new VBox(14);
		Label count = new Label(store.multiSelection().size() + " elements selected.");
		count.setWrapText(true);
		count.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
		root.getChildren().addAll(count,
				deleteSelectionButton("Delete " + store.multiSelection().size() + " Elements",
						this::deleteMultiSelection));
		return root;
	}

	/** Deletes every multi-selected zoom/annotation/audio as one undoable batch. */
	private void deleteMultiSelection() {
		List<dev.gravaai.core.editor.EditorCommand> deletes = new ArrayList<>();
		for (String id : store.multiSelection()) {
			String kind = SelectionCommands.kindOf(store, id);
			if (SelectionCommands.TimelineSelections.ZOOM.equals(kind)) {
				deletes.add(new ZoomCommands.Delete(id));
			} else if (SelectionCommands.TimelineSelections.ANNOTATION.equals(kind)) {
				deletes.add(new AnnotationCommands.Delete(id));
			} else if (SelectionCommands.TimelineSelections.AUDIO.equals(kind)) {
				deletes.add(new AudioCommands.Delete(id));
			}
		}
		if (!deletes.isEmpty()) {
			store.apply(new dev.gravaai.core.editor.BatchCommand(deletes));
			store.clearSelectionsExcept(null, null, null, null, null);
		}
	}

	/**
	 * Applies one edit to every multi-selected element of {@code kind} (one undoable batch) or to
	 * the single selection when fewer than two of that kind are selected.
	 */
	private void applyMulti(String kind,
			java.util.function.Function<String, dev.gravaai.core.editor.EditorCommand> commandForId,
			dev.gravaai.core.editor.EditorCommand single) {
		List<String> ids = multiIdsOfKind(kind);
		if (ids.size() > 1) {
			List<dev.gravaai.core.editor.EditorCommand> batch = new ArrayList<>(ids.size());
			for (String id : ids) {
				batch.add(commandForId.apply(id));
			}
			store.apply(new dev.gravaai.core.editor.BatchCommand(batch));
		} else {
			store.apply(single);
		}
	}

	/** The multi-selected ids of one kind; empty when fewer than two are selected. */
	private List<String> multiIdsOfKind(String kind) {
		List<String> ids = new ArrayList<>();
		if (store.multiSelection().size() > 1) {
			for (String id : store.multiSelection()) {
				if (kind.equals(SelectionCommands.kindOf(store, id))) {
					ids.add(id);
				}
			}
		}
		return ids;
	}

	/** The homogeneous kind of the multi-selection, {@code "mixed"} for several kinds, null for a single selection. */
	private @Nullable String multiSelectionKind() {
		if (store.multiSelection().size() <= 1) {
			return null;
		}
		String kind = null;
		for (String id : store.multiSelection()) {
			String idKind = SelectionCommands.kindOf(store, id);
			if (idKind == null) {
				continue;
			}
			if (kind == null) {
				kind = idKind;
			} else if (!kind.equals(idKind)) {
				return "mixed";
			}
		}
		return kind;
	}

	private @Nullable Node captionSelectionSection() {
		CaptionCue cue = selectedCaption();
		if (cue == null) {
			return emptySelection("Select a caption to edit its text.");
		}
		VBox root = new VBox(14);
		TextArea text = new TextArea();
		text.setText(cue.text() == null ? "" : cue.text());
		text.setPrefRowCount(3);
		text.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				String next = text.getText() == null ? "" : text.getText().strip();
				if (!next.equals(cue.text())) {
					store.apply(new CaptionCommands.TextEdit(cue.id(), next));
				}
			}
		});
		root.getChildren().addAll(text, deleteSelectionButton("Delete Caption",
				() -> store.apply(new CaptionCommands.Delete(cue.id()))));
		return root;
	}

	private @Nullable ClipRegion selectedClip() {
		String id = store.selectedClipId();
		if (id == null) {
			return null;
		}
		for (ClipRegion clip : store.state().clipRegions()) {
			if (clip.id().equals(id)) {
				return clip;
			}
		}
		return null;
	}

	private @Nullable AudioRegion selectedAudio() {
		String id = store.selectedAudioId();
		if (id == null) {
			return null;
		}
		for (AudioRegion audio : store.state().audioRegions()) {
			if (audio.id().equals(id)) {
				return audio;
			}
		}
		return null;
	}

	private @Nullable CaptionCue selectedCaption() {
		String id = store.selectedCaptionId();
		if (id == null) {
			return null;
		}
		for (CaptionCue cue : store.state().autoCaptions()) {
			if (cue.id().equals(id)) {
				return cue;
			}
		}
		return null;
	}

	/** The keystroke press editor: key text, modifier toggles, delete (React caption editor shape). */
	private @Nullable Node keystrokeSelectionSection() {
		KeystrokeElement element = selectedKeystroke();
		if (element == null) {
			return emptySelection("Select a keystroke to edit or remove it.");
		}
		VBox root = new VBox(14);

		Input key = new Input("Key");
		key.text(element.key()).fullWidth();
		key.focusedProperty().addListener((observable, wasFocused, focused) -> {
			if (!focused) {
				String next = key.getText() == null ? "" : key.getText().strip();
				if (!next.isEmpty() && !next.equals(element.key())) {
					store.apply(new KeystrokeCommands.ChangeLabel(element.id(), next,
							element.modifiers()));
				}
			}
		});
		root.getChildren().add(key);

		// 2 × 2 grid: the settings rail is narrow, so one row squeezes each switch's label until
		// it wraps per letter; sharing two rows leaves every label room on one line.
		GridPane modifiers = new GridPane();
		modifiers.setHgap(10);
		modifiers.setVgap(10);
		for (int column = 0; column < 2; column++) {
			ColumnConstraints constraints = new ColumnConstraints();
			constraints.setPercentWidth(50);
			modifiers.getColumnConstraints().add(constraints);
		}
		int cell = 0;
		for (String wire : KeystrokeElement.MODIFIER_ORDER) {
			// Wires are the canonical constants, so capitalizing is the display label.
			String label = wire.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + wire.substring(1);
			Switch toggle = new Switch(label);
			toggle.setMaxWidth(Double.MAX_VALUE);
			toggle.setChecked(element.modifiers().contains(wire));
			toggle.onCheckedChange(checked -> {
				List<String> next = new ArrayList<>(element.modifiers());
				if (checked) {
					if (!next.contains(wire)) {
						next.add(wire);
					}
				} else {
					next.remove(wire);
				}
				store.apply(new KeystrokeCommands.ChangeLabel(element.id(), element.key(), next));
			});
			modifiers.add(toggle, cell % 2, cell / 2);
			cell++;
		}
		root.getChildren().add(modifiers);

		root.getChildren().add(deleteSelectionButton("Delete Keystroke",
				() -> store.apply(new KeystrokeCommands.Delete(element.id()))));
		return root;
	}

	private @Nullable KeystrokeElement selectedKeystroke() {
		String id = store.selectedKeystrokeId();
		if (id == null) {
			return null;
		}
		for (KeystrokeElement element : store.state().keystrokeElements()) {
			if (element.id().equals(id)) {
				return element;
			}
		}
		return null;
	}

	private Label emptySelection(String message) {
		Label empty = new Label(message);
		empty.setWrapText(true);
		empty.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
		return empty;
	}

	private Button deleteSelectionButton(String text, Runnable action) {
		Button button = new Button(text, Button.Variant.DESTRUCTIVE_SOFT, Button.Size.SM);
		button.setMaxWidth(Double.MAX_VALUE);
		button.setAlignment(Pos.CENTER_LEFT);
		button.setOnAction(event -> action.run());
		return button;
	}

	private static String formatMultiplier(double value) {
		if (value == Math.rint(value)) {
			return (long) value + "x";
		}
		return Double.toString(value) + "x";
	}

	// ---------------------------------------------------------------------
	// Scene: background wallpaper + frame
	// ---------------------------------------------------------------------

	private VBox sceneSection() {
		var state = store.state();
		VBox root = new VBox(20);

		VBox background = new VBox(16);
		background.getChildren().add(heading("Background", () -> apply(json -> {
			json.put("backgroundBlur", 0);
			json.put("wallpaper", "/wallpapers/tahoe-light.jpg");
		})));
		background.getChildren().add(slider("Blur", state.backgroundBlur(), 0, 8, 0.25,
				value -> String.format(java.util.Locale.ROOT, "%.1fpx", value),
				value -> apply(json -> json.put("backgroundBlur", value))));

		ChoiceGroup tabs = choice("image", "Image", "video", "Video", "color", "Color",
				"gradient", "Gradient");
		tabs.setValue(EditorWallpapers.tabFor(state.wallpaper()));
		VBox tabContent = new VBox();
		tabContent.setPadding(new Insets(14, 0, 0, 0));
		tabs.onValueChange(value -> tabContent.getChildren().setAll(backgroundTab(value)));
		tabContent.getChildren().add(backgroundTab(tabs.value()));
		background.getChildren().addAll(tabs, tabContent);
		root.getChildren().add(background);

		VBox frame = new VBox(12);
		frame.getChildren().add(heading("Frame", () -> apply(json -> {
			json.put("shadowIntensity", 0.67);
			json.put("borderRadius", ProjectPersistence.getDefaultBorderRadiusPercent());
			ObjectNode padding = (ObjectNode) json.get("padding");
			padding.put("top", 20).put("bottom", 20).put("left", 20).put("right", 20)
					.put("linked", true);
		})));
		frame.getChildren().add(slider("Shadow", state.shadowIntensity(), 0, 1, 0.01,
				value -> Math.round(value * 100) + "%",
				value -> apply(json -> json.put("shadowIntensity", value))));
		frame.getChildren().add(slider("Radius", state.borderRadius(), 0, 50, 0.1,
				value -> formatPercent(value),
				value -> apply(json -> json.put("borderRadius", value))));
		frame.getChildren().addAll(paddingControls(state.padding()));
		root.getChildren().add(frame);
		return root;
	}

	private Node backgroundTab(String tab) {
		var state = store.state();
		switch (tab) {
			case "image" -> {
				return new WallpaperGrid(EditorWallpapers.imageTiles(), state.wallpaper(),
						this::applyWallpaper, this::chooseCustomWallpaper);
			}
			case "video" -> {
				return new WallpaperGrid(EditorWallpapers.VIDEO_TILES, state.wallpaper(),
						this::applyWallpaper, () -> actions.chooseVideoWallpaper().ifPresent(
								path -> applyWallpaper(path.toAbsolutePath().toString())));
			}
			case "color" -> {
				return colorTab(state.wallpaper());
			}
			default -> {
				return gradientTab(state.wallpaper());
			}
		}
	}

	private Node colorTab(@Nullable String selected) {
		VBox box = new VBox(12);
		GridPane grid = new GridPane();
		grid.getStyleClass().add("editor-tiles");
		for (int column = 0; column < 5; column++) {
			ColumnConstraints constraints = new ColumnConstraints();
			constraints.setPercentWidth(20);
			grid.getColumnConstraints().add(constraints);
		}
		List<String> palette = EditorWallpapers.COLOR_PALETTE.subList(0, 15);
		int index = 0;
		for (String hex : palette) {
			Button swatch = colorSwatch(hex, hex.equalsIgnoreCase(selected));
			grid.add(swatch, index % 5, index / 5);
			index++;
		}
		box.getChildren().add(grid);

		HBox custom = new HBox(8);
		custom.setAlignment(Pos.CENTER_LEFT);
		Input hex = new Input();
		hex.setPromptText("Custom color");
		hex.setText(EditorWallpapers.isHexColor(selected) ? selected : "#ADADAD");
		hex.setPrefWidth(140);
		Button applyButton = new Button("Apply", Button.Variant.SECONDARY, Button.Size.SM);
		applyButton.setOnAction(event -> {
			if (EditorWallpapers.isHexColor(hex.getText())) {
				applyWallpaper(hex.getText().trim());
			}
		});
		custom.getChildren().addAll(hex, applyButton);
		box.getChildren().add(custom);
		return box;
	}

	private Button colorSwatch(String hex, boolean selected) {
		Button button = new Button();
		button.getStyleClass().add("editor-tile");
		button.setMaxWidth(Double.MAX_VALUE);
		button.setPrefHeight(38);
		Region fill = new Region();
		fill.setMinSize(44, 34);
		fill.setPrefSize(44, 34);
		fill.setStyle("-fx-background-color: " + hex + "; -fx-background-radius: 6px;");
		javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(fill);
		if (selected) {
			Icon check = Icon.of(Icons.Check, 10, "bold");
			check.setIconColor(Color.WHITE);
			javafx.scene.layout.StackPane badge = new javafx.scene.layout.StackPane(check);
			badge.getStyleClass().add("editor-tile__check");
			javafx.scene.layout.StackPane.setAlignment(badge, Pos.BOTTOM_LEFT);
			stack.getChildren().add(badge);
		}
		button.setGraphic(stack);
		button.setOnAction(event -> applyWallpaper(hex));
		return button;
	}

	private Node gradientTab(@Nullable String selected) {
		GridPane grid = new GridPane();
		grid.getStyleClass().add("editor-tiles");
		for (int column = 0; column < 5; column++) {
			ColumnConstraints constraints = new ColumnConstraints();
			constraints.setPercentWidth(20);
			grid.getColumnConstraints().add(constraints);
		}
		for (int index = 0; index < EditorWallpapers.GRADIENTS.size(); index++) {
			String gradient = EditorWallpapers.GRADIENTS.get(index);
			Button button = new Button();
			button.getStyleClass().add("editor-tile");
			button.setMaxWidth(Double.MAX_VALUE);
			button.setPrefHeight(38);
			Region fill = new Region();
			fill.setMinSize(44, 34);
			fill.setPrefSize(44, 34);
			fill.setBackground(new javafx.scene.layout.Background(
					new javafx.scene.layout.BackgroundFill(
							EditorWallpapers.paintFor(gradient, Color.TRANSPARENT),
							new javafx.scene.layout.CornerRadii(6), Insets.EMPTY)));
			button.setGraphic(fill);
			button.setOnAction(event -> applyWallpaper(gradient));
			grid.add(button, index % 5, index / 5);
		}
		return grid;
	}

	private Node paddingControls(Padding padding) {
		boolean linked = !Boolean.FALSE.equals(padding.linked());
		VBox box = new VBox(12);
		if (advanced) {
			HBox linkRow = new HBox(12);
			linkRow.setAlignment(Pos.CENTER_LEFT);
			Label linkLabel = new Label("Link padding sides");
			linkLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			Switch link = new Switch();
			link.setChecked(linked);
			link.onCheckedChange(value -> apply(json -> {
				ObjectNode node = (ObjectNode) json.get("padding");
				node.put("linked", value);
			}));
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			linkRow.getChildren().addAll(linkLabel, spacer, link);
			box.getChildren().add(linkRow);
		}
		if (linked) {
			box.getChildren().add(slider("Padding", padding.top(), 0, 100, 1,
					value -> formatPercent(value), value -> apply(json -> {
						ObjectNode node = (ObjectNode) json.get("padding");
						node.put("top", value).put("bottom", value).put("left", value)
								.put("right", value).put("linked", true);
					})));
		} else {
			GridPane grid = new GridPane();
			grid.setHgap(12);
			grid.setVgap(6);
			ColumnConstraints half = new ColumnConstraints();
			half.setPercentWidth(50);
			grid.getColumnConstraints().addAll(half, half);
			grid.add(paddingSlider("Top", "top", padding.top(), 250), 0, 0);
			grid.add(paddingSlider("Bottom", "bottom", padding.bottom(), 250), 1, 0);
			grid.add(paddingSlider("Left", "left", padding.left(), 100), 0, 1);
			grid.add(paddingSlider("Right", "right", padding.right(), 100), 1, 1);
			box.getChildren().add(grid);
		}
		return box;
	}

	private Node paddingSlider(String label, String field, double value, double max) {
		return slider(label, value, 0, max, 1, next2 -> formatPercent(next2), next -> apply(json -> {
			ObjectNode node = (ObjectNode) json.get("padding");
			node.put(field, next).put("linked", false);
		}));
	}

	private void applyWallpaper(String value) {
		apply(json -> json.put("wallpaper", value));
		rebuild();
	}

	private void chooseCustomWallpaper() {
		actions.chooseWallpaper().ifPresent(path -> applyWallpaper(path.toAbsolutePath().toString()));
	}

	// ---------------------------------------------------------------------
	// Cursor
	// ---------------------------------------------------------------------

	private VBox cursorSection() {
		var state = store.state();
		VBox root = new VBox(14);

		Switch show = new Switch("Show cursor");
		show.setChecked(state.showCursor());
		show.onCheckedChange(value -> apply(json -> json.put("showCursor", value)));
		Switch loop = new Switch("Loop cursor");
		loop.setChecked(state.loopCursor());
		loop.onCheckedChange(value -> apply(json -> json.put("loopCursor", value)));
		root.getChildren().addAll(show, loop);

		Select style = new Select();
		for (String cursorStyle : List.of("tahoe", "tahoe-inverted", "macos", "windows11", "dot",
				"figma")) {
			style.item(cursorStyle, labelFor(cursorStyle));
		}
		style.value(state.cursorStyle());
		style.onValueChange(value -> apply(json -> json.put("cursorStyle", value)));
		root.getChildren().add(row("Style", style));

		ChoiceGroup motion = choice("focused", "Focused", "smooth", "Smooth");
		String activePreset = matchedMotionPreset(state);
		if (activePreset != null) {
			motion.setValue(activePreset);
		}
		motion.onValueChange(value -> applyMotionPreset(value));
		root.getChildren().add(row("Motion", motion));

		root.getChildren().add(slider("Sway", state.cursorSway(), 0, 2, 0.01,
				value -> String.format(java.util.Locale.ROOT, "%.2f", value),
				value -> apply(json -> json.put("cursorSway", value))));

		ChoiceGroup effect = choice("none", "Off", "spotlight", "Spotlight", "ripple", "Ripple",
				"echo", "Echo");
		effect.setValue(state.cursorClickEffect().wire());
		effect.onValueChange(value -> apply(json -> json.put("cursorClickEffect", value)));
		root.getChildren().add(row("Click effect", effect));

		GridPane colors = new GridPane();
		colors.getStyleClass().add("editor-tiles");
		List<String> effectColors = List.of("#2563EB", "#EF4444", "#F59E0B", "#22C55E",
				"#A855F7", "#EC4899", "#14B8A6", "#F97316");
		for (int index = 0; index < effectColors.size(); index++) {
			String color = effectColors.get(index);
			Button swatch = colorSwatch(color,
					color.equalsIgnoreCase(state.cursorClickEffectColor()));
			swatch.setOnAction(event -> apply(json -> json.put("cursorClickEffectColor", color)));
			colors.add(swatch, index % 4, index / 4);
		}
		root.getChildren().add(colors);

		root.getChildren().add(slider("Effect scale", state.cursorClickEffectScale(), 0.5, 2,
				0.01, value -> String.format(java.util.Locale.ROOT, "%.2f", value),
				value -> apply(json -> json.put("cursorClickEffectScale", value))));
		root.getChildren().add(slider("Effect opacity", state.cursorClickEffectOpacity(), 0, 1,
				0.01, value -> Math.round(value * 100) + "%",
				value -> apply(json -> json.put("cursorClickEffectOpacity", value))));
		root.getChildren().add(slider("Effect duration", state.cursorClickEffectDurationMs(), 120,
				1200, 10, value -> Math.round(value) + "ms",
				value -> apply(json -> json.put("cursorClickEffectDurationMs", value))));
		root.getChildren().add(keystrokesBlock(state.keystrokeOverlay()));
		return root;
	}

	/**
	 * "Key presses" block of the cursor section (SettingsPanel.tsx {@code effects.keystrokes}):
	 * show switch; position, key size and distance from edge once the recording has key presses;
	 * time on screen in Advanced. Without a {@code .keys.json} it explains how to record them.
	 */
	private VBox keystrokesBlock(KeystrokeOverlaySettings settings) {
		KeystrokeOverlaySettings overlay = settings == null ? KeystrokeOverlaySettings.DEFAULT : settings;
		VBox block = new VBox(12);
		block.getStyleClass().add("editor-keystrokes");
		Label title = new Label("Key presses");
		title.getStyleClass().add("editor-section__label");
		Switch show = new Switch("Show key presses");
		show.setChecked(overlay.enabled());
		show.onCheckedChange(value -> applyKeystrokes(json -> json.put("enabled", value)));
		block.getChildren().addAll(title, show);

		if (KeystrokeEvents.count(recordingPath.get()) == 0) {
			Label empty = new Label("No key presses were recorded. Turn on Record key presses in"
					+ " Settings > Recording before recording.");
			empty.setWrapText(true);
			empty.getStyleClass().add("editor-keystrokes__empty");
			empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			block.getChildren().add(empty);
			return block;
		}

		ChoiceGroup position = choice("bottom-center", "Bottom center", "bottom-left", "Bottom left",
				"bottom-right", "Bottom right", "top-center", "Top center");
		position.setValue(overlay.position().wire());
		position.onValueChange(value -> applyKeystrokes(json -> json.put("position", value)));
		position.setAccessibleText("Key press position");
		block.getChildren().add(position);
		block.getChildren().add(slider("Key size", overlay.fontSize(), 12, 96, 1,
				value -> Math.round(value) + " px",
				value -> applyKeystrokes(json -> json.put("fontSize", value))));
		block.getChildren().add(slider("Distance from edge", overlay.bottomOffset(), 0, 90, 1,
				value -> Math.round(value) + "%",
				value -> applyKeystrokes(json -> json.put("bottomOffset", value))));
		if (advanced) {
			block.getChildren().add(slider("Time on screen", overlay.lingerMs(), 200, 5000, 50,
					value -> String.format(java.util.Locale.ROOT, "%.2f s", value / 1000),
					value -> applyKeystrokes(json -> json.put("lingerMs", Math.round(value)))));
		}
		return block;
	}

	private void applyKeystrokes(Consumer<ObjectNode> mutation) {
		apply(json -> {
			JsonNode existing = json.get("keystrokeOverlay");
			mutation.accept(existing instanceof ObjectNode object ? object
					: json.putObject("keystrokeOverlay"));
		});
	}

	/** The recording whose {@code .keys.json} sidecar holds the key presses (preview media). */
	public void setRecordingPath(java.util.function.Supplier<java.nio.file.Path> path) {
		this.recordingPath = path == null ? () -> null : path;
		if (section == EditorSidebarRail.Section.CURSOR) {
			rebuild();
		}
	}

	private @Nullable String matchedMotionPreset(dev.gravaai.core.project.ProjectEditorState state) {
		return CursorMotionPresets.getMatchingCursorMotionPresetId(
				new CursorMotionPresets.SelectionInput(state.zoomInDurationMs(),
						state.zoomOutDurationMs(), state.cursorSize(), state.cursorSmoothing(),
						state.cursorSpringStiffnessMultiplier(),
						state.cursorSpringDampingMultiplier(), state.cursorSpringMassMultiplier(),
						state.cursorClickBounce(), state.cursorClickBounceDuration()));
	}

	private void applyMotionPreset(String id) {
		CursorMotionPresets.CursorMotionPreset preset = CursorMotionPresets.preset(id);
		if (preset == null) {
			return;
		}
		apply(json -> {
			json.put("zoomInDurationMs", preset.zoomInDurationMs());
			json.put("zoomOutDurationMs", preset.zoomOutDurationMs());
			json.put("cursorSize", preset.cursorSize());
			json.put("cursorSmoothing", preset.cursorSmoothing());
			json.put("cursorSpringStiffnessMultiplier", preset.cursorSpringStiffnessMultiplier());
			json.put("cursorSpringDampingMultiplier", preset.cursorSpringDampingMultiplier());
			json.put("cursorSpringMassMultiplier", preset.cursorSpringMassMultiplier());
			json.put("cursorClickBounce", preset.cursorClickBounce());
			json.put("cursorClickBounceDuration", preset.cursorClickBounceDuration());
		});
		rebuild();
	}

	// ---------------------------------------------------------------------
	// Webcam
	// ---------------------------------------------------------------------

	private VBox webcamSection() {
		var webcam = store.state().webcam();
		VBox root = new VBox(14);

		Switch enabled = new Switch("Enabled");
		enabled.setChecked(webcam.enabled());
		enabled.onCheckedChange(value -> applyWebcam(json -> json.put("enabled", value)));
		Switch mirror = new Switch("Mirror");
		mirror.setChecked(webcam.mirror());
		mirror.onCheckedChange(value -> {
			applyWebcam(json -> json.put("mirror", value));
			// The crop control edits a flipped rectangle when mirrored.
			rebuild();
		});
		Switch react = new Switch("React to zoom");
		react.setChecked(webcam.reactToZoom());
		react.onCheckedChange(value -> applyWebcam(json -> json.put("reactToZoom", value)));
		root.getChildren().addAll(enabled, mirror, react);

		Select position = new Select();
		for (WebcamPositionPreset preset : WebcamPositionPreset.values()) {
			position.item(preset.wire(), labelFor(preset.wire()));
		}
		position.value(webcam.positionPreset().wire());
		position.onValueChange(value -> applyWebcam(json -> {
			json.put("positionPreset", value);
			WebcamPositionPreset preset = WebcamPositionPreset.fromWireOrNull(value);
			if (preset != null && preset.isCorner()) {
				json.put("corner", value);
			}
		}));
		root.getChildren().add(row("Position", position));

		root.getChildren().add(slider("Size", webcam.size(), 10, 100, 1, value -> formatPercent(value),
				value -> applyWebcam(json -> json.put("size", value))));
		root.getChildren().add(slider("Width", webcam.width(), 10, 100, 1, value -> formatPercent(value),
				value -> applyWebcam(json -> json.put("width", value))));
		root.getChildren().add(slider("Height", webcam.height(), 10, 100, 1, value -> formatPercent(value),
				value -> applyWebcam(json -> json.put("height", value))));
		if (advanced) {
			// React: the crop control sits under Webcam > Advanced with a reset action.
			CropRegion cropRegion = webcam.cropRegion() == null ? CropRegion.DEFAULT
					: webcam.cropRegion();
			WebcamCropControl cropControl = new WebcamCropControl();
			cropControl.setCropRegion(cropRegion);
			cropControl.setMirrored(webcam.mirror());
			cropControl.setOnCropChange(next -> applyWebcam(json -> {
				var cropNode = json.putObject("cropRegion");
				cropNode.put("x", next.x());
				cropNode.put("y", next.y());
				cropNode.put("width", next.width());
				cropNode.put("height", next.height());
			}));
			VBox cropBlock = new VBox(8);
			cropBlock.setAlignment(javafx.geometry.Pos.CENTER);
			cropBlock.getChildren().addAll(heading("Crop", () -> applyWebcam(json -> {
				var cropNode = json.putObject("cropRegion");
				cropNode.put("x", 0);
				cropNode.put("y", 0);
				cropNode.put("width", 1);
				cropNode.put("height", 1);
			})), cropControl);
			root.getChildren().add(cropBlock);
		}
		root.getChildren().add(slider("Roundness", webcam.roundness(), 0, 100, 1,
				value -> formatPercent(value),
				value -> applyWebcam(json -> json.put("roundness", value))));
		root.getChildren().add(slider("Shadow", webcam.shadow(), 0, 1, 0.01,
				value -> Math.round(value * 100) + "%",
				value -> applyWebcam(json -> json.put("shadow", value))));
		root.getChildren().add(slider("Margin", webcam.margin(), 0, 96, 1,
				value -> Math.round(value) + "px",
				value -> applyWebcam(json -> json.put("margin", value))));
		return root;
	}

	// ---------------------------------------------------------------------
	// Captions
	// ---------------------------------------------------------------------

	/** {@code CAPTION_LANGUAGE_OPTIONS}. */
	private static final String[][] CAPTION_LANGUAGES = {
			{"auto", "Auto Detect"}, {"en", "English"}, {"es", "Spanish"}, {"fr", "French"},
			{"de", "German"}, {"it", "Italian"}, {"pt", "Portuguese"},
			{"zh", "Chinese (Simplified)"}, {"ja", "Japanese"}, {"ko", "Korean"}};

	/** Port of {@code captionsSectionContent} in SettingsPanel.tsx. */
	private VBox captionsSection() {
		AutoCaptionSettings settings = store.state().autoCaptionSettings();
		VBox root = new VBox(16);
		if (store.selectedCaptionId() != null) {
			root.getChildren().add(new CaptionListPanel(store, playback));
		}

		HBox header = new HBox(12);
		header.setAlignment(Pos.CENTER_LEFT);
		Label generation = new Label("Generation");
		generation.getStyleClass().add("editor-section__label");
		Button reset = new Button("Reset", Button.Variant.GHOST, Button.Size.SM);
		reset.getStyleClass().add("editor-section__reset");
		reset.setOnAction(event -> {
			// Normalisation fills a missing autoCaptionSettings with DEFAULT_AUTO_CAPTION_SETTINGS.
			apply(json -> json.remove("autoCaptionSettings"));
			rebuild();
		});
		Region headerSpacer = new Region();
		HBox.setHgrow(headerSpacer, Priority.ALWAYS);
		Label showLabel = new Label("Show");
		showLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		Switch enabled = new Switch();
		enabled.setChecked(settings.enabled());
		enabled.onCheckedChange(value -> applyCaptionSettings(json -> json.put("enabled", value)));
		header.getChildren().addAll(generation, reset, headerSpacer, showLabel, enabled);
		root.getChildren().add(header);

		var models = captionModels == null ? null : captionModels.state();
		boolean generating = models != null && models.generating();
		boolean downloading = models != null && models.downloadStatus()
				== dev.gravaai.ui.editor.transcript.CaptionModelUiService.DownloadStatus.DOWNLOADING;
		boolean hasModel = models != null && models.modelPath() != null;
		int downloadProgress = models == null ? 0 : models.downloadProgress();
		@Nullable String modelId = models == null ? null : models.modelId();
		int cueCount = store.state().autoCaptions().size();

		VBox controls = new VBox(12);
		controls.setPadding(new Insets(8, 0, 8, 0));
		if (advanced) {
			Button custom = new Button("Use custom", Button.Variant.OUTLINE, Button.Size.MD);
			custom.setMaxWidth(Double.MAX_VALUE);
			custom.setOnAction(event -> {
				if (captionModels != null) {
					captionModels.pickCustomModel();
				}
			});
			controls.getChildren().add(custom);
		}
		Select language = new Select();
		for (String[] option : CAPTION_LANGUAGES) {
			language.item(option[0], option[1]);
		}
		language.value(settings.language() == null || settings.language().isBlank() ? "auto"
				: settings.language());
		language.onValueChange(value -> applyCaptionSettings(json -> json.put("language", value)));
		language.setPrefWidth(180);
		controls.getChildren().add(row("Language", language));

		Select animation = new Select();
		for (AutoCaptionAnimation style : AutoCaptionAnimation.values()) {
			animation.item(style.wire(), labelFor(style.wire()));
		}
		animation.value(settings.animationStyle().wire());
		animation.onValueChange(
				value -> applyCaptionSettings(json -> json.put("animationStyle", value)));
		animation.setPrefWidth(180);
		controls.getChildren().add(row("Animation", animation));

		VBox modelRow = new VBox(6);
		Label modelTitle = new Label("Whisper model");
		modelTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: -g-fg;");
		Label modelDescription = new Label("Large turbo is much more accurate outside English; "
				+ "small is about three times faster.");
		modelDescription.setWrapText(true);
		modelDescription.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		ChoiceGroup modelChoice = new ChoiceGroup();
		modelChoice.item("large-v3-turbo-q5_0", "Large turbo (574 MB)");
		modelChoice.item("small", "Small (488 MB)");
		modelChoice.setValue(modelId == null ? "" : modelId);
		modelChoice.onValueChange(value -> {
			if (captionModels != null && value != null && !value.isEmpty()) {
				captionModels.selectModel(value);
			}
		});
		modelRow.getChildren().addAll(modelTitle, modelDescription, modelChoice);
		controls.getChildren().add(modelRow);

		Button modelButton;
		if (downloading) {
			modelButton = new Button("Downloading... " + downloadProgress + "%",
					Button.Variant.DEFAULT, Button.Size.MD);
			modelButton.setDisable(true);
		} else if (hasModel) {
			modelButton = new Button("Delete Model", Button.Variant.OUTLINE, Button.Size.MD);
			modelButton.setOnAction(event -> {
				if (captionModels != null) {
					captionModels.deleteSmallModel();
				}
			});
		} else {
			modelButton = new Button("Download Model", Button.Variant.DEFAULT, Button.Size.MD);
			modelButton.setOnAction(event -> {
				if (captionModels != null) {
					captionModels.downloadSmallModel();
				}
			});
		}
		modelButton.setMaxWidth(Double.MAX_VALUE);
		Button clear = new Button("Clear Captions", Button.Variant.OUTLINE, Button.Size.MD);
		clear.setMaxWidth(Double.MAX_VALUE);
		clear.setDisable(cueCount == 0);
		clear.setOnAction(event -> {
			apply(json -> {
				json.putArray("autoCaptions");
				((ObjectNode) json.get("autoCaptionSettings")).put("enabled", false);
			});
			rebuild();
		});
		VBox modelButtons = new VBox(8, modelButton, clear);
		controls.getChildren().add(modelButtons);

		String generateText = generating ? "Generating..."
				: cueCount > 0 ? "Regenerate Captions" : "Generate Captions";
		Button generate = new Button(generateText, Button.Variant.DEFAULT, Button.Size.MD);
		generate.setMaxWidth(Double.MAX_VALUE);
		generate.setDisable(generating || !hasModel);
		generate.setOnAction(event -> {
			if (captionModels != null) {
				captionModels.generateCaptions();
			}
		});
		VBox generateBox = new VBox(16, generate);
		if (generating) {
			Label status = new Label("Generating captions. This can take a moment.");
			status.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			javafx.scene.control.ProgressBar indeterminate = new javafx.scene.control.ProgressBar(
					javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS);
			indeterminate.setMaxWidth(Double.MAX_VALUE);
			generateBox.getChildren().add(new VBox(4, status, indeterminate));
		}
		controls.getChildren().add(generateBox);
		if (downloading) {
			javafx.scene.control.ProgressBar download = new javafx.scene.control.ProgressBar(
					downloadProgress / 100.0);
			download.setMaxWidth(Double.MAX_VALUE);
			controls.getChildren().add(download);
		}
		root.getChildren().add(controls);

		VBox fonts = new VBox(12);
		if (advanced) {
			Switch quickAdd = new Switch();
			quickAdd.setChecked(settings.timelineQuickAdd());
			quickAdd.onCheckedChange(
					value -> applyCaptionSettings(json -> json.put("timelineQuickAdd", value)));
			fonts.getChildren().add(row("Hover to add on timeline", quickAdd));
		}
		Label fontTitle = new Label("Font Settings");
		fontTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: 500; -fx-text-fill: -g-fg;");
		fonts.getChildren().add(fontTitle);
		fonts.getChildren().add(slider("Font size", settings.fontSize(), 16, 72, 1,
				value -> Math.round(value) + "px",
				value -> applyCaptionSettings(json -> json.put("fontSize", value))));
		fonts.getChildren().add(colorInput("Text color", settings.textColor(),
				value -> applyCaptionSettings(json -> json.put("textColor", value))));
		if (advanced) {
			fonts.getChildren().add(slider("Rows", settings.maxRows(), 1, 4, 1,
					value -> Long.toString(Math.round(value)),
					value -> applyCaptionSettings(json -> json.put("maxRows", Math.round(value)))));
			fonts.getChildren().add(slider("Bottom offset", settings.bottomOffset(), 0, 30, 1,
					value -> Math.round(value) + "%",
					value -> applyCaptionSettings(json -> json.put("bottomOffset", value))));
			fonts.getChildren().add(slider("Max width", settings.maxWidth(), 40, 95, 1,
					value -> Math.round(value) + "%",
					value -> applyCaptionSettings(json -> json.put("maxWidth", value))));
			fonts.getChildren().add(slider("Box radius", settings.boxRadius(), 0, 40, 0.5,
					value -> value == Math.rint(value)
							? String.format(java.util.Locale.ROOT, "%.0fpx", value)
							: String.format(java.util.Locale.ROOT, "%.1fpx", value),
					value -> applyCaptionSettings(json -> json.put("boxRadius", value))));
			fonts.getChildren().add(slider("Background opacity", settings.backgroundOpacity(),
					0, 1, 0.01, value -> Math.round(value * 100) + "%",
					value -> applyCaptionSettings(json -> json.put("backgroundOpacity", value))));
		}
		root.getChildren().add(fonts);
		return root;
	}

	private ChoiceGroup choice(String... valuesAndLabels) {
		ChoiceGroup group = new ChoiceGroup();
		for (int index = 0; index + 1 < valuesAndLabels.length; index += 2) {
			group.item(valuesAndLabels[index], valuesAndLabels[index + 1]);
		}
		return group;
	}

	private Node colorInput(String label, @Nullable String value, Consumer<String> onChange) {
		HBox row = new HBox(8);
		row.setAlignment(Pos.CENTER_LEFT);
		Label caption = new Label(label);
		caption.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Input input = new Input();
		input.setText(value);
		input.setPrefWidth(110);
		input.setOnAction(event -> {
			if (EditorWallpapers.isHexColor(input.getText())) {
				onChange.accept(input.getText().trim());
			}
		});
		row.getChildren().addAll(caption, spacer, input);
		return row;
	}

	// ---------------------------------------------------------------------
	// Transcript / meeting / settings
	// ---------------------------------------------------------------------

	private VBox transcriptSection() {
		VBox root = new VBox(6);
		if (captionModels != null) {
			// One panel per service: it keeps word selection and feedback across section switches.
			// The app controller applies generated cues, so the panel's sink is a no-op.
			if (transcriptPanel == null) {
				transcriptPanel = new dev.gravaai.ui.editor.transcript.TranscriptPanel(store,
						captionModels.transcript(),
						() -> playback.state().currentTimeSeconds() * 1000.0,
						() -> playback.state().isPlaying(), playback::seek, null, cues -> { });
			} else {
				transcriptPanel.refresh();
			}
			VBox.setVgrow(transcriptPanel, Priority.ALWAYS);
			root.getChildren().add(transcriptPanel);
			return root;
		}
		var cues = store.state().autoCaptions();
		if (cues.isEmpty()) {
			Label empty = new Label("No captions yet. Generate captions to edit the transcript.");
			empty.setWrapText(true);
			empty.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
			root.getChildren().add(empty);
			return root;
		}
		for (var cue : cues) {
			HBox row = new HBox(10);
			row.setAlignment(Pos.TOP_LEFT);
			row.setPadding(new Insets(4, 4, 4, 4));
			row.setStyle("-fx-background-radius: 6px;");
			Label time = new Label(PlaybackControls.formatTime(cue.startMs() / 1000.0));
			time.setStyle("-fx-font-size: 10px; -fx-text-fill: -g-muted;");
			time.setMinWidth(38);
			Label text = new Label(cue.text());
			text.setWrapText(true);
			text.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(255,255,255,0.85);");
			HBox.setHgrow(text, Priority.ALWAYS);
			row.getChildren().addAll(time, text);
			row.setOnMouseClicked(event -> {
				store.setSelectedCaptionId(cue.id());
				playback.seek(cue.startMs() / 1000.0);
			});
			root.getChildren().add(row);
		}
		return root;
	}

	private VBox meetingSection() {
		VBox root = new VBox(10);
		Label empty = new Label("Open a recording to transcribe it and write notes.");
		empty.setWrapText(true);
		empty.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
		root.getChildren().add(empty);
		return root;
	}

	private VBox settingsSection() {
		VBox root = new VBox(14);
		Switch autoZooms = new Switch("Auto-apply fresh recording auto-zooms");
		autoZooms.setChecked(actions.autoApplyFreshRecordingAutoZooms());
		autoZooms.onCheckedChange(actions::setAutoApplyFreshRecordingAutoZooms);
		root.getChildren().add(autoZooms);

		VBox buttons = new VBox(8);
		buttons.getChildren().addAll(
				// SettingsPanel.tsx has a single "Keyboard shortcuts -> Customize" row; the config
				// dialog already lists every binding, so no separate help entry.
				settingsButton("Customize keyboard shortcuts", Icons.Keyboard, onShortcutsConfig),
				settingsButton("Tutorial", Icons.DocumentText, onTutorial),
				settingsButton("Add custom font", Icons.TextB, onAddCustomFont),
				settingsButton("Caption list", Icons.ClosedCaptioning, actions::openCaptionList),
				settingsButton("Extensions", Icons.PuzzlePiece, actions::openExtensions));
		root.getChildren().add(buttons);

		// React editor Settings -> Permissions category (Linux parts of the page).
		Label permissionsHeading = new Label("Permissions");
		permissionsHeading.getStyleClass().add("editor-section__label");
		root.getChildren().addAll(permissionsHeading,
				new dev.gravaai.ui.dashboard.RecordingPermissionsSettings());
		return root;
	}

	private Button settingsButton(String text, String icon, Runnable action) {
		Button button = new Button(text, Button.Variant.SECONDARY, Button.Size.SM);
		button.icon(icon);
		button.setGraphicTextGap(8);
		button.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
		button.setMaxWidth(Double.MAX_VALUE);
		button.setAlignment(Pos.CENTER_LEFT);
		button.setOnAction(event -> action.run());
		return button;
	}

	// ---------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------

	private void apply(Consumer<ObjectNode> mutation) {
		EditorMutations.update(store, mutation);
	}

	private void applyWebcam(Consumer<ObjectNode> mutation) {
		apply(json -> mutation.accept((ObjectNode) json.get("webcam")));
	}

	private void applyCaptionSettings(Consumer<ObjectNode> mutation) {
		apply(json -> mutation.accept((ObjectNode) json.get("autoCaptionSettings")));
	}

	private HBox heading(String text, Runnable reset) {
		HBox row = new HBox(8);
		row.getStyleClass().add("editor-section__heading");
		Label label = new Label(text);
		label.getStyleClass().add("editor-section__label");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button resetButton = new Button("Reset", Button.Variant.GHOST, Button.Size.SM);
		resetButton.getStyleClass().add("editor-section__reset");
		resetButton.setOnAction(event -> {
			reset.run();
			rebuild();
		});
		row.getChildren().addAll(label, spacer, resetButton);
		return row;
	}

	private HBox row(String label, Node control) {
		HBox row = new HBox(12);
		row.setAlignment(Pos.CENTER_LEFT);
		Label caption = new Label(label);
		caption.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		row.getChildren().addAll(caption, spacer, control);
		return row;
	}

	private Slider slider(String label, double value, double min, double max, double step,
			DoubleFunction<String> formatter, Consumer<Double> onCommit) {
		Slider slider = new Slider(label);
		slider.range(min, max).step(step).formatter(formatter).sliderControl();
		slider.setValue(value);
		slider.onValueCommit(onCommit);
		slider.setMaxWidth(Double.MAX_VALUE);
		return slider;
	}

	private static String formatPercent(double value) {
		return Math.round(value) + "%";
	}

	private static String labelFor(String wire) {
		if (wire == null || wire.isEmpty()) {
			return "";
		}
		String spaced = wire.replace('-', ' ');
		return spaced.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + spaced.substring(1);
	}

	/** The crop-region editor opens from the preview toolbar; keep the type referenced here. */
	public static CropRegion defaultCrop() {
		return CropRegion.DEFAULT;
	}

	/** Kept so the webcam corner enum is part of this class's compile-time contract. */
	static WebcamCorner defaultCorner() {
		return WebcamCorner.BOTTOM_RIGHT;
	}

	static WebcamOverlaySettings defaultWebcam() {
		return WebcamOverlaySettings.defaults();
	}

	static CursorClickEffectStyle defaultEffect() {
		return CursorClickEffectStyle.NONE;
	}
}
