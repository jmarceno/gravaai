package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

import dev.gravaai.core.editor.AnnotationCommands;
import dev.gravaai.core.editor.AudioCommands;
import dev.gravaai.core.editor.CameraLayoutCommands;
import dev.gravaai.core.editor.CaptionCommands;
import dev.gravaai.core.editor.ClipCommands;
import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.GradeCommands;
import dev.gravaai.core.editor.KeystrokeCommands;
import dev.gravaai.core.editor.SelectionCommands;
import dev.gravaai.core.editor.SourceAudioCommands;
import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.core.project.AudioCleanup;
import dev.gravaai.core.project.AudioRegion;
import dev.gravaai.core.project.AutoCaptionAnimation;
import dev.gravaai.core.project.AutoCaptionSettings;
import dev.gravaai.core.project.CameraLayout;
import dev.gravaai.core.project.CameraLayoutSegment;
import dev.gravaai.core.project.CaptionCue;
import dev.gravaai.core.project.ClipRegion;
import dev.gravaai.core.project.ClipTransition;
import dev.gravaai.core.project.ClipTransitionKind;
import dev.gravaai.core.project.CropRegion;
import dev.gravaai.core.project.CursorClickEffectStyle;
import dev.gravaai.core.project.CursorMotionPresets;
import dev.gravaai.core.project.GradeRegion;
import dev.gravaai.core.project.GradeTarget;
import dev.gravaai.core.project.KeystrokeElement;
import dev.gravaai.core.project.KeystrokeOverlaySettings;
import dev.gravaai.core.project.Padding;
import dev.gravaai.core.project.ProjectPersistence;
import dev.gravaai.core.project.WebcamBackgroundEffect;
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
import dev.gravaai.ui.editor.timeline.core.TimelineSourceAudioTrack;
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
		CAMERA_LAYOUT,
		ELEMENT,
		GRADE,
		MULTI
	}

	private final EditorStore store;
	private @Nullable ElementSettingsPanel elementPanel;
	private final EditorViewActions actions;
	private final EditorPlaybackPort playback;
	private final Label title = new Label("Scene");
	private final VBox content = new VBox(20);
	private final ScrollPane scroll;
	private EditorSidebarRail.Section section = EditorSidebarRail.Section.SCENE;
	private boolean advanced;
	private java.util.function.Supplier<java.nio.file.Path> recordingPath = () -> null;
	/** The project's source-audio lanes; the Audio section lists one cleanup group per track. */
	private java.util.function.Supplier<List<TimelineSourceAudioTrack>> sourceAudioTracks = List::of;
	private final java.util.Map<EditorSidebarRail.Section, Boolean> advancedSections =
			new java.util.EnumMap<>(EditorSidebarRail.Section.class);
	private final Switch advancedSwitch = Switch.xs();
	private final Label advancedLabel = new Label("Advanced");
	private final Map<EditorSidebarRail.Section, Button> sectionTabs = new EnumMap<>(
			EditorSidebarRail.Section.class);
	/** Which inspector groups are collapsed, keyed by section + heading title. */
	private final Map<String, Boolean> collapsedGroups = new java.util.HashMap<>();
	private java.util.function.LongSupplier trackCount = () -> 3;
	private final Label footLabel = new Label("");
	private java.util.function.Consumer<EditorSidebarRail.Section> onSectionTab = section -> { };
	private dev.gravaai.ui.editor.transcript.@Nullable CaptionModelUiService captionModels;
	private dev.gravaai.ui.editor.@Nullable AutoMaskUiService autoMask;
	private @Nullable AutoCloseable autoMaskSubscription;
	/** The export-only deep-zoom upscaler (Settings section); null until the app wires it. */
	private dev.gravaai.ui.editor.@Nullable SuperResolutionUiService superResolution;
	private @Nullable AutoCloseable superResolutionSubscription;
	/** Detector toggles; both on by default, matching the ship defaults. */
	private boolean autoMaskFaces = true;
	private boolean autoMaskText = true;
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

		footLabel.getStyleClass().add("editor-inspector__foot-label");
		HBox foot = new HBox(footLabel);
		foot.getStyleClass().add("editor-inspector__foot");

		getChildren().addAll(header, buildSectionTabs(), scroll, foot);
		rebuild();
	}

	/**
	 * The icon tab row under the header (mock {@code .insp__tabs}): one tab per rail section,
	 * routed through the same {@code rail.select} path so the rail, tabs and panel agree.
	 */
	private HBox buildSectionTabs() {
		HBox tabs = new HBox(2);
		tabs.getStyleClass().add("editor-inspector__tabs");
		for (EditorSidebarRail.Section candidate : EditorSidebarRail.Section.values()) {
			Button tab = new Button("", Button.Variant.GHOST, Button.Size.SM);
			tab.getStyleClass().add("editor-inspector__tab");
			tab.icon(Icon.of(candidate.icon(), 14));
			tab.setMaxWidth(Double.MAX_VALUE);
			HBox.setHgrow(tab, Priority.ALWAYS);
			dev.gravaai.ui.kit.Tooltip.install(tab, candidate.label());
			tab.setOnAction(event -> onSectionTab.accept(candidate));
			sectionTabs.put(candidate, tab);
			tabs.getChildren().add(tab);
		}
		return tabs;
	}

	/** Replaces the section-tab action (the shell routes tabs through {@code rail.select}). */
	public void onSectionTab(java.util.function.Consumer<EditorSidebarRail.Section> listener) {
		this.onSectionTab = listener == null ? section -> { } : listener;
	}

	/** Supplies the live timeline row count for the footer's track readout. */
	public void setTrackCount(java.util.function.LongSupplier count) {
		this.trackCount = count == null ? () -> 3 : count;
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

	/**
	 * The Camera layout scope: the Webcam panel edits the selected block instead of the project.
	 * The panel is the same one either way — only its scope (and title) change.
	 */
	public void showCameraLayoutSettings() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.CAMERA_LAYOUT;
		rebuild();
	}

	/** Shows the editor for the selected animated element. */
	public void showElementSettings() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.ELEMENT;
		rebuild();
	}

	/** Shows the editor for a mixed multi-selection (count + batched delete). */
	public void showMultiSelection() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.MULTI;
		rebuild();
	}

	/** Shows the editor for the selected Color-track grade. */
	public void showGradeSettings() {
		this.annotationMode = false;
		this.selectionEditor = SelectionEditor.GRADE;
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
		} else if (store.selectedCameraLayoutId() != null) {
			showCameraLayoutSettings();
		} else if (store.selectedElementId().isPresent()) {
			showElementSettings();
		} else if (store.selectedGradeId() != null) {
			showGradeSettings();
		} else if (isSelectionMode()) {
			// Leaving the Camera block scope returns to the project scope of the same Webcam
			// panel: an empty-lane click must not drop the user on an unrelated section.
			show(selectionEditor == SelectionEditor.CAMERA_LAYOUT
					? EditorSidebarRail.Section.WEBCAM : section);
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
			case SCENE, WEBCAM, CAPTIONS, KEYSTROKES, AUTO_MASK -> true;
			case COLOR, CURSOR, AUDIO, TRANSCRIPT, MEETING, SETTINGS -> false;
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

	/**
	 * Connects the AUTO-MASK section to the app's controller. Rebuilds when a run starts or
	 * finishes so the button flips between Detect and Cancel.
	 */
	public void setAutoMaskService(AutoMaskUiService service) {
		closeQuietly(autoMaskSubscription);
		autoMaskSubscription = null;
		autoMask = service;
		if (service != null) {
			autoMaskSubscription = service.addListener(state -> javafx.application.Platform
					.runLater(() -> {
						if (!annotationMode && section == EditorSidebarRail.Section.AUTO_MASK) {
							rebuild();
						}
					}));
		}
		rebuild();
	}

	/**
	 * Connects the SETTINGS section's export quality row to the app's upscaler controller. Rebuilds
	 * on every state change so the download progress and the toggle follow the install.
	 */
	public void setSuperResolutionService(SuperResolutionUiService service) {
		closeQuietly(superResolutionSubscription);
		superResolutionSubscription = null;
		superResolution = service;
		if (service != null) {
			java.util.function.Consumer<SuperResolutionUiService.State> listener =
					state -> javafx.application.Platform.runLater(() -> {
						if (!annotationMode && section == EditorSidebarRail.Section.SETTINGS) {
							rebuild();
						}
					});
			service.addListener(listener);
			superResolutionSubscription = () -> service.removeListener(listener);
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
		// Mock .insp__head h2: mono uppercase section title.
		title.setText((selectionMode ? selectionTitle() : section.label())
				.toUpperCase(java.util.Locale.ROOT));
		for (Map.Entry<EditorSidebarRail.Section, Button> entry : sectionTabs.entrySet()) {
			entry.getValue().getStyleClass().remove("is-selected");
			if (entry.getKey() == section) {
				entry.getValue().getStyleClass().add("is-selected");
			}
		}
		footLabel.setText("PROJECT · " + trackCount.getAsLong() + " TRACKS");
		content.getChildren().setAll(groupCollapsibleSections(sectionContent()));
		scroll.setVvalue(0);
	}

	/**
	 * Wraps the section's groups into collapsible {@code .sec} blocks. Section builders nest their
	 * groups as subsection VBoxes whose first child is a heading row (Background, Frame, …), and
	 * some build headings inline; both become one group: a 30px head with chevron and hover Reset,
	 * the body below, a hairline under the block. Groups keep their collapsed state per section.
	 */
	private Node groupCollapsibleSections(@Nullable Node root) {
		if (root == null) {
			return new Region();
		}
		if (!(root instanceof VBox box) || box.getChildren().isEmpty()) {
			return root;
		}
		List<Node> result = new ArrayList<>();
		VBox currentBody = null;
		// Grouping re-parents children (groups wrap the subsection VBoxes), so iterate a copy.
		for (Node child : new ArrayList<>(box.getChildren())) {
			if (child instanceof HBox heading
					&& heading.getStyleClass().contains("editor-section__heading")) {
				currentBody = startCollapsibleGroup(heading, result);
			} else if (currentBody != null) {
				currentBody.getChildren().add(asCollapsibleGroup(child));
			} else {
				result.add(asCollapsibleGroup(child));
			}
		}
		VBox grouped = new VBox(0);
		grouped.setFillWidth(true);
		grouped.getChildren().setAll(result);
		return grouped;
	}

	/** A subsection VBox that opens with a heading becomes the group itself. */
	private Node asCollapsibleGroup(Node child) {
		if (child instanceof VBox box && !box.getChildren().isEmpty()
				&& box.getChildren().get(0) instanceof HBox first
				&& first.getStyleClass().contains("editor-section__heading")) {
			HBox heading = (HBox) box.getChildren().get(0);
			box.getChildren().remove(0);
			// The body keeps the subsection's own spacing; the group adds head + hairline.
			VBox group = collapsibleGroup(heading, box, box.getSpacing());
			return group;
		}
		return child;
	}

	/**
	 * Starts one group from an inline heading: the head goes into {@code result} and the body is
	 * returned for the following controls, until the next heading.
	 */
	private VBox startCollapsibleGroup(HBox heading, List<Node> result) {
		VBox body = new VBox(14);
		result.add(collapsibleGroup(heading, body, 14));
		return body;
	}

	/**
	 * One collapsible group: head (chevron + the heading row with its hover Reset) over the body,
	 * a hairline under the block. Toggling collapses the body in place.
	 */
	private VBox collapsibleGroup(HBox heading, VBox body, double bodySpacing) {
		String key = section.name() + "/" + headingText(heading);
		boolean collapsed = collapsedGroups.getOrDefault(key, false);
		Icon chevron = Icon.of(dev.gravaai.ui.kit.Icons.CaretDown, 11);
		chevron.getStyleClass().add("editor-section__chevron");
		chevron.setRotate(collapsed ? -90 : 0);
		HBox head = new HBox(6, chevron, heading);
		head.getStyleClass().add("editor-section__head");
		head.setAlignment(Pos.CENTER_LEFT);
		head.setPadding(new Insets(0, 10, 0, 10));
		// The heading row (label + spacer + Reset) stretches so Reset sits at the far edge.
		HBox.setHgrow(heading, Priority.ALWAYS);
		heading.setMaxWidth(Double.MAX_VALUE);
		body.setSpacing(bodySpacing);
		body.getStyleClass().add("editor-section__body");
		body.setPadding(new Insets(0, 10, 10, 10));
		body.setVisible(!collapsed);
		body.setManaged(!collapsed);
		head.setOnMouseClicked(event -> {
			boolean nowCollapsed = !collapsedGroups.getOrDefault(key, false);
			collapsedGroups.put(key, nowCollapsed);
			body.setVisible(!nowCollapsed);
			body.setManaged(!nowCollapsed);
			chevron.setRotate(nowCollapsed ? -90 : 0);
		});
		VBox group = new VBox(0, head, body);
		group.getStyleClass().add("editor-section");
		return group;
	}

	/** The heading's title text (its first labeled child), for the collapse key. */
	private static String headingText(HBox heading) {
		for (Node child : heading.getChildren()) {
			if (child instanceof javafx.scene.control.Labeled labeled
					&& labeled.getText() != null) {
				return labeled.getText();
			}
		}
		return "";
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
			case CAMERA_LAYOUT -> "Webcam · block";
			case ELEMENT -> "Element";
			case GRADE -> "Color grade";
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
	if (selectionEditor == SelectionEditor.CAMERA_LAYOUT) {
		// The Camera track re-scopes the ordinary Webcam panel rather than opening a second one.
		return webcamSection();
	}
	if (selectionEditor == SelectionEditor.ELEMENT) {
		if (elementPanel == null) {
			elementPanel = new ElementSettingsPanel(store);
		} else {
			elementPanel.refresh();
		}
		return elementPanel;
	}
	if (selectionEditor == SelectionEditor.GRADE) {
		return gradeSection();
	}
	if (selectionEditor == SelectionEditor.MULTI) {
		return multiSelectionSection();
	}
		return switch (section) {
			case SCENE -> sceneSection();
			case COLOR -> gradeSection();
			case CURSOR -> cursorSection();
			case KEYSTROKES -> keystrokesSection();
			case WEBCAM -> webcamSection();
			case CAPTIONS -> captionsSection();
			case AUDIO -> audioSection();
			case AUTO_MASK -> autoMaskSection();
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

		Switch mute = Switch.xs("Mute clip");
		mute.setChecked(Boolean.TRUE.equals(clip.muted()));
		mute.onCheckedChange(value -> store.apply(new ClipCommands.SetMuted(value)));
		List<Node> transition = transitionControls(clip);
		transition.add(deleteSelectionButton("Delete Clip",
				() -> store.apply(new ClipCommands.Delete(clip.id()))));
		root.getChildren().add(group("Clip", null, speed, mute));
		root.getChildren().add(group("Transition", null, transition.toArray(Node[]::new)));
		return root;
	}

	/**
	 * The outgoing transition of one clip: None / Fade through black, plus the fade duration once a
	 * fade is set (the camera block's "Switch in" pattern). Both controls write through
	 * {@code ClipCommands.SetTransition}; removal is the null transition.
	 */
	private List<Node> transitionControls(ClipRegion clip) {
		ClipTransition transition = clip.transitionOut();
		boolean fade = transition != null && transition.kind() == ClipTransitionKind.FADE_BLACK;
		ChoiceGroup kind = choice("none", "None", "fade-black", "Fade");
		kind.setValue(fade ? "fade-black" : "none");
		kind.onValueChange(value -> store.apply(new ClipCommands.SetTransition(
				"fade-black".equals(value) ? new ClipTransition(ClipTransitionKind.FADE_BLACK,
						transition != null ? transition.durationMs()
								: ClipTransition.DEFAULT_CLIP_TRANSITION_MS)
						: null)));
		List<Node> controls = new ArrayList<>();
		controls.add(row("Fade out", kind));
		if (fade && transition != null) {
			Slider duration = new Slider("Duration");
			duration.range(ClipTransition.MIN_CLIP_TRANSITION_MS,
					ClipTransition.MAX_CLIP_TRANSITION_MS).step(100)
					.formatter(value -> Math.round(value) + " ms").sliderControl();
			duration.setValue(transition.durationMs());
			duration.setMaxWidth(Double.MAX_VALUE);
			duration.onValueCommit(value -> store.apply(new ClipCommands.SetTransition(
					new ClipTransition(ClipTransitionKind.FADE_BLACK, Math.round(value)))));
			controls.add(duration);
		}
		return controls;
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

		Switch normalize = Switch.xs("Normalize");
		normalize.setChecked(Boolean.TRUE.equals(audio.normalize()));
		normalize.onCheckedChange(value -> applyMulti(SelectionCommands.TimelineSelections.AUDIO,
				id -> new AudioCommands.SetNormalize(id, value),
				new AudioCommands.SetNormalize(value)));
		root.getChildren().add(group("Audio", null, volume, normalize,
				deleteSelectionButton(multiIds.size() > 1
						? "Delete " + multiIds.size() + " Audio Regions" : "Delete Audio",
						() -> applyMulti(SelectionCommands.TimelineSelections.AUDIO,
								AudioCommands.Delete::new,
								new AudioCommands.Delete(audio.id())))));
		return root;
	}

	/** The multi-selection section for a mixed selection: count + batched delete. */
	private Node multiSelectionSection() {
		VBox root = new VBox(14);
		Label count = new Label(store.multiSelection().size() + " elements selected.");
		count.setWrapText(true);
		count.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
		root.getChildren().add(group("Selection", null, count,
				deleteSelectionButton("Delete " + store.multiSelection().size() + " Elements",
						this::deleteMultiSelection)));
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
		root.getChildren().addAll(group("Caption", null, text,
				deleteSelectionButton("Delete Caption",
						() -> store.apply(new CaptionCommands.Delete(cue.id())))));
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
			Switch toggle = Switch.xs(label);
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
		root.getChildren().add(group("Keystroke", null, key, modifiers,
				deleteSelectionButton("Delete Keystroke",
						() -> store.apply(new KeystrokeCommands.Delete(element.id())))));
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

	/**
	 * The Color & luminosity editor: the sliders of the grade being edited — the timeline
	 * selection when there is one, otherwise the grade under the playhead. With neither, the
	 * section explains the Color track and offers the add-at-playhead gesture.
	 */
	private Node gradeSection() {
		GradeRegion selected = selectedGrade();
		GradeRegion grade = selected != null ? selected : gradeAtPlayhead();
		VBox root = new VBox(14);
		if (grade == null) {
			root.getChildren().add(emptySelection(
					"Grade the footage over a span: click the COLOR lane in the timeline, press G, "
							+ "or use Add Layer → Color. The grade then shades every clip it covers."));
			Button add = new Button("Add grade at playhead", Button.Variant.SECONDARY,
					Button.Size.SM);
			add.setMaxWidth(Double.MAX_VALUE);
			add.setAlignment(Pos.CENTER_LEFT);
			add.setOnAction(event -> actions.addColorGrade());
			root.getChildren().add(add);
			return root;
		}
		Label span = new Label(PlaybackControls.formatDuration(grade.startMs() / 1000.0) + " – "
				+ PlaybackControls.formatDuration(grade.endMs() / 1000.0));
		span.setStyle("-fx-font-family: \"DejaVu Sans Mono\"; -fx-font-size: 12px; -fx-text-fill: -g-muted;");
		root.getChildren().add(span);
		// Which picture this grade shades: screen and camera usually need their own calibration,
		// so two grades can cover one span with a target each.
		ChoiceGroup targets = choice("main", "Screen", "camera", "Camera", "both", "Both");
		targets.setValue(grade.targets().wire());
		targets.setAccessibleText("Applies to");
		targets.onValueChange(value -> store.apply(new GradeCommands.ChangeTarget(grade.id(),
				GradeTarget.fromWire(value))));
		root.getChildren().add(row("Applies to", targets));
		root.getChildren().add(heading("Luminosity", () -> resetGrade(grade.id())));
		root.getChildren().add(slider("Brightness", grade.brightness(), GradeRegion.MIN_ADJUST,
				GradeRegion.MAX_ADJUST, 1, EditorInspectorPanel::formatSigned,
				value -> applyGrade(grade.id(), value, grade.contrast(), grade.saturation(),
						grade.temperature(), grade.gamma())));
		root.getChildren().add(slider("Contrast", grade.contrast(), GradeRegion.MIN_ADJUST,
				GradeRegion.MAX_ADJUST, 1, EditorInspectorPanel::formatSigned,
				value -> applyGrade(grade.id(), grade.brightness(), value, grade.saturation(),
						grade.temperature(), grade.gamma())));
		root.getChildren().add(slider("Gamma", grade.gamma(), GradeRegion.MIN_GAMMA,
				GradeRegion.MAX_GAMMA, 0.05,
				value -> String.format(java.util.Locale.ROOT, "%.2f", value),
				value -> applyGrade(grade.id(), grade.brightness(), grade.contrast(),
						grade.saturation(), grade.temperature(), value)));
		root.getChildren().add(heading("Color", () -> resetGrade(grade.id())));
		root.getChildren().add(slider("Saturation", grade.saturation(), GradeRegion.MIN_ADJUST,
				GradeRegion.MAX_ADJUST, 1, EditorInspectorPanel::formatSigned,
				value -> applyGrade(grade.id(), grade.brightness(), grade.contrast(), value,
						grade.temperature(), grade.gamma())));
		root.getChildren().add(slider("Temperature", grade.temperature(), GradeRegion.MIN_ADJUST,
				GradeRegion.MAX_ADJUST, 1, EditorInspectorPanel::formatSigned,
				value -> applyGrade(grade.id(), grade.brightness(), grade.contrast(),
						grade.saturation(), value, grade.gamma())));
		root.getChildren().add(deleteSelectionButton("Delete Grade",
				() -> store.apply(new GradeCommands.Delete(grade.id()))));
		return root;
	}

	private void applyGrade(String id, double brightness, double contrast, double saturation,
			double temperature, double gamma) {
		store.apply(new GradeCommands.ChangeParams(id, brightness, contrast, saturation,
				temperature, gamma));
	}

	private void resetGrade(String id) {
		applyGrade(id, 0, 0, 0, 0, GradeRegion.DEFAULT_GAMMA);
	}

	private @Nullable GradeRegion selectedGrade() {
		String id = store.selectedGradeId();
		if (id == null) {
			return null;
		}
		for (GradeRegion region : store.state().gradeRegions()) {
			if (region.id().equals(id)) {
				return region;
			}
		}
		return null;
	}

	/** The grade covering the playhead, latest start winning (the renderer's overlap rule). */
	private @Nullable GradeRegion gradeAtPlayhead() {
		double timeMs = playback.state().currentTimeSeconds() * 1000;
		GradeRegion active = null;
		for (GradeRegion region : store.state().gradeRegions()) {
			if (timeMs >= region.startMs() && timeMs < region.endMs()
					&& (active == null || region.startMs() >= active.startMs())) {
				active = region;
			}
		}
		return active;
	}

	/** Signed slider output: {@code 0}, {@code +12}, {@code -8}. */
	private static String formatSigned(double value) {
		if (value == 0) {
			return "0";
		}
		return String.format(java.util.Locale.ROOT, "%+.0f", value);
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
			json.put("wallpaper", "/wallpapers/morning-tide.jpg");
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
			Switch link = Switch.xs();
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

		Switch show = Switch.xs("Show cursor");
		show.setChecked(state.showCursor());
		show.onCheckedChange(value -> apply(json -> json.put("showCursor", value)));
		Switch loop = Switch.xs("Loop cursor");
		loop.setChecked(state.loopCursor());
		loop.onCheckedChange(value -> apply(json -> json.put("loopCursor", value)));

		Select style = new Select();
		for (String cursorStyle : List.of("tahoe", "tahoe-inverted", "macos", "windows11", "dot",
				"figma")) {
			style.item(cursorStyle, labelFor(cursorStyle));
		}
		style.value(state.cursorStyle());
		style.onValueChange(value -> apply(json -> json.put("cursorStyle", value)));

		ChoiceGroup motion = choice("focused", "Focused", "smooth", "Smooth");
		String activePreset = matchedMotionPreset(state);
		if (activePreset != null) {
			motion.setValue(activePreset);
		}
		motion.onValueChange(value -> applyMotionPreset(value));
		Slider sway = slider("Sway", state.cursorSway(), 0, 2, 0.01,
				value -> String.format(java.util.Locale.ROOT, "%.2f", value),
				value -> apply(json -> json.put("cursorSway", value)));

		ChoiceGroup effect = choice("none", "Off", "spotlight", "Spotlight", "ripple", "Ripple",
				"echo", "Echo");
		effect.setValue(state.cursorClickEffect().wire());
		effect.onValueChange(value -> apply(json -> json.put("cursorClickEffect", value)));

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

		// Cursor, Motion and Click effect: the panel's three groups, each with the chevron and
		// hover Reset affordance; the click sound block below carries its own group.
		root.getChildren().add(group("Cursor", this::resetCursor, show, loop, row("Style", style)));
		root.getChildren().add(group("Motion", this::resetMotion, row("Motion", motion), sway));
		root.getChildren().add(group("Click effect", this::resetClickEffect, row("Click effect",
				effect), colors,
				slider("Effect scale", state.cursorClickEffectScale(), 0.5, 2,
						0.01, value -> String.format(java.util.Locale.ROOT, "%.2f", value),
						value -> apply(json -> json.put("cursorClickEffectScale", value))),
				slider("Effect opacity", state.cursorClickEffectOpacity(), 0, 1,
						0.01, value -> Math.round(value * 100) + "%",
						value -> apply(json -> json.put("cursorClickEffectOpacity", value))),
				slider("Effect duration", state.cursorClickEffectDurationMs(), 120,
						1200, 10, value -> Math.round(value) + "ms",
						value -> apply(json -> json.put("cursorClickEffectDurationMs", value)))));
		root.getChildren().add(clickSoundBlock(state.clickSound()));
		return root;
	}

	/** Restores the cursor switches and style; motion fields keep their own group. */
	private void resetCursor() {
		apply(json -> {
			json.remove("showCursor");
			json.remove("loopCursor");
			json.remove("cursorStyle");
		});
	}

	/** Restores the motion preset fields (size, smoothing, springs, bounce) and the sway. */
	private void resetMotion() {
		apply(json -> {
			json.remove("cursorSway");
			json.remove("zoomInDurationMs");
			json.remove("zoomOutDurationMs");
			json.remove("cursorSize");
			json.remove("cursorSmoothing");
			json.remove("cursorSpringStiffnessMultiplier");
			json.remove("cursorSpringDampingMultiplier");
			json.remove("cursorSpringMassMultiplier");
			json.remove("cursorClickBounce");
			json.remove("cursorClickBounceDuration");
		});
	}

	/** Restores the click effect and its color, scale, opacity and duration. */
	private void resetClickEffect() {
		apply(json -> {
			json.remove("cursorClickEffect");
			json.remove("cursorClickEffectColor");
			json.remove("cursorClickEffectScale");
			json.remove("cursorClickEffectOpacity");
			json.remove("cursorClickEffectDurationMs");
		});
	}

	private void resetClickSound() {
		apply(json -> json.remove("clickSound"));
	}

	/** The Keystrokes tab: the on-screen key press overlay and the typing sounds. */
	private VBox keystrokesSection() {
		var state = store.state();
		VBox root = new VBox(14);
		root.getChildren().add(keystrokesBlock(state.keystrokeOverlay()));
		root.getChildren().add(keySoundBlock(state.keySound()));
		return root;
	}

	/**
	 * "Typing sound" block: a switch, the sound choice (auditioned on change) and volume. Only
	 * character keys sound, so Ctrl/Shift/arrows stay silent and the track is not noisy.
	 */
	private VBox keySoundBlock(dev.gravaai.core.project.KeySoundSettings settings) {
		var sound = settings == null
				? dev.gravaai.core.project.KeySoundSettings.DEFAULT : settings;
		VBox block = new VBox(12);
		block.getStyleClass().add("editor-keystrokes");
		Switch enabled = Switch.xs("Typing sounds");
		enabled.setChecked(sound.enabled());
		enabled.onCheckedChange(value -> applyKeySound(json -> json.put("enabled", value)));
		block.getChildren().addAll(heading("Typing sound", this::resetKeySound), enabled);

		if (KeystrokeEvents.count(recordingPath.get()) == 0) {
			enabled.setDisable(true);
			Label empty = new Label("No key presses were recorded, so there is nothing to sound.");
			empty.setWrapText(true);
			empty.getStyleClass().add("editor-keystrokes__empty");
			empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			block.getChildren().add(empty);
			return block;
		}

		ChoiceGroup style = choice("clack", "Clack", "tap", "Tap", "soft", "Soft");
		style.setValue(sound.sound().wire());
		style.onValueChange(value -> {
			var picked = dev.gravaai.core.project.KeySoundStyle.fromWire(value);
			applyKeySound(json -> json.put("sound", picked.wire()));
			dev.gravaai.media.audio.ClickSoundAudition.play(picked, sound.volume());
		});
		style.setAccessibleText("Typing sound");
		block.getChildren().add(style);
		block.getChildren().add(slider("Volume", sound.volume(), 0, 1, 0.01,
				value -> Math.round(value * 100) + "%",
				value -> applyKeySound(json -> json.put("volume", value))));
		return block;
	}

	private void applyKeySound(java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> mutation) {
		apply(json -> {
			tools.jackson.databind.JsonNode existing = json.get("keySound");
			mutation.accept(existing instanceof tools.jackson.databind.node.ObjectNode object
					? object : json.putObject("keySound"));
		});
	}

	/**
	 * "Click sound" block of the cursor section: a switch, the sound choice (auditioned on
	 * change) and volume. Global on purpose — the sound belongs to the click, like the visual
	 * effect, so one setting covers every recorded click and the timeline stays clean.
	 */
	private VBox clickSoundBlock(dev.gravaai.core.project.ClickSoundSettings settings) {
		var sound = settings == null
				? dev.gravaai.core.project.ClickSoundSettings.DEFAULT : settings;
		VBox block = new VBox(12);
		block.getStyleClass().add("editor-keystrokes");
		Switch enabled = Switch.xs("Click sounds");
		enabled.setChecked(sound.enabled());
		enabled.onCheckedChange(value -> applyClickSound(json -> json.put("enabled", value)));
		block.getChildren().addAll(heading("Click sound", this::resetClickSound), enabled);

		if (ClickEvents.count(recordingPath.get()) == 0) {
			enabled.setDisable(true);
			Label empty = new Label("No clicks were recorded in this recording's cursor"
					+ " telemetry, so there is nothing to sound.");
			empty.setWrapText(true);
			empty.getStyleClass().add("editor-keystrokes__empty");
			empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			block.getChildren().add(empty);
			return block;
		}

		ChoiceGroup style = choice("crisp", "Crisp", "tick", "Tick", "soft", "Soft");
		style.setValue(sound.sound().wire());
		style.onValueChange(value -> {
			var picked = dev.gravaai.core.project.ClickSoundStyle.fromWire(value);
			applyClickSound(json -> json.put("sound", picked.wire()));
			dev.gravaai.media.audio.ClickSoundAudition.play(picked, sound.volume());
		});
		style.setAccessibleText("Click sound");
		block.getChildren().add(style);
		block.getChildren().add(slider("Volume", sound.volume(), 0, 1, 0.01,
				value -> Math.round(value * 100) + "%",
				value -> applyClickSound(json -> json.put("volume", value))));
		return block;
	}

	private void applyClickSound(java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> mutation) {
		apply(json -> {
			tools.jackson.databind.JsonNode existing = json.get("clickSound");
			mutation.accept(existing instanceof tools.jackson.databind.node.ObjectNode object
					? object : json.putObject("clickSound"));
		});
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
		Switch show = Switch.xs("Show key presses");
		show.setChecked(overlay.enabled());
		show.onCheckedChange(value -> applyKeystrokes(json -> json.put("enabled", value)));
		block.getChildren().addAll(heading("Key presses", this::resetKeystrokes), show);

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

	private void resetKeystrokes() {
		apply(json -> json.remove("keystrokeOverlay"));
	}

	private void resetKeySound() {
		apply(json -> json.remove("keySound"));
	}

	/** The recording whose {@code .keys.json} sidecar holds the key presses (preview media). */
	public void setRecordingPath(java.util.function.Supplier<java.nio.file.Path> path) {
		this.recordingPath = path == null ? () -> null : path;
		if (section == EditorSidebarRail.Section.CURSOR
				|| section == EditorSidebarRail.Section.KEYSTROKES) {
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
		// The panel is one set of camera options whose scope follows the Camera track: a selected
		// block edits that block's overrides, no selection (or empty-lane space) edits the project.
		CameraLayoutSegment scope = cameraLayoutScope();
		VBox root = new VBox(14);
		if (scope != null) {
			root.getChildren().add(cameraScopeBanner(scope));
		}
		// One Camera group carries the switch, background, layout and geometry affordances;
		// Crop and the background-effect mask stay separate groups under Advanced.
		VBox camera = group(scope == null ? "Camera" : "This block",
				scope == null ? this::resetWebcam : () -> resetCameraBlock(scope));

		// The camera device itself is one per recording: enabled/mirror/react and the background
		// effect stay project-wide, so a block never edits them behind the user's back.
		if (scope == null) {
			Switch enabled = Switch.xs("Enabled");
			enabled.setChecked(webcam.enabled());
			enabled.onCheckedChange(value -> applyWebcam(json -> json.put("enabled", value)));
			Switch mirror = Switch.xs("Mirror");
			mirror.setChecked(webcam.mirror());
			mirror.onCheckedChange(value -> {
				applyWebcam(json -> json.put("mirror", value));
				// The crop control edits a flipped rectangle when mirrored.
				rebuild();
			});
			Switch react = Switch.xs("React to zoom");
			react.setChecked(webcam.reactToZoom());
			react.onCheckedChange(value -> applyWebcam(json -> json.put("reactToZoom", value)));
			// Smart framing dodges the cursor and zoom focus; a split camera never floats over
			// the content, so there is nothing for it to do there.
			Switch smartFraming = Switch.xs("Smart framing");
			smartFraming.setChecked(webcam.smartFraming());
			smartFraming.onCheckedChange(value -> {
				applyWebcam(json -> json.put("smartFraming", value));
				rebuild();
			});
			boolean projectSplit = webcam.positionPreset().isSplit();
			// React to zoom counter-scales the floating bubble; a split camera has a fixed strip.
			camera.getChildren().addAll(projectSplit ? List.of(enabled, mirror)
					: List.of(enabled, mirror, react, smartFraming));

			// What the card does with the room: nothing, blur it, or let the wallpaper show through.
			// One selector rather than two switches, so blur and remove cannot both be on.
			ChoiceGroup backgroundEffect = choice(WebcamBackgroundEffect.NONE.wire(), "None",
					WebcamBackgroundEffect.BLUR.wire(), "Blur",
					WebcamBackgroundEffect.REMOVE.wire(), "Remove");
			backgroundEffect.setValue(webcam.backgroundEffect().wire());
			backgroundEffect.setAccessibleText("Webcam background");
			backgroundEffect.onValueChange(value -> {
				WebcamBackgroundEffect effect = WebcamBackgroundEffect.fromWireOrNull(value);
				if (effect == null || effect == webcam.backgroundEffect()) {
					return;
				}
				applyWebcam(json -> json.put("backgroundEffect", effect.wire()));
			});
			Label effectCaption = new Label("Background");
			effectCaption.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			camera.getChildren().add(new VBox(8, effectCaption, backgroundEffect));
		} else {
			// A block can also take the camera full frame or hide it; the Webcam panel is the only
			// editor for blocks, so the full layout list lives here.
			Select blockLayout = new Select();
			for (CameraLayout choice : CameraLayout.all()) {
				blockLayout.item(choice.wire(), choice.label());
			}
			blockLayout.value(scope.layout().wire());
			blockLayout.onValueChange(value -> CameraLayout.fromWire(value).ifPresent(choice -> {
				store.apply(new CameraLayoutCommands.ChangeLayout(scope.id(), choice));
				rebuild();
			}));
			camera.getChildren().add(row("Layout", blockLayout));
		}

		WebcamPositionPreset preset = framingPreset(scope, webcam);
		boolean split = preset.isSplit();
		// Framing only applies to a positioned camera; camera-only and no-camera have no box.
		if (scope != null && !(scope.layout() instanceof CameraLayout.Position)) {
			root.getChildren().addAll(camera, cameraSwitchGroup(scope),
					deleteSelectionButton("Remove block (use project layout)",
							() -> store.apply(new CameraLayoutCommands.Delete(scope.id()))));
			return root;
		}

		// Layout first: the floating bubble or split screen, each with its own position control.
		// A block picks from the full list above instead (it can also be camera-only or hidden).
		if (scope == null) {
			ChoiceGroup layout = choice("overlay", "Overlay", "split", "Split screen");
			layout.setValue(split ? "split" : "overlay");
			layout.setAccessibleText("Webcam layout");
			layout.onValueChange(value -> {
				if (value.equals("split") == split) {
					return;
				}
				applyPreset(null, value.equals("split")
						? defaultSplitPreset(store.state().aspectRatio())
						: webcam.corner().wire());
				rebuild();
			});
			camera.getChildren().add(layout);
		}

		if (split) {
			ChoiceGroup side = choice("split-right", "Right", "split-left", "Left",
					"split-top", "Top", "split-bottom", "Bottom");
			side.setValue(preset.wire());
			side.setAccessibleText("Camera side");
			side.onValueChange(value -> {
				if (!value.equals(preset.wire())) {
					applyPreset(scope, value);
					rebuild();
				}
			});
			// Four chips do not fit beside a caption in the inspector width: caption above.
			Label sideCaption = new Label("Camera side");
			sideCaption.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			camera.getChildren().add(new VBox(8, sideCaption, side));
			// The camera share of the split axis: the width percent beside the content, the
			// height percent above/below it (the compositor reads the same fields).
			boolean stacked = preset == WebcamPositionPreset.SPLIT_TOP
					|| preset == WebcamPositionPreset.SPLIT_BOTTOM;
			String shareField = stacked ? "height" : "width";
			camera.getChildren().add(framingSlider(scope, "Camera share", shareField,
					stacked ? webcam.height() : webcam.width(), 10, 90,
					value -> formatPercent(value)));
		} else {
			Select position = new Select();
			for (WebcamPositionPreset candidate : WebcamPositionPreset.values()) {
				if (!candidate.isSplit()) {
					position.item(candidate.wire(), labelFor(candidate.wire()));
				}
			}
			position.value(preset.wire());
			position.onValueChange(value -> applyPreset(scope, value));
			camera.getChildren().add(row("Position", position));

			camera.getChildren().add(framingSlider(scope, "Size", "size", webcam.size(),
					10, 100, value -> formatPercent(value)));
			camera.getChildren().add(framingSlider(scope, "Width", "width", webcam.width(),
					10, 100, value -> formatPercent(value)));
			camera.getChildren().add(framingSlider(scope, "Height", "height", webcam.height(),
					10, 100, value -> formatPercent(value)));
		}
		camera.getChildren().add(framingSlider(scope, "Roundness", "roundness", webcam.roundness(),
				0, 100, value -> formatPercent(value)));
		camera.getChildren().add(framingSlider(scope, "Shadow", "shadow", webcam.shadow(),
				0, 1, value -> Math.round(value * 100) + "%"));
		camera.getChildren().add(framingSlider(scope, split ? "Gap" : "Margin", "margin",
				webcam.margin(), 0, 96, value -> Math.round(value) + "px"));
		root.getChildren().add(camera);

		if (scope != null) {
			// How the camera arrives at and leaves this block: the switch diamonds on the track.
			root.getChildren().add(cameraSwitchGroup(scope));
			root.getChildren().add(deleteSelectionButton("Remove block (use project layout)",
					() -> store.apply(new CameraLayoutCommands.Delete(scope.id()))));
			return root;
		}

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

			// Background effect mask: how crisp the cut-out edge is and how far it sits from the
			// detected subject, plus the switch that keeps the precise model on during playback.
			// All three are no-ops unless the effect is on, so they stay under Advanced beside the
			// crop control rather than cluttering the main section.
			root.getChildren().add(heading("Background effect", () -> applyWebcam(json -> {
				json.put("backgroundEffect", WebcamOverlaySettings.DEFAULT_WEBCAM_BACKGROUND_EFFECT
						.wire());
				json.put("highQualityMaskWhilePlaying",
						WebcamOverlaySettings.DEFAULT_WEBCAM_HIGH_QUALITY_MASK_WHILE_PLAYING);
				json.put("maskPrecision", WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_PRECISION);
				json.put("maskOffset", WebcamOverlaySettings.DEFAULT_WEBCAM_MASK_OFFSET);
			})));
			// Positive wording: checked reads as "better, slower". Off by default, so playback runs
			// the ~2 ms model and only a stopped frame or an export pays the ~28 ms one.
			Switch highQuality = Switch.xs("High-quality mask while playing");
			highQuality.setChecked(webcam.highQualityMaskWhilePlaying());
			highQuality.onCheckedChange(value -> applyWebcam(
					json -> json.put("highQualityMaskWhilePlaying", value)));
			root.getChildren().add(highQuality);
			root.getChildren().add(slider("Edge precision", webcam.maskPrecision(), 0, 1, 0.05,
					value -> Math.round(value * 100) + "%",
					value -> applyWebcam(json -> json.put("maskPrecision", value))));
			root.getChildren().add(slider("Edge offset", webcam.maskOffset(), -5, 5, 0.5,
					value -> (value > 0 ? "+" : "") + Math.round(value) + "%",
					value -> applyWebcam(json -> json.put("maskOffset", value))));
		}
		return root;
	}

	/**
	 * The Camera track block the Webcam panel edits, or {@code null} for the project scope. The
	 * panel follows the timeline selection, so selecting a block re-scopes it and clicking the
	 * empty lane (or the track gutter) returns it to the project settings.
	 */
	private @Nullable CameraLayoutSegment cameraLayoutScope() {
		String id = store.selectedCameraLayoutId();
		if (id == null) {
			return null;
		}
		return store.state().cameraLayoutSegments().stream()
				.filter(segment -> segment.id().equals(id)).findFirst().orElse(null);
	}

	/** A one-line banner naming the block the panel is scoped to, with its time range. */
	private Node cameraScopeBanner(CameraLayoutSegment segment) {
		Label banner = new Label("Editing this block · "
				+ PlaybackControls.formatDuration(segment.startMs() / 1000.0) + " – "
				+ PlaybackControls.formatDuration(segment.endMs() / 1000.0)
				+ ". Other times use the project layout.");
		banner.setWrapText(true);
		banner.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		return banner;
	}

	/** The preset the framing controls start from: the block's layout, else the project's. */
	private static WebcamPositionPreset framingPreset(@Nullable CameraLayoutSegment scope,
			WebcamOverlaySettings webcam) {
		if (scope != null && scope.layout() instanceof CameraLayout.Position(var preset)) {
			return preset;
		}
		return webcam.positionPreset();
	}

	/** Writes a position preset to the current scope: the block's layout, or the project's webcam. */
	private void applyPreset(@Nullable CameraLayoutSegment scope, @Nullable String wire) {
		WebcamPositionPreset preset = wire == null ? null : WebcamPositionPreset.fromWireOrNull(wire);
		if (preset == null) {
			return;
		}
		if (scope != null) {
			store.apply(new CameraLayoutCommands.ChangeLayout(scope.id(),
					new CameraLayout.Position(preset)));
			return;
		}
		applyWebcam(json -> {
			json.put("positionPreset", preset.wire());
			if (preset.isCorner()) {
				json.put("corner", preset.wire());
			}
		});
	}

	/**
	 * One framing slider, routed to the current scope. In the block scope a slider shows (and
	 * writes) the block's override, falling back to the project value until the user moves it; the
	 * Reset action clears the overrides instead of copying values.
	 */
	private Slider framingSlider(@Nullable CameraLayoutSegment scope, String label, String field,
			double projectValue, double min, double max, DoubleFunction<String> formatter) {
		if (scope == null) {
			return slider(label, projectValue, min, max, sliderStep(field), formatter,
					value -> applyWebcam(json -> json.put(field, value)));
		}
		double value = scope.style().get(field).orElse(projectValue);
		return slider(label, value, min, max, sliderStep(field), formatter,
				next -> store.apply(new CameraLayoutCommands.SetStyle(scope.id(), field,
						java.util.OptionalDouble.of(next))));
	}

	/** Shadow is a 0..1 fraction; the other framing fields are whole percents or pixels. */
	private static double sliderStep(String field) {
		return "shadow".equals(field) ? 0.01 : 1;
	}

	/** Clears every style override of one block, so it inherits the project look again. */
	private void resetCameraBlock(CameraLayoutSegment segment) {
		store.apply(new CameraLayoutCommands.ClearStyle(segment.id()));
		rebuild();
	}

	/** How the camera enters and leaves the block: the two switch diamonds, as chips. */
	private Node cameraSwitchGroup(CameraLayoutSegment segment) {
		ChoiceGroup transition = choice("cut", "Cut", "smooth", "Smooth");
		transition.setValue(segment.transition().wire());
		transition.setAccessibleText("Switch");
		transition.onValueChange(value -> store.apply(new CameraLayoutCommands.SetTransition(
				segment.id(), CameraLayoutSegment.Transition.fromWire(value))));

		List<Node> rows = new ArrayList<>();
		rows.add(row("Switch in", transition));
		// The switch out only matters where the project layout follows: a touching block's
		// switch-in governs that boundary instead.
		if (segment.followedByGap(store.state().cameraLayoutSegments())) {
			ChoiceGroup switchOut = choice("cut", "Cut", "smooth", "Smooth");
			switchOut.setValue(segment.transitionOut().wire());
			switchOut.setAccessibleText("Switch out");
			switchOut.onValueChange(value -> store.apply(new CameraLayoutCommands.SetTransitionOut(
					segment.id(), CameraLayoutSegment.Transition.fromWire(value))));
			rows.add(row("Switch out", switchOut));
		}
		VBox box = new VBox(12);
		box.getChildren().addAll(rows);
		return box;
	}

	/**
	 * Restores every camera setting to its default while keeping the chosen device: the
	 * normaliser refills missing webcam fields, so removing them (sourcePath excepted) resets.
	 */
	private void resetWebcam() {
		apply(json -> {
			JsonNode node = json.get("webcam");
			if (node instanceof ObjectNode webcamNode) {
				List<String> fields = new ArrayList<>(webcamNode.properties().stream()
						.map(Map.Entry::getKey).toList());
				for (String field : fields) {
					if (!field.equals("sourcePath")) {
						webcamNode.remove(field);
					}
				}
			}
		});
	}

	// ---------------------------------------------------------------------
	// Captions
	// ---------------------------------------------------------------------

	/** {@code CAPTION_LANGUAGE_OPTIONS}. */
	private static final String[][] CAPTION_LANGUAGES = {
			{"auto", "Auto Detect"}, {"en", "English"}, {"es", "Spanish"}, {"fr", "French"},
			{"de", "German"}, {"it", "Italian"}, {"pt", "Portuguese"},
			{"zh", "Chinese (Simplified)"}, {"ja", "Japanese"}, {"ko", "Korean"}};

	/**
	 * Auto masking: the two detector toggles, Detect/Cancel, the model download row and a result
	 * summary. The heavy lifting lives in the app's AutoMaskController; this section only presents
	 * it, the same split the Captions section uses for its controller.
	 */
	private VBox autoMaskSection() {
		VBox root = new VBox(16);
		AutoMaskUiService service = autoMask;
		AutoMaskUiService.State state = service == null
				? new AutoMaskUiService.State(false) : service.state();
		AutoMaskUiService.Progress progress = service == null
				? new AutoMaskUiService.Progress(false, 0, "", false) : service.progress();
		boolean running = service != null && service.detecting();
		boolean installed = state.modelInstalled();

		VBox detectors = group("Detectors", this::resetAutoMaskDetectors);
		Switch faces = Switch.xs();
		faces.setChecked(autoMaskFaces);
		faces.onCheckedChange(value -> autoMaskFaces = value);
		detectors.getChildren().add(row("Faces", faces));
		Switch text = Switch.xs();
		text.setChecked(autoMaskText);
		text.onCheckedChange(value -> autoMaskText = value);
		detectors.getChildren().add(row("Screen text (PII)", text));
		detectors.getChildren().add(new Label(
				"Finds faces and personal data in the recording, then blurs them as timeline"
						+ " regions. Everything runs on this machine."));

		VBox model = group("Detection model", null);
		if (installed) {
			Label ready = new Label("Installed.");
			ready.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			model.getChildren().add(ready);
		} else {
			Button download = new Button("Download model (~580 MB)",
					Button.Variant.OUTLINE, Button.Size.MD);
			download.setMaxWidth(Double.MAX_VALUE);
			download.setDisable(running);
			download.setOnAction(event -> {
				if (service != null) {
					service.downloadModel();
				}
			});
			model.getChildren().add(download);
		}
		root.getChildren().add(model);

		VBox run = group("Masking", null);
		String action = running ? "Cancel" : "Detect Masks";
		Button detect = new Button(action, running ? Button.Variant.OUTLINE
				: Button.Variant.DEFAULT, Button.Size.MD);
		detect.setMaxWidth(Double.MAX_VALUE);
		// Text detection needs the downloaded model; faces alone are bundled. `installed` already
		// came from service.state(), so re-reading it here would be the same value twice.
		detect.setDisable(!running && !installed && autoMaskText);
		detect.setOnAction(event -> {
			if (service == null) {
				return;
			}
			if (running) {
				service.cancel();
			} else {
				service.detect(faces.isChecked(), text.isChecked());
			}
		});
		run.getChildren().add(detect);
		if (running) {
			javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(
					progress.percent() / 100.0);
			bar.setMaxWidth(Double.MAX_VALUE);
			Label status = new Label(progress.message());
			status.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			run.getChildren().add(new VBox(4, status, bar));
		} else if (progress.failed()) {
			Label failure = new Label(progress.message());
			failure.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-danger;");
			run.getChildren().add(failure);
		} else if (service != null && service.lastRegionCount() > 0) {
			Label summary = new Label(service.lastRegionCount()
					+ (service.lastRegionCount() == 1 ? " region on the timeline."
							: " regions on the timeline."));
			summary.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			run.getChildren().add(summary);
			Button remove = new Button("Remove auto masks", Button.Variant.GHOST,
					Button.Size.SM);
			remove.setOnAction(event -> service.removeAutoRegions());
			run.getChildren().add(remove);
		}
		root.getChildren().add(run);
		return root;
	}

	/** Reset for the DETECTORS group: both detectors on, the ship default. */
	private void resetAutoMaskDetectors() {
		autoMaskFaces = true;
		autoMaskText = true;
	}

	// ---------------------------------------------------------------------
	// Audio cleanup
	// ---------------------------------------------------------------------

	/** The high-pass frequencies the inspector offers (0 is off, the rest are real options). */
	private static final List<Integer> HIGH_PASS_OPTIONS = List.of(0, 80, 120, 180);

	/** The loudness targets the inspector offers; 0 is off, the rest come from the core model. */
	private static final List<Double> LOUDNESS_OPTIONS = List.of(0.0,
			AudioCleanup.LOUDNESS_PODCAST_LUFS, AudioCleanup.LOUDNESS_STREAMING_LUFS,
			AudioCleanup.LOUDNESS_BROADCAST_LUFS);

	/** The source tracks the Audio section edits (the shell points this at the timeline). */
	public void setSourceAudioTracks(
			java.util.function.Supplier<List<TimelineSourceAudioTrack>> tracks) {
		this.sourceAudioTracks = tracks == null ? List::of : tracks;
		rebuild();
	}

	/**
	 * Edit-time cleanup of the recording's source audio: one group per lane with the three
	 * non-destructive stages. The loudness target is a property of the deliverable, so its row says
	 * that the exported mix is normalized rather than the lane alone.
	 */
	private VBox audioSection() {
		VBox root = new VBox(14);
		List<TimelineSourceAudioTrack> tracks = sourceAudioTracks.get();
		if (tracks == null || tracks.isEmpty()) {
			Label hint = new Label("Open a recording with audio to clean up its source tracks.");
			hint.setWrapText(true);
			hint.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
			root.getChildren().add(group("Source audio", null, hint));
			return root;
		}
		for (TimelineSourceAudioTrack track : tracks) {
			root.getChildren().add(cleanupGroup(track));
		}
		Label note = new Label("Cleanup is applied to the preview and again on export; the"
				+ " recording files are never rewritten. The loudness target normalizes the exported"
				+ " mix, not the lane.");
		note.setWrapText(true);
		note.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
		root.getChildren().add(note);
		return root;
	}

	/** One lane's cleanup group: noise reduction, high-pass and the programme loudness target. */
	private VBox cleanupGroup(TimelineSourceAudioTrack track) {
		AudioCleanup cleanup = currentCleanup(track.id());

		ChoiceGroup noise = choice("off", "Off", "light", "Light", "balanced", "Balanced",
				"strong", "Strong");
		noise.setValue(cleanup.noiseReduction().wire());
		noise.onValueChange(value -> setCleanup(track.id(), current -> current
				.withNoiseReduction(AudioCleanup.NoiseReduction.fromText(value))));

		ChoiceGroup highPass = highPassChoice(cleanup.highPassHz());
		highPass.onValueChange(value -> setCleanup(track.id(),
				current -> current.withHighPassHz(Integer.parseInt(value))));

		ChoiceGroup loudness = loudnessChoice(cleanup.loudnessTargetLufs());
		loudness.onValueChange(value -> setCleanup(track.id(),
				current -> current.withLoudnessTargetLufs(Double.parseDouble(value))));

		// The chip rows stack under their captions rather than sitting at a row's right edge: four
		// reduction levels plus the caption overflow the inspector's width in one HBox.
		VBox body = new VBox(12, labeledChoice("Noise reduction", noise),
				labeledChoice("High-pass", highPass),
				labeledChoice("Loudness target", loudness));
		return group(track.label(), () -> resetCleanup(track.id()), body);
	}

	/** A caption above a chip row (the inspector's row layout for wide ChoiceGroups). */
	private VBox labeledChoice(String caption, ChoiceGroup choices) {
		Label label = new Label(caption);
		label.getStyleClass().add("row-label");
		return new VBox(6, label, choices);
	}

	private ChoiceGroup highPassChoice(int currentHz) {
		ChoiceGroup group = choice("0", "Off", "80", "80 Hz", "120", "120 Hz", "180", "180 Hz");
		int clamped = AudioCleanup.clampHighPass(currentHz);
		if (clamped > 0 && !HIGH_PASS_OPTIONS.contains(clamped)) {
			// A project (or an agent) may carry a filter the inspector does not offer; show it
			// instead of silently lying about the value.
			group.item(Integer.toString(clamped), clamped + " Hz");
		}
		group.setValue(Integer.toString(clamped));
		return group;
	}

	private ChoiceGroup loudnessChoice(double currentLufs) {
		ChoiceGroup group = choice("0", "Off", "-16", "-16 LUFS", "-14", "-14 LUFS",
				"-23", "-23 LUFS");
		String wire = loudnessWire(currentLufs);
		boolean known = wire.equals("0") || LOUDNESS_OPTIONS.stream()
				.map(EditorInspectorPanel::loudnessWire).anyMatch(wire::equals);
		if (!known) {
			group.item(wire, wire + " LUFS");
		}
		group.setValue(wire);
		return group;
	}

	/** The chip value of a loudness target: {@code 0} when off, else the LUFS number. */
	private static String loudnessWire(double lufs) {
		if (!(lufs < 0)) {
			return "0";
		}
		double rounded = Math.rint(lufs * 10) / 10;
		return rounded == Math.rint(rounded) ? Long.toString((long) rounded)
				: String.format(java.util.Locale.ROOT, "%.1f", rounded);
	}

	private AudioCleanup currentCleanup(String trackId) {
		return store.state().defaultSourceAudioTrackSettingsTyped().trackOrDefault(trackId)
				.cleanup();
	}

	/** Writes one stage of a lane's cleanup, keeping the other stages. */
	private void setCleanup(String trackId,
			java.util.function.UnaryOperator<AudioCleanup> change) {
		AudioCleanup next = change.apply(currentCleanup(trackId));
		store.apply(new SourceAudioCommands.SetDefaultTrackCleanup(trackId, next));
	}

	private void resetCleanup(String trackId) {
		store.apply(new SourceAudioCommands.SetDefaultTrackCleanup(trackId, AudioCleanup.NONE));
	}

	/** Port of {@code captionsSectionContent} in SettingsPanel.tsx. */
	private VBox captionsSection() {
		AutoCaptionSettings settings = store.state().autoCaptionSettings();
		VBox root = new VBox(16);
		if (store.selectedCaptionId() != null) {
			root.getChildren().add(new CaptionListPanel(store, playback));
		}

		Switch enabled = Switch.xs();
		enabled.setChecked(settings.enabled());
		enabled.onCheckedChange(value -> applyCaptionSettings(json -> json.put("enabled", value)));

		// Generation and Font settings: the two groups of the captions panel, each with the
		// chevron and hover Reset affordance.
		VBox generation = group("Generation", this::resetGeneration);
		generation.getChildren().add(row("Show", enabled));

		var models = captionModels == null ? null : captionModels.state();
		boolean generating = models != null && models.generating();
		boolean downloading = models != null && models.downloadStatus()
				== dev.gravaai.ui.editor.transcript.CaptionModelUiService.DownloadStatus.DOWNLOADING;
		boolean hasModel = models != null && models.modelPath() != null;
		int downloadProgress = models == null ? 0 : models.downloadProgress();
		@Nullable String modelId = models == null ? null : models.modelId();
		int cueCount = store.state().autoCaptions().size();

		if (advanced) {
			Button custom = new Button("Use custom", Button.Variant.OUTLINE, Button.Size.MD);
			custom.setMaxWidth(Double.MAX_VALUE);
			custom.setOnAction(event -> {
				if (captionModels != null) {
					captionModels.pickCustomModel();
				}
			});
			generation.getChildren().add(custom);
		}
		Select language = new Select();
		for (String[] option : CAPTION_LANGUAGES) {
			language.item(option[0], option[1]);
		}
		language.value(settings.language() == null || settings.language().isBlank() ? "auto"
				: settings.language());
		language.onValueChange(value -> applyCaptionSettings(json -> json.put("language", value)));
		language.setPrefWidth(180);
		generation.getChildren().add(row("Language", language));

		Select animation = new Select();
		for (AutoCaptionAnimation style : AutoCaptionAnimation.values()) {
			animation.item(style.wire(), labelFor(style.wire()));
		}
		animation.value(settings.animationStyle().wire());
		animation.onValueChange(
				value -> applyCaptionSettings(json -> json.put("animationStyle", value)));
		animation.setPrefWidth(180);
		generation.getChildren().add(row("Animation", animation));

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
		generation.getChildren().add(modelRow);

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
		generation.getChildren().add(modelButtons);

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
		generation.getChildren().add(generateBox);
		if (downloading) {
			javafx.scene.control.ProgressBar download = new javafx.scene.control.ProgressBar(
					downloadProgress / 100.0);
			download.setMaxWidth(Double.MAX_VALUE);
			generation.getChildren().add(download);
		}
		root.getChildren().add(generation);

		VBox fonts = group("Font settings", this::resetCaptionFonts);
		fonts.getChildren().add(slider("Font size", settings.fontSize(), 16, 72, 1,
				value -> Math.round(value) + "px",
				value -> applyCaptionSettings(json -> json.put("fontSize", value))));
		fonts.getChildren().add(colorInput("Text color", settings.textColor(),
				value -> applyCaptionSettings(json -> json.put("textColor", value))));
		if (advanced) {
			Switch quickAdd = Switch.xs();
			quickAdd.setChecked(settings.timelineQuickAdd());
			quickAdd.onCheckedChange(
					value -> applyCaptionSettings(json -> json.put("timelineQuickAdd", value)));
			fonts.getChildren().add(row("Hover to add on timeline", quickAdd));
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

	/** Restores the generation settings; the normaliser refills the removed object. */
	private void resetGeneration() {
		apply(json -> json.remove("autoCaptionSettings"));
	}

	/** Restores the caption font, colour and advanced text box settings. */
	private void resetCaptionFonts() {
		apply(json -> {
			JsonNode node = json.get("autoCaptionSettings");
			if (node instanceof ObjectNode settingsNode) {
				for (String field : List.of("fontSize", "textColor", "maxRows", "bottomOffset",
						"maxWidth", "boxRadius", "backgroundOpacity")) {
					settingsNode.remove(field);
				}
			}
		});
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
			root.getChildren().add(group("Transcript", null, transcriptPanel));
			return root;
		}
		var cues = store.state().autoCaptions();
		VBox transcript = group("Transcript", null);
		if (cues.isEmpty()) {
			Label empty = new Label("No captions yet. Generate captions to edit the transcript.");
			empty.setWrapText(true);
			empty.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
			transcript.getChildren().add(empty);
			root.getChildren().add(transcript);
			return root;
		}
		for (var cue : cues) {
			HBox row = new HBox(10);
			row.setAlignment(Pos.TOP_LEFT);
			row.setPadding(new Insets(4, 4, 4, 4));
			row.setStyle("-fx-background-radius: 6px;");
			Label time = new Label(PlaybackControls.formatDuration(cue.startMs() / 1000.0));
			time.setStyle("-fx-font-family: \"DejaVu Sans Mono\"; -fx-font-size: 10px; -fx-text-fill: -g-muted;");
			time.setMinWidth(52);
			Label text = new Label(cue.text());
			text.setWrapText(true);
			text.setStyle("-fx-font-size: 12px; -fx-text-fill: rgba(255,255,255,0.85);");
			HBox.setHgrow(text, Priority.ALWAYS);
			row.getChildren().addAll(time, text);
			row.setOnMouseClicked(event -> {
				store.setSelectedCaptionId(cue.id());
				playback.seek(cue.startMs() / 1000.0);
			});
			transcript.getChildren().add(row);
		}
		root.getChildren().add(transcript);
		return root;
	}

	private VBox meetingSection() {
		VBox root = new VBox(10);
		Label empty = new Label("Open a recording to transcribe it and write notes.");
		empty.setWrapText(true);
		empty.setStyle("-fx-font-size: 13px; -fx-text-fill: -g-muted;");
		root.getChildren().add(group("Notes", null, empty));
		return root;
	}

	private VBox settingsSection() {
		VBox root = new VBox(14);
		Switch autoZooms = Switch.xs("Auto-apply fresh recording auto-zooms");
		autoZooms.setChecked(actions.autoApplyFreshRecordingAutoZooms());
		autoZooms.onCheckedChange(actions::setAutoApplyFreshRecordingAutoZooms);

		VBox buttons = new VBox(8);
		buttons.getChildren().addAll(
				// SettingsPanel.tsx has a single "Keyboard shortcuts -> Customize" row; the config
				// dialog already lists every binding, so no separate help entry.
				settingsButton("Customize keyboard shortcuts", Icons.Keyboard, onShortcutsConfig),
				settingsButton("Tutorial", Icons.DocumentText, onTutorial),
				settingsButton("Add custom font", Icons.TextB, onAddCustomFont),
				settingsButton("Caption list", Icons.ClosedCaptioning, actions::openCaptionList),
				settingsButton("Extensions", Icons.PuzzlePiece, actions::openExtensions));

		// React editor Settings -> Permissions category (Linux parts of the page).
		root.getChildren().add(group("General", null, autoZooms, buttons));
		root.getChildren().add(superResolutionGroup());
		root.getChildren().add(group("Permissions", null,
				new dev.gravaai.ui.dashboard.RecordingPermissionsSettings()));
		return root;
	}

	/**
	 * Export quality row for the optional deep-zoom upscaler. Export only, so the label says so:
	 * the preview keeps the cheap sharpening pass and the toggle changes what an export writes.
	 */
	private VBox superResolutionGroup() {
		SuperResolutionUiService service = superResolution;
		SuperResolutionUiService.State state = service != null ? service.state()
				: new SuperResolutionUiService.State(false, false, 0, null, false);

		Switch upscale = Switch.xs("Upscale deep zooms in exports");
		upscale.setChecked(state.enabled());
		upscale.onCheckedChange(value -> {
			if (service != null) {
				service.setEnabled(value);
			}
		});

		VBox body = new VBox(10);
		body.getChildren().add(upscale);
		body.getChildren().add(new Label(
				"Runs a super-resolution model on the deepest zoom stages when you export, so"
						+ " text on a 1080p recording stays legible at 3.5–5×. The preview keeps"
						+ " the fast sharpening pass; exports get the sharper texture."));

		if (!state.modelInstalled() && !state.enabled()) {
			Label hint = new Label("The model (4.9 MB) downloads when you turn this on.");
			hint.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			body.getChildren().add(hint);
		}
		if (state.enabled() && !state.modelInstalled() && !state.failed()) {
			javafx.scene.control.ProgressBar progress = new javafx.scene.control.ProgressBar(
					Math.clamp(state.percent(), 0, 100) / 100.0);
			progress.setMaxWidth(Double.MAX_VALUE);
			body.getChildren().add(progress);
			if (state.message() != null) {
				Label message = new Label(state.message());
				message.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
				body.getChildren().add(message);
			}
		}
		if (state.failed() && state.message() != null) {
			Label failure = new Label(state.message());
			failure.setWrapText(true);
			failure.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-danger;");
			body.getChildren().add(failure);
		}
		if (state.modelInstalled()) {
			HBox actions = new HBox(8);
			actions.setAlignment(Pos.CENTER_LEFT);
			Label installed = new Label("Model installed.");
			installed.setStyle("-fx-font-size: 12px; -fx-text-fill: -g-muted;");
			Button remove = new Button("Remove model", Button.Variant.GHOST, Button.Size.SM);
			remove.setOnAction(event -> {
				if (service != null) {
					service.removeModel();
				}
			});
			HBox.setHgrow(installed, Priority.ALWAYS);
			actions.getChildren().addAll(installed, remove);
			body.getChildren().add(actions);
		}
		return group("Export quality", null, body);
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

	/** A group heading row: the title, and a hover Reset when the group has defaults to restore. */
	private HBox heading(String text, @Nullable Runnable reset) {
		HBox row = new HBox(8);
		row.getStyleClass().add("editor-section__heading");
		Label label = new Label(text);
		label.getStyleClass().add("editor-section__label");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		row.getChildren().addAll(label, spacer);
		if (reset != null) {
			Button resetButton = new Button("Reset", Button.Variant.GHOST, Button.Size.SM);
			resetButton.getStyleClass().add("editor-section__reset");
			resetButton.setOnAction(event -> {
				reset.run();
				rebuild();
			});
			row.getChildren().add(resetButton);
		}
		return row;
	}

	/**
	 * One collapsible group: the heading row first inside a subsection box, so
	 * {@link #groupCollapsibleSections} turns the box into a group with the chevron affordance.
	 */
	private VBox group(String title, @Nullable Runnable reset, Node... children) {
		VBox box = new VBox(12);
		box.getChildren().add(heading(title, reset));
		box.getChildren().addAll(children);
		return box;
	}

	private HBox row(String label, Node control) {
		HBox row = new HBox(12);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("editor-row");
		Label caption = new Label(label);
		caption.getStyleClass().add("row-label");
		// A wide control must not squeeze the caption down to an ellipsis.
		caption.setMinWidth(Region.USE_PREF_SIZE);
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

	/**
	 * The split preset picked when the user switches the webcam to split screen: the camera
	 * stacks below the content in a portrait frame and sits beside it otherwise.
	 */
	static String defaultSplitPreset(String aspectRatio) {
		return isPortraitAspect(aspectRatio)
				? WebcamPositionPreset.SPLIT_BOTTOM.wire()
				: WebcamPositionPreset.SPLIT_RIGHT.wire();
	}

	/** True for a taller-than-wide aspect wire ({@code 9:16}, {@code 4:5}, custom {@code W:H}). */
	static boolean isPortraitAspect(String aspectRatio) {
		String[] parts = aspectRatio == null ? new String[0] : aspectRatio.split(":");
		if (parts.length != 2) {
			return false;
		}
		try {
			return Double.parseDouble(parts[0]) < Double.parseDouble(parts[1]);
		} catch (NumberFormatException notARatio) {
			return false;
		}
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
