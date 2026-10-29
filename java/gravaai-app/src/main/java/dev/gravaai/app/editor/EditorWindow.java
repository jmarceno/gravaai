package dev.gravaai.app.editor;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.gravaai.core.editor.AnnotationCommands;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.editor.ClipCommands;
import dev.gravaai.core.editor.ZoomCommands;
import dev.gravaai.app.StageScenes;
import dev.gravaai.app.Startup;
import dev.gravaai.app.export.ProjectExporter;
import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.event.Subscription;
import dev.gravaai.core.project.ZoomRegion;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.services.projects.ProjectLibraryEntry;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RenameLibraryProject;
import dev.gravaai.services.recording.CursorTelemetry;
import dev.gravaai.services.recording.RecordingImportService;
import dev.gravaai.services.recording.RecordingPaths;
import dev.gravaai.services.recording.RecordingService;
import dev.gravaai.ui.agent.AgentUiService;
import dev.gravaai.ui.editor.AddCustomFontDialog;
import dev.gravaai.ui.editor.Announcements;
import dev.gravaai.ui.editor.CustomFonts;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.ui.editor.EditorShellView;
import dev.gravaai.ui.editor.EditorViewActions;
import dev.gravaai.ui.editor.ExtensionManager;
import dev.gravaai.ui.editor.ProjectBrowserDialog;
import dev.gravaai.ui.editor.Shortcuts;
import dev.gravaai.ui.editor.TutorialHelpDialog;
import dev.gravaai.ui.editor.export.ExportSessionModel;
import dev.gravaai.ui.editor.preview.PreviewFrameMapper;
import dev.gravaai.ui.editor.timeline.core.TimelineAudioPlacement;
import dev.gravaai.ui.kit.Toast;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.ui.editor.timeline.TimelineView;
import dev.gravaai.ui.kit.Button;
import dev.gravaai.ui.kit.Dialog;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One real editor window (plan P2.10, task-21): a {@link Stage} with the kit root, the
 * {@link EditorShellView}, the dashboard's {@code TimelineView} mounted into the shell and the
 * preview bound to the session's playback engine.
 *
 * <p>The window is the app-side implementation of {@link EditorViewActions}: save/save-as,
 * rename, project browser, custom fonts, shortcuts and the dialog entries go to app services via
 * {@link Services}; everything the editor itself owns (undo/redo, project state) stays in
 * {@link EditorSession}.
 */
public final class EditorWindow {

	private static final System.Logger LOG = System.getLogger("gravaai");

	/**
	 * App services the window needs (constructor injection, no DI framework - plan §5.1).
	 *
	 * @param projects project library read model/rename/recent; may be {@code null} in tests
	 * @param settings app settings store for fonts/shortcuts/preferences; may be {@code null}
	 * @param openProject opens another project in a new editor window
	 * @param projectUpdated called after save/rename/open so the app can remember recents + refresh
	 *     the dashboard
	 * @param onClosed called after the window hidden (AppContext removes it from its list)
	 * @param chooseOpenFile file-open dialog ({@code title -> path}); defaults to a JavaFX chooser
	 * @param chooseSaveFile file-save dialog ({@code title -> path}); defaults to a JavaFX chooser
	 * @param exporter the P3 export controller (settings, staged saves, reveal); may be {@code null}
	 * @param recordings the recording service for the Clips panel imports; may be {@code null}
	 * @param openRecordingsFolder reveals the recordings folder in the system file manager
	 */
	public record Services(
			@Nullable ProjectManager projects,
			@Nullable AppSettingsStore settings,
			Consumer<Path> openProject,
			Consumer<Path> projectUpdated,
			Runnable onClosed,
			@Nullable Function<String, Optional<Path>> chooseOpenFile,
			@Nullable Function<String, Optional<Path>> chooseSaveFile,
			@Nullable ProjectExporter exporter,
			java.util.function.@Nullable Supplier<@Nullable RecordingService> recordings,
			@Nullable Runnable openRecordingsFolder) {

		/** Backwards-compatible constructor without the export controller. */
		public Services(ProjectManager projects, AppSettingsStore settings, Consumer<Path> openProject,
				Consumer<Path> projectUpdated, Runnable onClosed,
				Function<String, Optional<Path>> chooseOpenFile,
				Function<String, Optional<Path>> chooseSaveFile) {
			this(projects, settings, openProject, projectUpdated, onClosed, chooseOpenFile,
					chooseSaveFile, null, null, null);
		}

		/** Backwards-compatible constructor without the Clips panel services. */
		public Services(ProjectManager projects, AppSettingsStore settings, Consumer<Path> openProject,
				Consumer<Path> projectUpdated, Runnable onClosed,
				Function<String, Optional<Path>> chooseOpenFile,
				Function<String, Optional<Path>> chooseSaveFile,
				ProjectExporter exporter) {
			this(projects, settings, openProject, projectUpdated, onClosed, chooseOpenFile,
					chooseSaveFile, exporter, null, null);
		}

		public static Services bare() {
			return new Services(null, null, path -> { }, path -> { }, () -> { }, null, null,
					null, null, null);
		}
	}

	private static final String SHORTCUTS_SETTING = "editor_shortcuts";
	private static final String AUTO_ZOOMS_SETTING = "editor_auto_apply_recording_zooms";
	private static final double NEW_ZOOM_SECONDS = 1.0;
	/**
	 * The in-editor save shortcut: Control on Linux/Windows, Command on macOS (JavaFX's platform
	 * shortcut modifier). Fixed on purpose, unlike the configurable timeline shortcuts.
	 */
	private static final KeyCombination SAVE_SHORTCUT = new KeyCodeCombination(KeyCode.S,
			KeyCombination.SHORTCUT_DOWN);

	private final EditorSession session;
	private final Services services;
	private final Stage stage;
	private final Scene scene;
	private final EditorShellView shell;
	private final TimelineView timeline;
	private final EditorViewActions actions;
	private final @Nullable RecordingImportController recordingImport;
	private Subscription storeSubscription;
	private Subscription timelineSubscription;
	private final Runnable closeNow;
	/** True when the window hosts the primary stage instead of owning a stage of its own. */
	private final boolean hosted;
	private boolean disposed;
	private boolean closeApproved;
	private boolean previewFrameLogged;
	private @Nullable AutoCaptionController captions;
	/** Engine->timeline playhead subscription (coalesced onto the FX thread). */
	private dev.gravaai.core.event.@Nullable Subscription playheadSubscription;
	private final java.util.concurrent.atomic.AtomicBoolean playheadScheduled =
			new java.util.concurrent.atomic.AtomicBoolean();
	private final java.util.concurrent.atomic.AtomicLong pendingPlayheadMs =
			new java.util.concurrent.atomic.AtomicLong();
	private int @Nullable [] cachedVideoSize;
	/** The fresh-recording auto-zoom pass runs at most once per editor. */
	private boolean freshRecordingZoomsTried;
	private volatile @Nullable Runnable onGoHome;

	private EditorWindow(Path projectPath, Services services) throws IOException {
		this(EditorSession.open(projectPath), services, null);
	}

	private EditorWindow(EditorSession session, Services services, @Nullable Stage hostStage) {
		this.services = services == null ? Services.bare() : services;
		this.session = session;
		this.actions = new Actions();
		java.util.function.Supplier<@Nullable RecordingService> recordingSupplier = services.recordings();
		if (recordingSupplier == null) {
			this.recordingImport = null;
		} else {
			java.util.function.Supplier<@Nullable RecordingService> recordings = recordingSupplier;
			this.recordingImport = new RecordingImportController(
					new RecordingImportController.Library() {
					@Override
					public RecordingImportService.ImportResult importRecording(String currentPath,
							String recordingPath, RecordingImportService.@Nullable RecordingWebcamSource currentWebcam,
							java.util.function.BooleanSupplier cancelled) throws IOException {
						RecordingService recordingService = recordings.get();
						if (recordingService == null) {
							throw new IOException("the recording service is not attached yet");
						}
						return recordingService.importRecording(currentPath, recordingPath,
								currentWebcam, cancelled);
					}

					@Override
					public void discardRecordingImport(String output) throws IOException {
						RecordingService recordingService = recordings.get();
						if (recordingService == null) {
							throw new IOException("the recording service is not attached yet");
						}
						recordingService.discardRecordingImport(output);
					}

					@Override
					public java.util.List<dev.gravaai.services.recording.CursorTelemetry.CursorTelemetryPoint>
							getCursorTelemetry(String videoPath) {
						RecordingService recordingService = recordings.get();
						return recordingService == null
								? java.util.List.of()
								: recordingService.getCursorTelemetry(videoPath);
					}
				}, new RecordingImportController.Session() {
					@Override
					public String videoPath() {
						return session.videoPath();
					}

					@Override
					public dev.gravaai.core.editor.EditorStore store() {
						return session.store();
					}

					@Override
					public dev.gravaai.core.project.ProjectDocument document() {
						return session.document();
					}

					@Override
					public void replaceSourceMedia(dev.gravaai.core.project.ProjectDocument document,
							double seekSeconds) {
						session.replaceSourceMedia(document, seekSeconds);
					}

					@Override
					public void pausePlayback() {
						if (session.playbackPort().state().isPlaying()) {
							session.playbackPort().togglePlayPause();
						}
					}

					@Override
					public void onMediaReplaced() {
						Platform.runLater(() -> {
							if (session.videoPath() != null) {
								shell.preview().setMediaPath(Path.of(session.videoPath()));
							}
							shell.preview().setCursorTrack(cursorTrack());
							cachedVideoSize = null;
						});
					}
				}, actions::autoApplyFreshRecordingAutoZooms);
		}
		this.timeline = new TimelineView(session.timelineDataSource(), session.timelineCommands());
		this.timelineSubscription = session.onTimelineChange(
				() -> Platform.runLater(timeline::refreshData));
		this.shell = new EditorShellView(session.store(), actions, session.playbackPort());
		timeline.onItemPressed(() -> shell.setClipsOpen(false));
		shell.setTimelineContent(timeline);
		// The timeline playhead is drawn from TimelineView.currentTimeMs; nothing else feeds it, so
		// subscribe to the engine clock here (React: useTimelineState currentTime). Coalesce the
		// ~60 Hz engine callbacks into at most one FX runLater per pulse.
		this.playheadSubscription = session.onPlayhead(ms -> {
			pendingPlayheadMs.set(Math.round(ms));
			if (playheadScheduled.compareAndSet(false, true)) {
				Platform.runLater(() -> {
					playheadScheduled.set(false);
					timeline.setCurrentTimeMs(pendingPlayheadMs.get());
				});
			}
		});
		shell.header().setProjectName(session.displayName());
		shell.setPreviewPlayback(session.engine());
		session.setTimelineSeek(shell.preview().timelineSeek());
		session.setTimelineScrub(shell.preview().timelineScrub());
		if (session.videoPath() != null) {
			shell.preview().setMediaPath(Path.of(session.videoPath()));
		}
		shell.preview().setCursorTrack(cursorTrack());
		shell.preview().previewReadyProperty().addListener((observable, wasReady, ready) -> {
			if (ready && !previewFrameLogged) {
				previewFrameLogged = true;
				Startup.log("editor preview ready");
			}
			if (ready && session.isUnsaved()) {
				// A fresh recording: React useFreshRecordingAutoZoom waits for the first frame
				// and cursor telemetry, then fires the suggest-zooms pass once.
				Platform.runLater(this::autoApplyFreshRecordingZooms);
			}
		});

		double initialWidth = hostStage != null && hostStage.getScene() != null && hostStage.getWidth() > 0
				? hostStage.getWidth() : 1440;
		double initialHeight = hostStage != null && hostStage.getScene() != null && hostStage.getHeight() > 0
				? hostStage.getHeight() : 900;
		this.scene = new Scene(new KitRoot(shell), initialWidth, initialHeight);
		Theme.install(scene);
		EditorShellView.installStyles(scene);
		Stage host = hostStage;
		this.hosted = host != null;
		this.stage = host != null ? host : new Stage();
		stage.setTitle(title());
		stage.setMinWidth(1024);
		stage.setMinHeight(700);
		StageScenes.setSceneKeepingSize(stage, scene);
		// Saving while editing (user report 2026-10-28): the header Save button and Ctrl+S (⌘S on
		// macOS) run the same save as the "Unsaved changes" prompt, without leaving the editor.
		shell.header().setOnSave(this::saveProject);
		scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
			if (SAVE_SHORTCUT.match(event)) {
				event.consume();
				saveProject();
			}
		});
		shell.header().setDirty(unsavedChanges());
		// Single-window mode: the app owns the primary stage (and its close request). The editor
		// disposes itself when the main window navigates back to the dashboard.
		this.closeNow = () -> {
			closeApproved = true;
			dispose();
			if (!hosted) {
				stage.close();
			}
		};
		if (!hosted) {
			stage.setOnCloseRequest(this::requestClose);
			stage.setOnHidden(event -> dispose());
		}
		this.storeSubscription = session.store()
				.onChange(changed -> Platform.runLater(this::refreshTitle));
	}

	/** Builds the window; must be called on the FX thread. */
	public static EditorWindow create(Path projectPath, Services services) throws IOException {
		return new EditorWindow(projectPath, services);
	}

	/**
	 * Builds the editor as the content of an existing stage (the app's single main window,
	 * plan §5.1): the stage's scene is replaced and no second window is created.
	 */
	public static EditorWindow createIn(Stage stage, Path projectPath, Services services)
			throws IOException {
		if (stage == null) {
			throw new IllegalArgumentException("stage must not be null");
		}
		return new EditorWindow(EditorSession.open(projectPath), services, stage);
	}

	/**
	 * Hosts a finished recording as a new, unsaved project in the main window (React
	 * {@code switchToEditor} after stop, or a raw recording card). The first save asks for a file.
	 */
	public static EditorWindow createForRecordingIn(Stage stage, Path videoPath, @Nullable String webcamPath,
			long webcamTimeOffsetMs, Services services) {
		if (stage == null) {
			throw new IllegalArgumentException("stage must not be null");
		}
		return new EditorWindow(EditorSession.openRecording(videoPath, webcamPath, webcamTimeOffsetMs),
				services, stage);
	}

	/** True when this editor is hosted by the app's main window (single-window mode). */
	public boolean isHosted() {
		return hosted;
	}

	/** Releases the editor resources (subscriptions, shell, engine, session); idempotent. */
	public void dispose() {
		if (disposed) {
			return;
		}
		disposed = true;
		try {
			if (playheadSubscription != null) {
				playheadSubscription.close();
				playheadSubscription = null;
			}
			if (storeSubscription != null) {
				storeSubscription.close();
			}
			if (timelineSubscription != null) {
				timelineSubscription.close();
			}
			if (captions != null) {
				captions.close();
			}
			shell.dispose();
			session.close();
		} finally {
			if (services.onClosed() != null) {
				services.onClosed().run();
			}
		}
	}

	/**
	 * Hosted-window close: prompts for unsaved changes like the standalone window, then disposes
	 * and runs {@code proceed} (used when the main window switches projects or goes Home).
	 */
	public void requestHostClose(Runnable proceed) {
		requestHostClose(proceed, null);
	}

	/** Same as {@link #requestHostClose(Runnable)} with a callback for the Cancel path. */
	public void requestHostClose(Runnable proceed, @Nullable Runnable cancelled) {
		Runnable next = proceed == null ? () -> { } : proceed;
		Runnable abort = cancelled == null ? () -> { } : cancelled;
		if (isExportRunning()) {
			confirmHostCloseDuringExport(next, abort);
			return;
		}
		if (closeApproved || !session.dirty()) {
			dispose();
			next.run();
			return;
		}
		Dialog dialog = new Dialog("Unsaved changes");
		dialog.description("Save changes to \"" + session.displayName() + "\" before closing?");
		Button discard = new Button("Discard", Button.Variant.DESTRUCTIVE_SOFT, Button.Size.SM);
		discard.setOnAction(action -> {
			dialog.close();
			closeApproved = true;
			dispose();
			next.run();
		});
		Button cancel = new Button("Cancel", Button.Variant.OUTLINE, Button.Size.SM);
		cancel.setOnAction(action -> {
			dialog.close();
			abort.run();
		});
		Button save = new Button("Save", Button.Variant.DEFAULT, Button.Size.SM);
		save.setOnAction(action -> {
			dialog.close();
			if (save()) {
				closeApproved = true;
				dispose();
				next.run();
			}
		});
		dialog.footer(discard, new Region(), cancel, save);
		dialog.show(shell);
	}

	public void show() {
		stage.show();
		Startup.log("editor window shown");
		PauseTransition previewWatchdog = new PauseTransition(Duration.seconds(8));
		previewWatchdog.setOnFinished(event -> {
			if (!previewFrameLogged) {
				LOG.log(System.Logger.Level.WARNING, "first preview frame missing: previewReady="
					+ shell.preview().isPreviewReady());
			}
		});
		previewWatchdog.play();
		Thread.ofVirtual().name("gravaai-editor-media").start(() -> {
			try {
				session.attachMedia();
			} catch (Throwable error) {
				LOG.log(System.Logger.Level.WARNING, "editor media attach failed: " + error);
			}
		});
		stage.toFront();
		stage.requestFocus();
	}

	public Stage stage() {
		return stage;
	}

	public Scene scene() {
		return scene;
	}

	/** Connects the Captions and Transcript sections to auto-caption generation (P3.1). */
	public void setCaptionController(AutoCaptionController controller) {
		if (captions != null) {
			captions.close();
		}
		captions = controller;
		shell.setCaptionModelService(controller);
	}

	/** Mounts the AI control chip/highlights and routes them to the timeline overlay. */
	public void setAgentUiService(AgentUiService service) {
		shell.setAgentUiService(service);
		if (shell.agentHighlights() != null) {
			timeline.setAgentHighlights(shell.agentHighlights());
		}
	}

	/**
	 * Installs the extension render hooks of this editor's preview (plan §5.18); null clears them.
	 * Forwarded through the shell to the preview renderer's compositor.
	 */
	public void setOverlayHooks(FrameOverlayHooks hooks) {
		shell.setOverlayHooks(hooks);
	}

	public EditorShellView shell() {
		return shell;
	}

	public EditorSession session() {
		return session;
	}

	public TimelineView timeline() {
		return timeline;
	}

	public @Nullable Path projectPath() {
		return session.projectPath();
	}

	private String title() {
		return "Gravaai - " + session.displayName();
	}

	/** Cursor telemetry sidecar ({@code <video>.cursor.json}); NONE when absent/invalid. */
	private PreviewFrameMapper.CursorTrack cursorTrack() {
		List<CursorSample> samples = CursorTelemetryLoader.load(session.videoPath());
		if (samples.isEmpty()) {
			return PreviewFrameMapper.CursorTrack.NONE;
		}
		return new PreviewFrameMapper.CursorTrack(samples, session.store().state().showCursor());
	}

	private void refreshTitle() {
		stage.setTitle(title());
		shell.header().setDirty(unsavedChanges());
	}

	public boolean isExportRunning() {
		try {
			return shell != null && shell.exportSession() != null && shell.exportSession().isExporting();
		} catch (RuntimeException error) {
			return false;
		}
	}

	private void requestClose(javafx.stage.WindowEvent event) {
		if (isExportRunning()) {
			event.consume();
			confirmCloseDuringExport(null, event);
			return;
		}
		if (closeApproved || !session.dirty()) {
			return;
		}
		event.consume();
		Dialog dialog = new Dialog("Unsaved changes");
		dialog.description("Save changes to \"" + session.displayName() + "\" before closing?");
		Button discard = new Button("Discard", Button.Variant.DESTRUCTIVE_SOFT, Button.Size.SM);
		discard.setOnAction(action -> {
			dialog.close();
			closeNow.run();
		});
		Button cancel = new Button("Cancel", Button.Variant.OUTLINE, Button.Size.SM);
		cancel.setOnAction(action -> dialog.close());
		Button save = new Button("Save", Button.Variant.DEFAULT, Button.Size.SM);
		save.setOnAction(action -> {
			dialog.close();
			if (save()) {
				closeNow.run();
			}
		});
		dialog.footer(discard, new Region(), cancel, save);
		dialog.show(shell);
	}

	/** Asks whether to cancel a running export before a hosted navigation; Cancel stays put. */
	private void confirmHostCloseDuringExport(Runnable proceed, Runnable cancelled) {
		Dialog dialog = new Dialog("Export in progress");
		dialog.description("An export is still rendering. Leave anyway and cancel it?");
		Button keepOpen = new Button("Keep editing", Button.Variant.DEFAULT, Button.Size.SM);
		keepOpen.setOnAction(action -> {
			dialog.close();
			cancelled.run();
		});
		Button leaveAnyway = new Button("Leave anyway", Button.Variant.DESTRUCTIVE_SOFT,
				Button.Size.SM);
		leaveAnyway.setOnAction(action -> {
			dialog.close();
			try {
				actions.cancelExport();
			} catch (RuntimeException ignored) {
				// Cancelling is best effort; navigation proceeds regardless.
			}
			closeApproved = true;
			dispose();
			proceed.run();
		});
		dialog.footer(new Region(), keepOpen, leaveAnyway);
		dialog.show(shell);
	}

	/** Asks whether to cancel a running export before closing; Cancel keeps the editor open. */
	private void confirmCloseDuringExport(@Nullable Runnable unused, javafx.stage.WindowEvent windowEvent) {
		Dialog dialog = new Dialog("Export in progress");
		dialog.description("An export is still rendering. Close anyway and cancel it?");
		Button keepOpen = new Button("Keep editing", Button.Variant.DEFAULT, Button.Size.SM);
		keepOpen.setOnAction(action -> dialog.close());
		Button closeAnyway = new Button("Close anyway", Button.Variant.DESTRUCTIVE_SOFT,
				Button.Size.SM);
		closeAnyway.setOnAction(action -> {
			dialog.close();
			try {
				actions.cancelExport();
			} catch (RuntimeException ignored) {
				// Cancelling is best effort; closing proceeds regardless.
			}
			if (windowEvent != null) {
				closeApproved = true;
				dispose();
				if (!hosted) {
					stage.close();
				}
			}
		});
		dialog.footer(new Region(), keepOpen, closeAnyway);
		dialog.show(shell);
	}

	/**
	 * True while the project has changes that are not on disk: edits since the last save, or no file
	 * yet (a recording straight from the recorder). Drives the Save button's unsaved marking.
	 */
	private boolean unsavedChanges() {
		return session.dirty() || session.isUnsaved();
	}

	/**
	 * The user-facing save: the header Save button and Ctrl+S (⌘S). It runs exactly the save the
	 * "Unsaved changes" prompt runs — the Projects-folder chooser while the project has no file, the
	 * project file once it has one — and reports the outcome with a toast, as the editor stays open.
	 */
	public void saveProject() {
		if (!save()) {
			// Cancelled chooser (nothing to report) or a failed save (already in an error dialog).
			return;
		}
		Path saved = session.projectPath();
		toast("Project saved", saved == null ? session.displayName() : saved.getFileName().toString(),
				Toast.Variant.SUCCESS);
	}

	private boolean save() {
		if (session.isUnsaved()) {
			// save-project without a path: the save dialog in the Projects folder.
			return saveAsChosen();
		}
		try {
			session.save();
			refreshTitle();
			Path saved = session.projectPath();
			if (saved != null && services.projectUpdated() != null) {
				services.projectUpdated().accept(saved);
			}
			return true;
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "save failed: " + error);
			errorDialog("Could not save the project", error.getMessage());
			return false;
		}
	}

	/** Save As through the chooser; returns false when cancelled or failed. */
	private boolean saveAsChosen() {
		Optional<Path> chosen = choose("Save project as", true);
		if (chosen.isEmpty()) {
			return false;
		}
		Path target = withProjectExtension(chosen.get());
		try {
			session.saveAs(target);
			shell.header().setProjectName(session.displayName());
			refreshTitle();
			if (services.projectUpdated() != null) {
				services.projectUpdated().accept(target);
			}
			return true;
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "save as failed: " + error);
			errorDialog("Could not save the project", error.getMessage());
			return false;
		}
	}

	private @Nullable Path projectsDirOrNull() {
		if (services.projects() == null) {
			return null;
		}
		try {
			return services.projects().getProjectsDir();
		} catch (IOException | RuntimeException error) {
			return null;
		}
	}

	private static Path withProjectExtension(Path target) {
		String name = target.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		if (name.endsWith(".gravaai") || name.endsWith(".openscreen") || name.endsWith(".json")) {
			return target;
		}
		return target.resolveSibling(target.getFileName() + ".gravaai");
	}

	private void errorDialog(String title, @Nullable String detail) {
		Dialog dialog = new Dialog(title);
		dialog.description(detail == null || detail.isBlank() ? "Unknown error" : detail);
		Button close = new Button("Close", Button.Variant.DEFAULT, Button.Size.SM);
		close.setOnAction(action -> dialog.close());
		dialog.footer(close);
		dialog.show(shell);
	}

	private java.util.Optional<Path> choose(String title, boolean save) {
		Function<String, Optional<Path>> chooser = save ? services.chooseSaveFile()
				: services.chooseOpenFile();
		if (chooser != null) {
			return chooser.apply(title);
		}
		javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
		fileChooser.setTitle(title);
		var extensionFilter = new javafx.stage.FileChooser.ExtensionFilter(
				"Gravaai projects (*.gravaai, *.openscreen)", "*.gravaai", "*.openscreen");
		fileChooser.getExtensionFilters().add(extensionFilter);
		if (save) {
			// save-project: default to the Projects folder and the project (or recording) name.
			Path current = session.projectPath();
			Path folder = current != null ? current.getParent() : projectsDirOrNull();
			if (folder != null && java.nio.file.Files.isDirectory(folder)) {
				fileChooser.setInitialDirectory(folder.toFile());
			}
			fileChooser.setInitialFileName(session.displayName() + ".gravaai");
		}
		java.io.File file = save ? fileChooser.showSaveDialog(stage)
				: fileChooser.showOpenDialog(stage);
		return file == null ? Optional.empty() : Optional.of(file.toPath());
	}

	/** Open dialog for wallpaper media (openVideoFilePicker / the image file input). */
	private Optional<Path> chooseMedia(String title, String kind, String... patterns) {
		javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
		fileChooser.setTitle(title);
		fileChooser.getExtensionFilters().add(
				new javafx.stage.FileChooser.ExtensionFilter(kind, patterns));
		java.io.File file = fileChooser.showOpenDialog(stage);
		return file == null ? Optional.empty() : Optional.of(file.toPath());
	}

	/** Probed {width, height} of the recording; {0, 0} when unknown (the dialog falls back to 16:9). */
	private static int[] videoSize(Path video) {
		try {
			return dev.gravaai.media.ffmpeg.MediaProbe.probe(video).video()
					.map(stream -> new int[] {stream.width(), stream.height()})
					.orElse(new int[] {0, 0});
		} catch (RuntimeException error) {
			return new int[] {0, 0};
		}
	}

	private double playheadMs() {
		return Math.max(0, session.playbackPort().state().currentTimeSeconds() * 1000.0);
	}

	/** Timeline duration from the clip regions ({@code timeline.totalMs} for the action guards). */
	private long timelineTotalMs() {
		long end = 0;
		for (dev.gravaai.core.project.ClipRegion clip : session.store().state().clipRegions()) {
			end = Math.max(end, clip.endMs());
		}
		return end;
	}

	private void toast(String title, @Nullable String description, Toast.Variant variant) {
		Toast shown = Toast.show(shell, title).variant(variant);
		if (description != null && !description.isEmpty()) {
			shown.description(description);
		}
	}

	/**
	 * {@code useFreshRecordingAutoZoom}: a new recording opens with interaction-based zooms, once,
	 * when the setting is on, there is cursor telemetry, no zoom exists yet and the source shape is
	 * eligible ({@code shouldAutoApplyFreshRecordingZoomsForSource}). Silent unless zooms were added.
	 */
	private void autoApplyFreshRecordingZooms() {
		if (freshRecordingZoomsTried) {
			return;
		}
		freshRecordingZoomsTried = true;
		if (!actions.autoApplyFreshRecordingAutoZooms() || timelineTotalMs() == 0
				|| !session.store().state().zoomRegions().isEmpty()
				|| session.cursorTelemetryPoints().size() < 2) {
			return;
		}
		int[] size = actions.exportSourceSize();
		if (!dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils
				.shouldAutoApplyFreshRecordingZoomsForSource(size == null ? null : size[0],
						size == null ? null : size[1], "linux")) {
			return;
		}
		var result = timeline.suggestZooms();
		if (result.status() != dev.gravaai.ui.editor.timeline.core.ZoomSuggestionUtils
				.SuggestionStatus.OK) {
			return;
		}
		int added = timeline.addSuggestedZooms();
		if (added > 0) {
			toast("Added " + added + " interaction-based zoom suggestion"
					+ (added == 1 ? "" : "s"), null, Toast.Variant.SUCCESS);
		}
	}

	/**
	 * Port of {@code useTimelineZoomActions.handleSuggestZooms}: guards, telemetry check, the
	 * suggestion build through {@link TimelineView#suggestZooms()} and the applied result toast.
	 */
	private void suggestInteractionZooms() {
		long totalMs = timelineTotalMs();
		if (totalMs == 0) {
			return;
		}
		if (!actions.autoApplyFreshRecordingAutoZooms()) {
			toast("Suggested zooms are unavailable while cursor looping is enabled.", null,
					Toast.Variant.ACCENT);
			return;
		}
		if (session.cursorTelemetryPoints().size() < 2) {
			toast("No cursor telemetry available",
					"Record a screencast first to generate cursor-based suggestions.",
					Toast.Variant.ACCENT);
			return;
		}
		var result = timeline.suggestZooms();
		switch (result.status()) {
			case NO_TELEMETRY -> toast("No usable cursor telemetry",
					"The recording does not include enough cursor movement data.", Toast.Variant.ACCENT);
			case NO_INTERACTIONS -> toast("No clear interaction moments found",
					"Try a recording with pauses or clicks around important actions.",
					Toast.Variant.ACCENT);
			case NO_SLOTS -> toast("No auto-zoom slots available",
					"Detected dwell points overlap existing zoom regions.", Toast.Variant.ACCENT);
			default -> {
				int added = timeline.addSuggestedZooms();
				toast("Added " + added + " interaction-based zoom suggestion"
						+ (added == 1 ? "" : "s"), null, Toast.Variant.SUCCESS);
			}
		}
	}

	/**
	 * Port of {@code useTimelineAudioActions.handleAddAudio}: pick a file, probe it, find the
	 * placement relative to the playhead and apply the audio-region command.
	 */
	private void addAudioLayerAtPlayhead(int preferredTrackIndex) {
		if (session.videoPath() == null || timelineTotalMs() == 0) {
			return;
		}
		Optional<Path> chosen = chooseMedia("Select Audio File", "Audio",
				"*.mp3", "*.wav", "*.aac", "*.m4a", "*.flac", "*.ogg");
		if (chosen.isEmpty()) {
			return;
		}
		Path audioPath = chosen.get();
		long audioDurationMs;
		try {
			audioDurationMs = Math.round(
					dev.gravaai.media.ffmpeg.MediaProbe.probe(audioPath).durationSeconds() * 1000);
		} catch (RuntimeException error) {
			audioDurationMs = 0;
		}
		if (audioDurationMs <= 0) {
			toast("Could not read audio file",
					"The selected file may be corrupted or in an unsupported format.",
					Toast.Variant.DANGER);
			return;
		}
		long totalMs = timelineTotalMs();
		long startPos = Math.max(0, Math.min(Math.round(playheadMs()), totalMs));
		if (totalMs - startPos <= 0) {
			toast("Cannot place audio here",
					"There is no remaining space at the current playhead position.",
					Toast.Variant.DANGER);
			return;
		}
		TimelineAudioPlacement.AudioPlacement placement = TimelineAudioPlacement.resolveAudioPlacement(
				session.store().state().audioRegions(), startPos, totalMs, audioDurationMs,
				preferredTrackIndex);
		if (placement == null) {
			toast("Cannot place audio here",
					"Audio region already exists at this location or not enough space available.",
					Toast.Variant.DANGER);
			return;
		}
		session.store().apply(new dev.gravaai.core.editor.AudioCommands.Add(startPos,
				startPos + placement.durationMs(), audioPath.toString(), placement.trackIndex()));
	}

	private List<ProjectBrowserDialog.Entry> browserEntries() {
		ProjectManager projectManager = services.projects();
		if (projectManager == null) {
			return List.of();
		}
		try {
			List<ProjectLibraryEntry> projectEntries = projectManager.listProjectEntries();
			if (projectEntries == null) {
				return List.of();
			}
			List<ProjectBrowserDialog.Entry> entries = new ArrayList<>();
			for (ProjectLibraryEntry entry : projectEntries) {
				entries.add(new ProjectBrowserDialog.Entry(Path.of(entry.path()), entry.name(),
						entry.updatedAt(),
						entry.thumbnailPath() == null ? null : Path.of(entry.thumbnailPath()),
						Path.of(entry.path()).equals(session.projectPath())));
			}
			return entries;
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "project browser entries failed: " + error);
			return List.of();
		}
	}

	/** The app-side actions implementation. */
	public void setOnGoHome(Runnable goHome) {
		this.onGoHome = goHome;
	}

	private final class Actions implements EditorViewActions {

		@Override
		public void goHome() {
			Runnable go = onGoHome;
			if (go != null) {
				go.run();
			}
		}

		@Override
		public void save() {
			EditorWindow.this.save();
		}

		@Override
		public void saveAs() {
			saveAsChosen();
		}

		@Override
		public void renameProject(String name) {
			if (name == null || name.isBlank()) {
				return;
			}
			Path source = session.projectPath();
			if (source == null) {
				// Unsaved recording: naming it is save-project-named into the Projects folder.
				Path folder = projectsDirOrNull();
				if (folder == null) {
					saveAsChosen();
					return;
				}
				String clean = name.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
				Path target = folder.resolve(clean + ".gravaai");
				try {
					java.nio.file.Files.createDirectories(folder);
					session.saveAs(target);
					shell.header().setProjectName(session.displayName());
					refreshTitle();
					if (services.projectUpdated() != null) {
						services.projectUpdated().accept(target);
					}
				} catch (IOException | RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING, "save named failed: " + error);
					errorDialog("Could not save the project", error.getMessage());
				}
				return;
			}
			Path renamed = source;
			try {
				List<Path> allowed = new ArrayList<>();
				allowed.add(source);
				ProjectManager projectManager = services.projects();
				List<ProjectLibraryEntry> projectEntries = projectManager == null
						? null
						: projectManager.listProjectEntries();
				if (projectEntries != null) {
					for (ProjectLibraryEntry entry : projectEntries) {
						allowed.add(Path.of(entry.path()));
					}
				}
				renamed = RenameLibraryProject.renameLibraryProject(source, name, allowed,
						path -> List.of(ProjectManager.getProjectThumbnailPath(path)));
				session.relocate(renamed);
				shell.header().setProjectName(session.displayName());
				refreshTitle();
				if (services.projectUpdated() != null) {
					services.projectUpdated().accept(renamed);
				}
			} catch (IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "rename failed: " + error);
				errorDialog("Could not rename the project", error.getMessage());
			}
		}

		@Override
		public ExportPreferences exportPreferences() {
			ProjectExporter exporter = services.exporter();
			return exporter == null ? ExportPreferences.defaults() : exporter.exportPreferences();
		}

		@Override
		public void saveExportPreferences(ExportPreferences preferences) {
			ProjectExporter exporter = services.exporter();
			if (exporter != null) {
				exporter.saveExportPreferences(preferences);
			}
		}

		@Override
		public void startExport(ExportPreferences preferences) {
			ProjectExporter exporter = services.exporter();
			if (exporter == null) {
				errorDialog("Export", "The export pipeline is not available.");
				return;
			}
			LOG.log(System.Logger.Level.INFO, "export requested for " + session.projectPath());
			exporter.startExport(session, preferences, shell.exportSession(), stage);
		}

		@Override
		public void cancelExport() {
			ProjectExporter exporter = services.exporter();
			if (exporter != null) {
				exporter.cancelExport(shell.exportSession());
			}
		}

		@Override
		public void retrySaveExport() {
			ProjectExporter exporter = services.exporter();
			if (exporter != null) {
				exporter.retrySaveExport(session, shell.exportSession(), stage);
			}
		}

		@Override
		public void revealExportedFile() {
			ProjectExporter exporter = services.exporter();
			if (exporter != null) {
				exporter.revealExportedFile(shell.exportSession());
			}
		}

		@Override
		public void discardPendingExportSave(ExportSessionModel.PendingExportSave pending) {
			ProjectExporter exporter = services.exporter();
			if (exporter != null) {
				exporter.discardPendingExportSave(pending);
			}
		}

		@Override
		public int[] exportSourceSize() {
			if (session.videoPath() == null) {
				return new int[] {0, 0};
			}
			int[] size = cachedVideoSize;
			if (size == null) {
				size = videoSize(Path.of(session.videoPath()));
				cachedVideoSize = size;
			}
			return size;
		}

		@Override
		public Optional<Path> chooseWallpaper() {
			return chooseMedia("Choose wallpaper", "Images", "*.png", "*.jpg", "*.jpeg", "*.webp",
					"*.gif", "*.bmp");
		}

		@Override
		public Optional<Path> chooseVideoWallpaper() {
			return chooseMedia("Choose video background", "Videos", "*.mp4", "*.webm", "*.mov",
					"*.m4v", "*.mkv", "*.avi");
		}

		@Override
		public void openCropEditor() {
			var state = session.store().state();
			Path video = Path.of(session.videoPath());
			int[] size = videoSize(video);
			dev.gravaai.ui.editor.CropEditorDialog dialog = new dev.gravaai.ui.editor.CropEditorDialog(
					state.cropRegion(), size[0], size[1],
					region -> dev.gravaai.ui.editor.EditorMutations.update(session.store(), json -> {
						var crop = json.putObject("cropRegion");
						crop.put("x", region.x());
						crop.put("y", region.y());
						crop.put("width", region.width());
						crop.put("height", region.height());
					}));
			dialog.show(shell);
			long sourceMs = dev.gravaai.core.project.Clips.mapTimelineTimeToSourceTime(
					playheadMs(), state.clipRegions());
			Thread.ofVirtual().name("crop-frame").start(() -> {
				try (var reader = new dev.gravaai.media.compose.MediaFrameReader(video, 0, true)) {
					var frame = reader.decode(sourceMs / 1000.0);
					if (frame != null) {
						var image = dev.gravaai.ui.editor.CropEditorDialog.toImage(frame.width(),
								frame.height(), frame.pixelsTopFirstRgba());
						javafx.application.Platform.runLater(() -> dialog.setFrame(image));
					}
				} catch (RuntimeException error) {
					LOG.log(System.Logger.Level.WARNING, "crop frame decode failed: " + error);
				}
			});
		}

		@Override
		public void openKeyboardShortcutsHelp() {
			new dev.gravaai.ui.editor.KeyboardShortcutsHelp(shortcuts(),
					Shortcuts.isMacPlatform(), this::openShortcutsConfig).popover().show();
		}

		@Override
		public void openShortcutsConfig() {
			shell.openShortcutsConfigDialog();
		}

		@Override
		public void openTutorialHelp() {
			new TutorialHelpDialog().show(shell);
		}

		@Override
		public void openCaptionList() {
			shell.inspector().show(dev.gravaai.ui.editor.EditorSidebarRail.Section.CAPTIONS);
		}

		@Override
		public void openExtensions() {
			Dialog dialog = new Dialog("Extensions");
			dialog.content(new ExtensionManager());
			Button close = new Button("Close", Button.Variant.DEFAULT, Button.Size.SM);
			close.setOnAction(action -> dialog.close());
			dialog.footer(close);
			dialog.show(shell);
		}

		@Override
		public void addAnnotationLayer() {
			addTextAnnotation();
		}

		@Override
		public void addTextAnnotation() {
			addAnnotationOfType(dev.gravaai.core.project.AnnotationType.TEXT);
		}

		@Override
		public void addFigureAnnotation() {
			addAnnotationOfType(dev.gravaai.core.project.AnnotationType.FIGURE);
		}

		@Override
		public void addImageAnnotation() {
			addAnnotationOfType(dev.gravaai.core.project.AnnotationType.IMAGE);
		}

		@Override
		public void addBlurAnnotation() {
			addAnnotationOfType(dev.gravaai.core.project.AnnotationType.BLUR);
		}

		/**
		 * Add Layer → overlay (React handleAddAnnotation with the menu's nextTrack): a 1 s region
		 * at the playhead, pulled back so it fits the timeline, on a new lane below the existing
		 * overlays so it never hides under another block.
		 */
		private void addAnnotationOfType(dev.gravaai.core.project.AnnotationType type) {
			double totalMs = session.timelineDataSource().totalDurationMs();
			double duration = Math.min(1000, totalMs);
			if (duration <= 0) {
				return;
			}
			double start = Math.max(0, Math.min(playheadMs(), totalMs - duration));
			int nextTrack = 0;
			for (dev.gravaai.core.project.AnnotationRegion region
					: session.store().state().annotationRegions()) {
				nextTrack = Math.max(nextTrack,
						(region.trackIndex() == null ? 0 : region.trackIndex()) + 1);
			}
			session.store().apply(new AnnotationCommands.Add(start, start + duration, nextTrack));
			String id = session.store().selectedAnnotationId();
			if (id != null && type != dev.gravaai.core.project.AnnotationType.TEXT) {
				session.store().apply(new AnnotationCommands.ChangeType(id, type));
			}
		}

		@Override
		public void addAudioLayer() {
			// "Add Layer → Audio" (EditorPreviewPanel): prefer the next free audio track.
			int nextTrackIndex = 0;
			for (dev.gravaai.core.project.AudioRegion region : session.store().state().audioRegions()) {
				nextTrackIndex = Math.max(nextTrackIndex,
						(region.trackIndex() == null ? 0 : region.trackIndex()) + 1);
			}
			addAudioLayerAtPlayhead(nextTrackIndex);
		}

		@Override
		public void addZoom() {
			double start = playheadMs();
			double end = start + NEW_ZOOM_SECONDS * 1000;
			session.store().apply(new ZoomCommands.Add(start, end));
		}

		@Override
		public void suggestZooms() {
			suggestInteractionZooms();
		}

		@Override
		public void splitClip() {
			session.store().apply(new ClipCommands.Split(playheadMs()));
		}

		@Override
		public void toggleClips(boolean open) {
			LOG.log(System.Logger.Level.INFO, "clips panel " + (open ? "open" : "closed"));
		}

		@Override
		public List<dev.gravaai.services.recording.RecordingLibrary.RecordingLibraryEntry> listRecordings() {
			RecordingService recordings = services.recordings() == null ? null : services.recordings().get();
			if (recordings == null) {
				return List.of();
			}
			try {
				return recordings.listRecordings();
			} catch (IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "list recordings failed: " + error);
				throw error instanceof RuntimeException runtimeError
						? runtimeError
						: new RuntimeException(error);
			}
		}

		@Override
		public boolean setRecordingsRemoved(List<String> paths, boolean removed) {
			RecordingService recordings = services.recordings() == null ? null : services.recordings().get();
			if (recordings == null) {
				return false;
			}
			try {
				recordings.setRecordingsRemoved(paths, removed);
				return true;
			} catch (IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "set recordings removed failed: " + error);
				return false;
			}
		}

		@Override
		public java.util.concurrent.CompletableFuture<String> recordingThumbnail(String videoPath) {
			java.util.function.Supplier<@Nullable RecordingService> recordings = services.recordings();
			RecordingService recordingService = recordings == null ? null : recordings.get();
			if (recordingService == null) {
				return java.util.concurrent.CompletableFuture.completedFuture(null);
			}
			return recordingService.getRecordingThumbnail(videoPath);
		}

		@Override
		public void addRecordingsToTimeline(List<String> paths, @Nullable Integer index,
				Consumer<ClipImportResult> done) {
			if (recordingImport == null) {
				Platform.runLater(() -> done.accept(null));
				return;
			}
			recordingImport.addToTimeline(paths, index, done);
		}

		@Override
		public void cancelRecordingImport() {
			if (recordingImport != null) {
				recordingImport.cancel();
			}
		}

		@Override
		public void openRecordingsFolder() {
			if (services.openRecordingsFolder() != null) {
				services.openRecordingsFolder().run();
			} else {
				LOG.log(System.Logger.Level.INFO, "open recordings folder requested (no service)");
			}
		}

		@Override
		public List<ProjectBrowserDialog.Entry> projectBrowserEntries() {
			return browserEntries();
		}

		@Override
		public void openProject(Path path) {
			if (services.openProject() != null) {
				services.openProject().accept(path);
			}
		}

		@Override
		public void importProjectFile() {
			choose("Open project", false).ifPresent(path -> {
				if (services.openProject() != null) {
					services.openProject().accept(path);
				}
			});
		}

		@Override
		public List<CustomFonts.CustomFont> customFonts() {
			AppSettingsStore settings = services.settings();
			if (settings == null) {
				return List.of();
			}
			return new CustomFonts.Store(settings).load();
		}

		@Override
		public void saveCustomFonts(List<CustomFonts.CustomFont> fonts) {
			AppSettingsStore settings = services.settings();
			if (settings == null) {
				return;
			}
			try {
				new CustomFonts.Store(settings).save(fonts);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "save custom fonts failed: " + error);
			}
		}

		@Override
		public java.util.Map<Shortcuts.Action, Shortcuts.Binding> shortcuts() {
			AppSettingsStore settings = services.settings();
			if (settings == null) {
				return Shortcuts.defaults();
			}
			JsonNode stored = settings.readSetting(SHORTCUTS_SETTING);
			if (stored == null || !stored.isObject()) {
				return Shortcuts.defaults();
			}
			java.util.Map<Shortcuts.Action, Shortcuts.Binding> shortcuts = Shortcuts.defaults();
			for (Shortcuts.Action action : Shortcuts.Action.values()) {
				JsonNode value = stored.get(action.wire());
				if (value != null && value.isObject() && value.path("key").isString()) {
					shortcuts.put(action, new Shortcuts.Binding(value.get("key").asString(),
							value.path("ctrl").asBoolean(false),
							value.path("shift").asBoolean(false),
							value.path("alt").asBoolean(false)));
				}
			}
			return shortcuts;
		}

		@Override
		public void saveShortcuts(java.util.Map<Shortcuts.Action, Shortcuts.Binding> shortcuts) {
			AppSettingsStore settings = services.settings();
			if (settings == null) {
				return;
			}
			ObjectNode root = dev.gravaai.core.json.Json.nodeFactory().objectNode();
			for (java.util.Map.Entry<Shortcuts.Action, Shortcuts.Binding> entry : shortcuts.entrySet()) {
				ObjectNode binding = root.putObject(entry.getKey().wire());
				binding.put("key", entry.getValue().key());
				binding.put("ctrl", entry.getValue().ctrl());
				binding.put("shift", entry.getValue().shift());
				binding.put("alt", entry.getValue().alt());
			}
			try {
				settings.writeSetting(SHORTCUTS_SETTING, root);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "save shortcuts failed: " + error);
			}
		}

		@Override
		public boolean autoApplyFreshRecordingAutoZooms() {
			AppSettingsStore settings = services.settings();
			if (settings == null) {
				return true;
			}
			JsonNode value = settings.readSetting(AUTO_ZOOMS_SETTING);
			return value == null || !value.isBoolean() || value.booleanValue();
		}

		@Override
		public void setAutoApplyFreshRecordingAutoZooms(boolean enabled) {
			AppSettingsStore settings = services.settings();
			if (settings == null) {
				return;
			}
			try {
				settings.writeSetting(AUTO_ZOOMS_SETTING,
						dev.gravaai.core.json.Json.nodeFactory().booleanNode(enabled));
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING, "save auto-zoom preference failed: " + error);
			}
		}

		@Override
		public Announcements.@Nullable Feed bundledAnnouncements() {
			return Announcements.bundledFeed();
		}

		@Override
		public void dismissAnnouncement(String id) {
			LOG.log(System.Logger.Level.INFO, "announcement dismissed: " + id);
		}

		@Override
		public void recordAnnouncementImpression(String id) {
			LOG.log(System.Logger.Level.INFO, "announcement impression: " + id);
		}

		@Override
		public void runAnnouncementAction(Announcements.Action action) {
			LOG.log(System.Logger.Level.INFO, "announcement action: " + action);
		}
	}
}
