package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.net.URL;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.gravaai.core.editor.EditorStore;
import dev.gravaai.core.editor.ExportPreferences.ExportQuality;
import dev.gravaai.media.export.ExportDimensions;
import dev.gravaai.media.export.GifExportSettings;
import dev.gravaai.ui.agent.AgentHighlightsController;
import dev.gravaai.ui.agent.AgentStatusChip;
import dev.gravaai.ui.agent.AgentUiService;
import dev.gravaai.core.event.Subscription;
import dev.gravaai.ui.editor.clips.ClipImportOverlay;
import dev.gravaai.ui.editor.clips.RecordingLibraryPanel;
import dev.gravaai.ui.editor.export.ExportMenu;
import dev.gravaai.ui.editor.export.ExportSessionModel;
import dev.gravaai.ui.editor.export.ExportSettingsMenu;
import dev.gravaai.ui.editor.export.ExportSettingsState;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

/**
 * Editor shell (port of {@code VideoEditor.tsx} + {@code layout/EditorShell.tsx}, plan §5.12/P2.6):
 * header, icon rail, inspector panel, preview column with the playback bar, timeline strip.
 *
 * <p>Dependencies are constructor-injected seams: the shared {@link EditorStore} (model, commands,
 * undo/redo from task-12), {@link EditorViewActions} (project/export/dialog services wired by
 * {@code AppContext}) and {@link EditorPlaybackPort} (playback engine, plan §5.9). The dashboard
 * teammate's timeline is mounted with {@link #setTimelineContent}.
 */
public final class EditorShellView extends VBox {

	private static final String STYLESHEET = "/ui/editor/editor.css";

	private final EditorStore store;
	private final EditorViewActions actions;
	private final EditorPlaybackPort playback;
	private @Nullable AgentStatusChip agentStatusChip;
	private @Nullable AgentHighlightsController agentHighlights;
	private final EditorHeaderView header;
	private final EditorSidebarRail rail;
	private @Nullable EditorInspectorPanel inspector;
	private dev.gravaai.ui.editor.transcript.@Nullable CaptionModelUiService captionModels;
	private Region inspectorPlaceholder;
	private HBox body;
	private final VBox dropHost;
	private @Nullable RecordingLibraryPanel clipsPanel;
	private boolean clipsOpen;
	private @Nullable ClipImportOverlay importOverlay;
	private final EditorPreviewPanel preview;
	private final PlaybackControls playbackControls;
	private final EditorTimelinePanel timelinePanel;
	private final ProjectBrowserDialog projectBrowser;
	private final ExportSettingsState exportSettings;
	private final ExportSessionModel exportSession;
	private final ExportMenu exportMenu;
	private final EditorAnnouncementBanner announcementBanner;
	private final Subscription storeSubscription;

	public EditorShellView(EditorStore store, EditorViewActions actions,
			EditorPlaybackPort playback) {
		this(store, actions, playback, null);
	}

	/** @param timelineContent optional pre-built timeline content; null builds the default one. */
	public EditorShellView(EditorStore store, EditorViewActions actions,
			EditorPlaybackPort playback, @Nullable Node timelineContent) {
		this.store = store;
		this.actions = actions;
		this.playback = playback;
		getStyleClass().add("editor-root");
		setFocusTraversable(true);

		header = new EditorHeaderView(store, actions);
		rail = new EditorSidebarRail();
		inspectorPlaceholder = new Region();
		inspectorPlaceholder.getStyleClass().add("editor-inspector");
		preview = new EditorPreviewPanel(store, actions);
		playbackControls = new PlaybackControls(playback, actions);
		timelinePanel = new EditorTimelinePanel();
		if (timelineContent != null) {
			timelinePanel.setContent(timelineContent);
		}
		rail.onSelect(section -> {
			if (clipsOpen) {
				setClipsOpen(false);
			}
			// React setActiveSection closes the clips panel and clears every timeline selection
			// (clip, zoom, annotation, audio, caption) before switching the inspector section.
			store.clearSelectionsExcept(null, null, null, null, null);
			if (inspector != null) {
				inspector.show(section);
			}
		});
		header.setOnToggleClips(open -> {
			actions.toggleClips(open);
			setClipsOpen(open);
		});

		projectBrowser = new ProjectBrowserDialog(header.browserButton(), actions.projectBrowserEntries(),
				actions::openProject, actions::importProjectFile);
		header.setOnOpenProjects(() -> {
			projectBrowser.setEntries(actions.projectBrowserEntries());
			projectBrowser.toggle();
		});
		header.setOnGoHome(actions::goHome);

		exportSettings = new ExportSettingsState(actions.exportPreferences(),
				actions::saveExportPreferences);
		exportSession = new ExportSessionModel();
		exportMenu = new ExportMenu(header.exportButton(), exportSettings, exportSession,
				new ExportMenu.Callbacks() {
					@Override
					public void startExport(dev.gravaai.core.editor.ExportPreferences preferences) {
						actions.startExport(preferences);
					}

					@Override
					public void cancelExport() {
						actions.cancelExport();
					}

					@Override
					public void retrySaveExport() {
						actions.retrySaveExport();
					}

					@Override
					public void revealExportedFile() {
						actions.revealExportedFile();
					}

					@Override
					public void discardPendingSave(
							ExportSessionModel.@Nullable PendingExportSave pending) {
						if (pending != null) {
							actions.discardPendingExportSave(pending);
						}
					}
				}, this::exportDimensions, this::hasCaptionsForSidecar);
		header.setOnOpenExportMenu(exportMenu::openFromTrigger);

		Announcements.@Nullable Feed bundledAnnouncements = actions.bundledAnnouncements();
		announcementBanner = new EditorAnnouncementBanner(
				bundledAnnouncements == null ? Announcements.Feed.EMPTY : bundledAnnouncements,
				actions.remoteAnnouncements(), actions.dismissedAnnouncementIds(),
				actions.announcementImpressionCounts(), actions.appVersion(),
				actions::dismissAnnouncement, actions::recordAnnouncementImpression,
				actions::runAnnouncementAction);

		VBox previewColumn = new VBox(preview, playbackControls);
		VBox.setVgrow(preview, Priority.ALWAYS);
		HBox.setHgrow(previewColumn, Priority.ALWAYS);
		previewColumn.setMinWidth(0);
		previewColumn.setFillWidth(true);

		body = new HBox(8, rail, inspectorPlaceholder, previewColumn);
		body.getStyleClass().add("editor-body");
		HBox.setHgrow(previewColumn, Priority.ALWAYS);
		VBox.setVgrow(body, Priority.ALWAYS);
		body.setMinHeight(0);

		// The drag-and-drop container spans the sidebar row and the timeline panel
		// (EditorShell.tsx wraps both; a drop outside the timeline appends at the end).
		dropHost = new VBox(body, timelinePanel);
		dropHost.getStyleClass().add("editor-drop-host");
		VBox.setVgrow(dropHost, Priority.ALWAYS);
		dropHost.setOnDragOver(this::handleRecordingDragOver);
		dropHost.setOnDragDropped(this::handleRecordingDrop);

		timelinePanel.setMinHeight(180);
		timelinePanel.setMaxHeight(280);
		timelinePanel.prefHeightProperty().bind(heightProperty().multiply(0.22));

		getChildren().addAll(header, announcementBanner, dropHost);
		// P2.11: the inspector (wallpaper grids/images) is built after the first visible pulse.
		Platform.runLater(() -> Platform.runLater(this::ensureInspector));
		storeSubscription = store.onChange(changed -> Platform.runLater(() -> {
			if (inspector != null) {
				inspector.showSelectionSettings();
			}
			header.refresh();
			preview.refresh();
			playbackControls.refresh();
		}));
	}

	/** Forwards the extension render hooks to the preview panel (P3.3, wire into AppContext). */
	public void setOverlayHooks(dev.gravaai.media.compose.FrameOverlayHooks hooks) {
		preview.setOverlayHooks(hooks);
	}

	/** Adds the editor stylesheet on top of the kit theme. */
	public static void installStyles(Scene scene) {
		dev.gravaai.ui.kit.Theme.install(scene);
		URL resource = EditorShellView.class.getResource(STYLESHEET);
		if (resource == null) {
			throw new IllegalStateException("Missing editor stylesheet: " + STYLESHEET);
		}
		String url = resource.toExternalForm();
		if (!scene.getStylesheets().contains(url)) {
			scene.getStylesheets().add(url);
		}
	}

	public EditorStore store() {
		return store;
	}

	public EditorViewActions actions() {
		return actions;
	}

	/** Mounts the AI-editing header chip and the transient timeline highlights (P3.2). */
	public void setAgentUiService(AgentUiService service) {
		if (agentStatusChip != null) {
			agentStatusChip.dispose();
			header.getChildren().remove(agentStatusChip);
		}
		if (agentHighlights != null) {
			agentHighlights.close();
		}
		if (service == null) {
			return;
		}
		agentStatusChip = new AgentStatusChip(service);
		int insertAt = Math.min(5, header.getChildren().size());
		header.getChildren().add(insertAt, agentStatusChip);
		agentHighlights = new AgentHighlightsController(service);
	}

	/** Connects the Captions and Transcript sections to the auto-caption controller (P3.1). */
	public void setCaptionModelService(
			dev.gravaai.ui.editor.transcript.CaptionModelUiService service) {
		captionModels = service;
		if (inspector != null) {
			inspector.setCaptionModelService(service);
		}
	}

	/** The highlights controller for the timeline overlay (null until setAgentUiService). */
	public @Nullable AgentHighlightsController agentHighlights() {
		return agentHighlights;
	}

	public EditorHeaderView header() {
		return header;
	}

	public EditorSidebarRail rail() {
		return rail;
	}

	private void ensureInspector() {
		if (inspector != null) {
			return;
		}
		inspector = new EditorInspectorPanel(store, actions, playback);
		inspector.setOnAddCustomFont(this::openAddCustomFontDialog);
		inspector.setOnShortcutsConfig(this::openShortcutsConfigDialog);
		inspector.setOnTutorial(() -> new TutorialHelpDialog().show(this));
		inspector.setRecordingPath(preview::mediaPath);
		if (captionModels != null) {
			inspector.setCaptionModelService(captionModels);
		}
		inspector.showSelectionSettings();
		if (inspector.selectionEditor() == EditorInspectorPanel.SelectionEditor.NONE) {
			inspector.show(rail.active());
		}
		if (body != null) {
			body.getChildren().set(1, inspector);
		}
	}

	/** The inspector; built now when a caller needs it before the deferred startup build. */
	public EditorInspectorPanel inspector() {
		ensureInspector();
		EditorInspectorPanel built = inspector;
		if (built == null) {
			throw new IllegalStateException("editor inspector was not built");
		}
		return built;
	}

	public EditorPreviewPanel preview() {
		return preview;
	}

	public PlaybackControls playbackControls() {
		return playbackControls;
	}

	public EditorTimelinePanel timelinePanel() {
		return timelinePanel;
	}

	/** Mounts the real timeline view (dashboard's {@code TimelineView}). */
	public void setTimelineContent(Node timelineContent) {
		this.timelineContent = timelineContent;
		timelinePanel.setContent(timelineContent);
	}

	private @Nullable Node timelineContent;

	/** Whether the Clips panel replaces the inspector content. */
	public boolean clipsOpen() {
		return clipsOpen;
	}

	/** The Clips panel (built on first open). */
	public @Nullable RecordingLibraryPanel clipsPanel() {
		return clipsPanel;
	}

	/**
	 * Swaps the inspector slot between the Clips panel and the inspector (React
	 * {@code panelContent}); the rail shows no active section while the panel is open.
	 */
	public void setClipsOpen(boolean open) {
		if (clipsOpen == open) {
			return;
		}
		clipsOpen = open;
		if (open) {
			ensureInspector();
			if (clipsPanel == null) {
				clipsPanel = new RecordingLibraryPanel(clipsHost());
				clipsPanel.importingProperty().addListener((observable, was, now) -> updateImportOverlay());
				clipsPanel.cancellingProperty().addListener((observable, was, now) -> {
					if (importOverlay != null) {
						importOverlay.setCancelling(now);
					}
				});
			}
			body.getChildren().set(1, clipsPanel);
			clipsPanel.refresh();
			clipsPanel.requestFocus();
		} else {
			body.getChildren().set(1, inspector != null ? inspector : inspectorPlaceholder);
		}
		rail.setPanelOpen(open);
		header.setClipsOpen(open);
	}

	private RecordingLibraryPanel.Host clipsHost() {
		return new RecordingLibraryPanel.Host() {
			@Override
			public java.util.List<dev.gravaai.services.recording.RecordingLibrary.RecordingLibraryEntry>
					listRecordings() throws java.io.IOException {
				return actions.listRecordings();
			}

			@Override
			public boolean setRecordingsRemoved(java.util.List<String> paths, boolean removed)
					throws java.io.IOException {
				return actions.setRecordingsRemoved(paths, removed);
			}

			@Override
			public void addRecordingsToTimeline(java.util.List<String> paths,
					@Nullable Integer index,
					java.util.function.Consumer<EditorViewActions.ClipImportResult> done) {
				actions.addRecordingsToTimeline(paths, index, done);
			}

			@Override
			public void cancelRecordingImport() {
				actions.cancelRecordingImport();
			}

			@Override
			public java.util.concurrent.CompletableFuture<String> recordingThumbnail(String path) {
				return actions.recordingThumbnail(path);
			}

			@Override
			public void openRecordingsFolder() {
				actions.openRecordingsFolder();
			}

			@Override
			public void close() {
				setClipsOpen(false);
			}
		};
	}

	/** Shows/hides the full-window import blocker with the panel's importing flag. */
	private void updateImportOverlay() {
		RecordingLibraryPanel panel = clipsPanel;
		boolean importing = panel != null && panel.importingProperty().get();
		if (importing && panel != null && importOverlay == null) {
			importOverlay = ClipImportOverlay.show(this, panel::cancelImport);
		} else if (!importing && importOverlay != null) {
			importOverlay.hide();
			importOverlay = null;
		}
	}

	private void handleRecordingDragOver(DragEvent event) {
		if (event.getDragboard().hasContent(RecordingLibraryPanel.RECORDING_DRAG)) {
			event.acceptTransferModes(TransferMode.COPY);
			event.consume();
		}
	}

	private void handleRecordingDrop(DragEvent event) {
		if (clipsPanel == null
				|| !event.getDragboard().hasContent(RecordingLibraryPanel.RECORDING_DRAG)) {
			return;
		}
		java.util.List<String> paths = recordingPaths(event.getDragboard()
				.getContent(RecordingLibraryPanel.RECORDING_DRAG));
		if (paths == null) {
			return;
		}
		event.setDropCompleted(true);
		event.consume();
		clipsPanel.addToTimeline(paths, timelineDropIndex(event));
	}

	/** {@code JSON.parse} of the drag payload; {@code null} when it is not a path list. */
	private static java.util.@Nullable List<String> recordingPaths(Object content) {
		if (!(content instanceof String json)) {
			return null;
		}
		try {
			JsonNode parsed = dev.gravaai.core.json.Json.parseJsonWithByteOrderMark(json);
			java.util.List<String> paths = new ArrayList<>();
			if (parsed.isArray()) {
				for (JsonNode node : parsed) {
					if (!node.isString() || node.asString().isEmpty()) {
						return null;
					}
					paths.add(node.asString());
				}
			} else if (parsed.isString()) {
				paths.add(parsed.asString());
			} else {
				return null;
			}
			return paths.isEmpty() ? null : paths;
		} catch (RuntimeException malformed) {
			return null;
		}
	}

	/**
	 * The insert index for a timeline drop: the first clip whose midpoint is right of the drop X
	 * (React compares {@code clientX} against clip midpoints), or {@code null} to append.
	 */
	private @Nullable Integer timelineDropIndex(DragEvent event) {
		Node picked = event.getPickResult().getIntersectedNode();
		boolean overTimeline = false;
		for (Node node = picked; node != null; node = node.getParent()) {
			if (node == timelinePanel) {
				overTimeline = true;
				break;
			}
		}
		if (!overTimeline
				|| !(timelineContent instanceof dev.gravaai.ui.editor.timeline.TimelineView view)) {
			return null;
		}
		double localX = view.sceneToLocal(event.getSceneX(), event.getSceneY()).getX();
		double timeMs = view.timeAtX(localX);
		java.util.List<dev.gravaai.core.project.ClipRegion> clips =
				dev.gravaai.core.project.Clips.sortClipRegions(store.state().clipRegions());
		int index = clips.size();
		for (int i = 0; i < clips.size(); i++) {
			dev.gravaai.core.project.ClipRegion clip = clips.get(i);
			if (timeMs < (clip.startMs() + clip.endMs()) / 2.0) {
				index = i;
				break;
			}
		}
		return index;
	}

	/**
	 * Hands the preview column the media-layer playback controller (task-17 owns the preview; this
	 * is the shell-side hook they asked for). Safe before or after the window is shown.
	 */
	public void setPreviewPlayback(dev.gravaai.media.playback.PlaybackController controller) {
		preview.setPlaybackController(controller);
	}

	public ProjectBrowserDialog projectBrowser() {
		return projectBrowser;
	}

	/** The export popover (the app drives {@link #exportSession()} from the export controller). */
	public ExportMenu exportMenu() {
		return exportMenu;
	}

	/** The export settings state (persisted through {@link EditorViewActions}). */
	public ExportSettingsState exportSettings() {
		return exportSettings;
	}

	/** The export session model (progress, errors, pending saves). */
	public ExportSessionModel exportSession() {
		return exportSession;
	}

	/** {@code hasCaptionsForSidecar}: auto-captions enabled and present. */
	private boolean hasCaptionsForSidecar() {
		var state = store.state();
		return state.autoCaptionSettings().enabled() && !state.autoCaptions().isEmpty();
	}

	/** Output sizes for the settings form (port of {@code useExportDimensions.ts}). */
	private ExportSettingsMenu.OutputDimensions exportDimensions() {
		int[] source = actions.exportSourceSize();
		int sourceWidth = source[0] > 0 ? source[0] : 1920;
		int sourceHeight = source[1] > 0 ? source[1] : 1080;
		var state = store.state();
		String aspectRatio = state.aspectRatio() == null ? "native" : state.aspectRatio();
		var crop = state.cropRegion();
		ExportDimensions.Size base = ExportDimensions.calculateMp4SourceDimensions(sourceWidth,
				sourceHeight, aspectRatio,
				new ExportDimensions.CropBounds(crop == null ? 1 : crop.width(),
						crop == null ? 1 : crop.height()));
		Map<ExportQuality, int[]> mp4 = new EnumMap<>(ExportQuality.class);
		for (ExportQuality quality : ExportQuality.values()) {
			ExportDimensions.Size size = ExportDimensions.calculateMp4ExportDimensions(
					base.width(), base.height(), quality);
			mp4.put(quality, new int[] {size.width(), size.height()});
		}
		GifExportSettings.Size gif = GifExportSettings.calculateOutputDimensions(sourceWidth,
				sourceHeight, exportSettings.gifSizePreset());
		return new ExportSettingsMenu.OutputDimensions(mp4, new int[] {gif.width(), gif.height()});
	}

	/** The announcement banner mounted under the header (hidden with the empty bundled feed). */
	public EditorAnnouncementBanner announcementBanner() {
		return announcementBanner;
	}

	/**
	 * Opens the "Add Google Font" dialog (Settings section entry; the shell owns the dialog so the
	 * preview/annotation panels stay free of persistence wiring).
	 */
	public AddCustomFontDialog openAddCustomFontDialog() {
		List<CustomFonts.CustomFont> fonts = new ArrayList<>(actions.customFonts());
		AddCustomFontDialog dialog = new AddCustomFontDialog(fonts, CustomFonts.defaultLoader(),
				font -> {
					List<CustomFonts.CustomFont> next = new ArrayList<>(actions.customFonts());
					next.add(font);
					actions.saveCustomFonts(next);
				});
		dialog.show(this);
		return dialog;
	}

	/**
	 * Opens the shortcut editor (Settings section entry); saving hands the draft to
	 * {@link EditorViewActions#saveShortcuts} so AppContext can persist it.
	 */
	public void openShortcutsConfigDialog() {
		new ShortcutsConfigDialog(Shortcuts.isMacPlatform(), actions.shortcuts(),
				actions::saveShortcuts, () -> { }).show(this);
	}

	/** Re-renders header, preview and playback from the store/port. */
	public void refresh() {
		header.refresh();
		preview.refresh();
		playbackControls.refresh();
	}

	/** Detaches from the store events and the playback seam. */
	public void dispose() {
		storeSubscription.close();
		if (agentStatusChip != null) {
			agentStatusChip.dispose();
		}
		if (agentHighlights != null) {
			agentHighlights.close();
		}
		if (importOverlay != null) {
			importOverlay.hide();
			importOverlay = null;
		}
		playbackControls.dispose();
		projectBrowser.close();
		exportMenu.close();
	}
}
