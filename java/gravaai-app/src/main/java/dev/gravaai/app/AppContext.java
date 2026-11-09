package dev.gravaai.app;

import org.jspecify.annotations.Nullable;

import dev.gravaai.app.editor.EditorSkeletonWindow;
import dev.gravaai.app.agent.AgentWiring;
import dev.gravaai.app.export.ProjectExporter;
import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.app.editor.EditorWindow;
import dev.gravaai.app.editor.ProjectPoster;
import dev.gravaai.app.meeting.MeetingHosts;
import dev.gravaai.app.meeting.MeetingIntegration;
import dev.gravaai.app.meeting.MeetingWindow;
import dev.gravaai.app.recording.HudRecordingPortAdapter;
import dev.gravaai.app.recording.HudWindowController;
import dev.gravaai.app.recording.LazyHudRecordingPort;
import dev.gravaai.app.recording.RecordingWiring;
import dev.gravaai.core.editor.EditorPreferencesStore;
import dev.gravaai.core.settings.AppSettingsStore;
import dev.gravaai.core.storage.RecentProjectsStore;
import dev.gravaai.core.storage.StorageFiles;
import dev.gravaai.core.storage.UserData;

import dev.gravaai.platform.compositor.DesktopSession;
import dev.gravaai.platform.filemanager.FileManager;
import dev.gravaai.platform.nativehelpers.NativeHelpers;
import dev.gravaai.platform.portal.Portal;
import dev.gravaai.platform.trash.Trash;

import dev.gravaai.services.automations.AutomationService;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.services.export.ExportCoordinator;
import dev.gravaai.services.extensions.ExtensionFrameOverlayHooks;
import dev.gravaai.services.extensions.ExtensionHost;
import dev.gravaai.services.automations.AutomationTriggers;
import dev.gravaai.services.projects.FirstFrameThumbnail;
import dev.gravaai.services.projects.ProjectManager;
import dev.gravaai.services.projects.RecoverableRecording;
import dev.gravaai.services.projects.RecoverableRecordingsProvider;
import dev.gravaai.services.projects.RecordingEntry;
import dev.gravaai.services.projects.RecordingsDirectory;
import dev.gravaai.services.projects.RecordingsReadModel;
import dev.gravaai.services.recording.cosmic.CosmicRecorder.StopResult;
import dev.gravaai.services.recording.RecordingService;
import dev.gravaai.services.sources.SelectedSource;
import dev.gravaai.services.shortcuts.DesktopShortcutPortal;
import dev.gravaai.services.shortcuts.GlobalShortcutAction;
import dev.gravaai.services.shortcuts.GlobalShortcutDispatcher;
import dev.gravaai.services.shortcuts.GlobalShortcutSettings;
import dev.gravaai.services.shortcuts.GlobalShortcuts;
import dev.gravaai.services.shortcuts.GlobalShortcutsService;
import dev.gravaai.ui.dashboard.DashboardDependencies;
import dev.gravaai.ui.dashboard.DashboardLibraryStore;
import dev.gravaai.ui.dashboard.DashboardSection;
import dev.gravaai.ui.dashboard.DashboardView;
import dev.gravaai.ui.kit.KitRoot;
import dev.gravaai.ui.kit.Theme;
import dev.gravaai.ui.launch.HudOverlayWindow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import tools.jackson.databind.JsonNode;

/**
 * Wires the services and owns the shutdown order (plan §5.1).
 *
 * <p>Everything is constructed by hand (no DI framework, plan §4.3). P1 wiring: the HUD overlay is
 * the startup window (plan §7.1 P1.7), the primary stage hosts the dashboard, the tray and the
 * global-shortcut service drive the HUD, and the recording services come from
 * {@link RecordingWiring} (plan P1.6/P1.8/P1.9).
 */
public final class AppContext {

	private static final System.Logger LOG = System.getLogger("gravaai");

	private final UserData userData;
	/** The shortcut that started this process, or {@code null} for a plain launch. */
	private final @Nullable GlobalShortcutAction pendingAction;
	/** Project/recording passed on the command line (file-manager association), or null. */
	private @Nullable Path launchFile;
	private final AppIcons icons;

	private @Nullable Stage primaryStage;
	private @Nullable Windows windows;
	private @Nullable AppSettingsStore appSettings;
	private @Nullable GlobalShortcutDispatcher dispatcher;
	private @Nullable GlobalShortcutsService shortcutsService;
	/** D-Bus, portals, notifications, automations and the tray; every piece degrades to null. */
	private final DesktopServices desktopServices = new DesktopServices(new TrayOperations());
	private @Nullable DashboardView dashboardView;
	/** The dashboard scene kept for single-window navigation (plan §5.1). */
	private @Nullable Scene dashboardScene;
	/** The editor currently hosted by the primary stage, or null on the dashboard. */
	private @Nullable EditorWindow activeEditor;
	/** True while the main window swaps dashboard <-> editor (no window was "closed"). */
	private boolean switchingViews;
	private @Nullable ProjectManager projects;
	private @Nullable RecentProjectsStore recentProjects;
	private final List<EditorWindow> editorWindows = new ArrayList<>();
	private @Nullable RecordingWiring recordingWiring;
	private @Nullable HudWindowController hudController;
	private @Nullable LazyHudRecordingPort lazyHudPort;
	private @Nullable HudRecordingPortAdapter hudPort;
	private @Nullable HudOverlayWindow hudWindow;
	private @Nullable MeetingIntegration meeting;
	private @Nullable MeetingWindow meetingWindow;
	private @Nullable AgentWiring agentWiring;
	private volatile @Nullable ProjectExporter exporter;
	private @Nullable ExportCoordinator exportCoordinator;
	// P3.3 extension host (plan §5.18): userData/extensions drives the render hooks of the editor
	// preview and the exporter. Created off the startup path; a failure disables extensions only.
	private @Nullable ExtensionHost extensionHost;
	private boolean backgroundServicesStarted;
	private boolean shutdown;

	private AppContext(UserData userData, @Nullable GlobalShortcutAction pendingAction) {
		this.userData = userData;
		this.pendingAction = pendingAction;
		this.icons = new AppIcons(userData);
	}

	public static AppContext create(@Nullable GlobalShortcutAction pendingAction) {
		return create(pendingAction, null);
	}

	/**
	 * @param launchFile a project/recording passed on the command line (file-manager association);
	 *        it takes precedence over the {@code GRAVAAI_EDITOR_OPEN} dev trigger
	 */
	public static AppContext create(@Nullable GlobalShortcutAction pendingAction,
			@Nullable Path launchFile) {
		AppContext context = new AppContext(UserData.resolve(), pendingAction);
		context.launchFile = launchFile;
		return context;
	}

	/** The app-settings store: created on first use, then shared by every service. */
	private AppSettingsStore appSettings() {
		AppSettingsStore current = appSettings;
		if (current == null) {
			current = new AppSettingsStore(userData.appSettingsFile());
			appSettings = current;
		}
		return current;
	}

	public void start(Stage primaryStage) {
		this.primaryStage = primaryStage;
		installStageIcons(primaryStage);
		// Single-window app: the primary stage hosts the dashboard, the editor and (later) the
		// meeting view. Gravaai lives in the tray, so closing the main window hides it; only the
		// tray Quit action (or an explicit application shutdown) may exit the process.
		installCloseToTray(primaryStage);
		// The language preference drives every translated surface; it is read before any window is
		// built, so the first frame is already in the stored (or system) language. A later change
		// from the Settings picker rebuilds the translated scenes (see onLocaleChanged).
		dev.gravaai.ui.i18n.UiI18n.init(appSettings());
		dev.gravaai.ui.i18n.UiI18n.addListener(this::onLocaleChanged);
		Windows mainWindows = new Windows(primaryStage, Boolean.getBoolean("gravaai.uiKit"));
		windows = mainWindows;
		mainWindows.setEditorLauncher(this::openProjectInEditor);
		// P2.11 fast path: when a project is requested at launch, the editor is the startup
		// window. Skip the HUD and the heavy services on the critical path; they start after
		// the editor window is visible. The launch file (CLI/file-manager) wins over the
		// GRAVAAI_EDITOR_OPEN dev trigger.
		String envLaunchProject = System.getenv("GRAVAAI_EDITOR_OPEN");
		Path launchPath = launchFile != null ? launchFile
				: envLaunchProject != null && !envLaunchProject.isBlank()
						? Path.of(envLaunchProject) : null;
		if (launchPath != null) {
			// P2.11 skeleton-first: show an editor-shaped skeleton immediately, then build the real
			// EditorWindow on the next FX pulse (the skeleton is the visible startup window).
			String launchName = launchPath.getFileName().toString().replaceFirst("\\.(gravaai|openscreen)$", "");
			javafx.scene.image.Image preloadedPoster = loadSidecarPoster(launchPath);
			// Single window: the skeleton is shown in the app's main window and the real editor
			// replaces its scene on the next FX pulse (no second native window).
			EditorSkeletonWindow skeleton = EditorSkeletonWindow.show(primaryStage, launchName,
					preloadedPoster);
			Startup.log("window shown");
			if (preloadedPoster != null) {
				Startup.log("first preview frame");
			}
			if (preloadedPoster == null) {
				Thread.ofVirtual().name("gravaai-editor-poster").start(() -> {
					Path poster = ProjectPoster.extractFrame(launchPath);
					if (poster != null) {
						Platform.runLater(() -> {
							skeleton.setPoster(new javafx.scene.image.Image(poster.toUri().toString()));
							Startup.log("first preview frame");
						});
					}
				});
			}
			// The skeleton is visible and the budget lines are logged; load the UI fonts now, before
			// the full editor shell is built (Application.init skips this in the editor fast path).
			Main.ensureFontsLoaded();
			// The HUD stays hidden, but the recording services attach to it: without it the
			// dashboard's Record button and the record shortcut have nothing to drive.
			startHudWindow(mainWindows);
			Platform.runLater(() -> {
			try {
				openEditorStartup(launchPath);
			} finally {
				skeleton.close();
			}
		});
			Thread.ofVirtual().name("gravaai-editor-post").start(() -> {
				startBackgroundServices();
				initializeRecordingServices();
				initializeMeetings();
				initializeAgent();
				initializeExport();
				RecordingWiring wiring = recordingWiring;
				if (wiring != null) {
					try {
						wiring.start();
					} catch (RuntimeException error) {
						LOG.log(System.Logger.Level.WARNING, "recording wiring init failed: " + error);
					}
				}
				Platform.runLater(() -> {
					logEnvironment();
					scheduleExitIfRequested();
					startShortcuts();
					startTray();
					if (pendingAction != null && dispatcher != null) {
						dispatcher.dispatch(pendingAction);
					}
				});
			});
			return;
		}
		// The recording services (and the HUD that talks to them) are on the startup path only as
		// far as the HUD needs them: creation is cheap, device enumeration and the D-Bus/automation
		// services run after the window is visible (startup budget, plan §5.1).
		startHudWindow(mainWindows);
		boolean dashboardMode = Boolean.getBoolean("gravaai.dashboard");
		boolean uiKitMode = Boolean.getBoolean("gravaai.uiKit");
		if (dashboardMode || uiKitMode) {
			startBackgroundServices();
			initializeRecordingServices();
			initializeMeetings();
			initializeAgent();
			initializeExport();
		}
		if (dashboardMode) {
			openDashboard();
		} else if (uiKitMode) {
			mainWindows.showMainWindow();
		} else {
			showHud();
		}
		// The startup budget is about the first window being visible (plan §5.1): the HUD is the P1
		// startup window, so this line comes right after hud.show(); focus/toFront/keep-above work
		// happens after it and must not be counted in the budget.
		Startup.log("window shown");
		openEditorFromEnvironment();
		if (!dashboardMode && !uiKitMode) {
			bringHudToFront();
		}
		if (dashboardMode || uiKitMode) {
			logEnvironment();
			scheduleExitIfRequested();
			startShortcuts();
			startTray();
			if (pendingAction != null && dispatcher != null) {
				dispatcher.dispatch(pendingAction);
			}
		} else {
			// Everything that does not need to delay the visible HUD runs off the FX thread.
			Thread.ofVirtual().name("gravaai-post-window").start(() -> {
				startBackgroundServices();
				initializeRecordingServices();
				initializeMeetings();
				initializeAgent();
				initializeExport();
				RecordingWiring wiring = recordingWiring;
				if (wiring != null) {
					try {
						wiring.start();
					} catch (RuntimeException error) {
						LOG.log(System.Logger.Level.WARNING, "recording wiring init failed: " + error);
					}
				}
				Platform.runLater(() -> {
					logEnvironment();
					scheduleExitIfRequested();
					startShortcuts();
					startTray();
					if (pendingAction != null && dispatcher != null) {
						dispatcher.dispatch(pendingAction);
					}
				});
			});
		}
	}

	/**
	 * D-Bus, portals, notifications and automations (plan §5.5/§5.15). They are not needed to show
	 * the HUD, so in the normal startup they run on a virtual thread after the window is visible;
	 * the dashboard/ui-kit dev modes call this synchronously before showing their window.
	 */
	private synchronized void startBackgroundServices() {
		if (backgroundServicesStarted || shutdown) {
			return;
		}
		backgroundServicesStarted = true;
		initializeExtensions();
		desktopServices.connect();
		desktopServices.startAutomations(userData);
	}

	/**
	 * Builds the HUD window with an idle delegating recording port (startup path: no services yet).
	 * Nothing is shown here and no capture starts: the HUD/shortcut/tray flows drive the recording
	 * service once {@link #initializeRecordingServices()} attaches the real port.
	 */
	private void startHudWindow(Windows mainWindows) {
		hudController = new HudWindowController();
		lazyHudPort = new LazyHudRecordingPort();
		lazyHudPort.setOnHudReady(() -> {
			if (dispatcher != null) {
				dispatcher.hudReady();
			}
		});
		try {
			hudWindow = new HudOverlayWindow(lazyHudPort);
			hudController.attach(hudWindow);
			mainWindows.setHud(hudWindow);
			LOG.log(System.Logger.Level.INFO, "HUD window constructed (recording services pending)");
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "HUD window unavailable: " + error);
			hudWindow = null;
			lazyHudPort = null;
			hudController = null;
		}
	}

	/**
	 * Creates the recording services and the real HUD recording port, then switches the visible HUD
	 * from its idle delegate to the real one on the FX thread (queued HUD actions replay there).
	 * Runs on the post-window thread in the normal HUD startup, so the wiring construction never
	 * delays the window-visible budget; dev/dashboard modes call it synchronously.
	 */
	private void initializeRecordingServices() {
		if (recordingWiring != null || hudController == null || lazyHudPort == null) {
			return;
		}
		// The dispatcher must exist before the real port is attached: the HUD's ready callback
		// drains the global-shortcut queue through it.
		createShortcuts();
		AppSettingsStore settings = appSettings();
		try {
			recordingWiring = RecordingWiring.create(userData, settings, hudController);
			recordingWiring.onRecordingSaved(this::onRecordingSaved);
			hudPort = HudRecordingPortAdapter.create(
					recordingWiring,
					userData,
					settings,
					new HudRecordingPortAdapter.Navigation() {
						@Override
						public void openDashboard() {
							AppContext.this.openDashboard();
						}

						@Override
						public void requestSourcePick() {
							HudRecordingPortAdapter port = hudPort;
							if (port != null) {
								port.requestSourcePick();
							}
						}

						@Override
						public void hideHud() {
							AppContext.this.hideHud();
						}

						@Override
						public void recordingFinalizing(boolean finalizing) {
							Platform.runLater(() -> onRecordingFinalizing(finalizing));
						}

						@Override
						public void openMeeting(String mediaPath) {
							if (mediaPath != null && !mediaPath.isBlank()) {
								Platform.runLater(() -> openMeetingWindow(Path.of(mediaPath)));
							}
						}

						@Override
						public void openRecordingInEditor(String videoPath) {
							if (videoPath != null && !videoPath.isBlank()) {
								openProjectInEditor(Path.of(videoPath));
							}
						}

						@Override
						public void showRecordingError(String message) {
							// The adapter translates on its (virtual) thread; the hint belongs to FX.
							Platform.runLater(() -> {
								HudOverlayWindow hud = hudWindow;
								if (hud != null) {
									hud.showHint(message, true);
								}
							});
						}
					});
			lazyHudPort.attach(hudPort);
			LOG.log(System.Logger.Level.INFO, "recording services ready");
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "recording services unavailable: " + error);
			recordingWiring = null;
			hudPort = null;
		}
	}

	/**
	 * Creates the meeting service once the recording wiring, D-Bus portal/file manager and the
	 * automation service exist (plan P1.11). Cheap: no model download, no keyring access; jobs run
	 * later on the service's own executor.
	 */
	private void initializeMeetings() {
		if (meeting != null || recordingWiring == null) {
			return;
		}
		try {
			meeting = MeetingIntegration.create(userData, appSettings(), recordingWiring,
					desktopServices.fileManager(), desktopServices.automations());
			meeting.service().onOpenRequested(() -> Platform.runLater(this::openMeetingWindow));
			LOG.log(System.Logger.Level.INFO, "meeting services ready");
			String autoOpen = System.getenv("GRAVAAI_MEETING_OPEN");
			if (autoOpen != null && !autoOpen.isBlank()) {
				Platform.runLater(() -> openMeetingWindow(Path.of(autoOpen)));
			}
			if (envFlag("GRAVAAI_MEETING_LIBRARY")) {
				MeetingHosts.openLibrary(meeting.service(),
						mediaPath -> Platform.runLater(() -> openMeetingWindow(mediaPath)));
			}
			if (envFlag("GRAVAAI_MEETING_SETTINGS")) {
				MeetingHosts.openSettings(meeting.service());
			}
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "meeting services unavailable: " + error);
			meeting = null;
		}
	}

	private void initializeAgent() {
		if (agentWiring != null) {
			return;
		}
		try {
			AgentWiring wiring = AgentWiring.create(userData, appSettings(),
					this::activeEditorSession, () -> projects,
					() -> meeting == null ? null : meeting.service(),
					this::exporter, path -> openProjectInEditor(path),
					() -> Platform.runLater(this::openDashboard));
			agentWiring = wiring;
			for (EditorWindow window : List.copyOf(editorWindows)) {
				window.setAgentUiService(wiring.ui());
			}
			LOG.log(System.Logger.Level.INFO,
					"AI control wired: " + wiring.service().snapshot().status());
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "AI control unavailable: " + error);
			agentWiring = null;
		}
	}

	private void initializeExport() {
		AutomationService automations = desktopServices.automations();
		if (exporter != null || automations == null) {
			return;
		}
		FileManager manager = desktopServices.fileManager();
		ExportPublisher publisher = new ExportPublisher(manager != null ? manager::reveal : null);
		exportCoordinator = new ExportCoordinator(publisher, automations.triggers());
		EditorPreferencesStore preferencesStore = new EditorPreferencesStore(appSettings());
		exporter = new ProjectExporter(exportCoordinator, publisher, preferencesStore);
		exporter.setExtensionHost(extensionHost());
	}

	/**
	 * Discovers and activates the user-installed extensions ({@code userData/extensions}, plan
	 * §5.18). Never throws: a broken extension directory disables only extensions, never the app.
	 */
	private synchronized void initializeExtensions() {
		extensionHost();
	}

	/** The process-wide extension host, created (and activated) on first use. */
	private synchronized ExtensionHost extensionHost() {
		ExtensionHost current = extensionHost;
		if (current != null) {
			return current;
		}
		if (shutdown) {
			throw new IllegalStateException("the application is shutting down");
		}
		ExtensionHost host = new ExtensionHost(userData.extensionsDir());
		try {
			var extensions = host.enableAll();
			LOG.log(System.Logger.Level.INFO,
					"extensions: " + host.activeCount() + " active of " + extensions.size()
							+ " in " + host.extensionsDir());
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING,
					"extensions unavailable (" + host.extensionsDir() + "): " + error);
		}
		extensionHost = host;
		return host;
	}

	/**
	 * Extension render hooks for one editor's preview, bound to that project's timeline duration
	 * (plan §5.18). Dispatched from the compositor on the preview GL thread.
	 */
	private dev.gravaai.media.compose.FrameOverlayHooks previewOverlayHooks(EditorSession session) {
		ExtensionFrameOverlayHooks extensions = new ExtensionFrameOverlayHooks(extensionHost(),
				() -> session.timelineDataSource().totalDurationMs());
		dev.gravaai.services.elements.ElementOverlayHooks hooks =
				new dev.gravaai.services.elements.ElementOverlayHooks(extensions,
						session.elementDiagnostics(), () -> session.store().state());
		session.own(hooks);
		return hooks;
	}

	private @Nullable ProjectExporter exporter() {
		return exporter;
	}

	/** Headless acceptance hook: GRAVAAI_EXPORT_TO=/path/out.mp4 exports on open. */
	private void runEnvExport(EditorWindow window) {
		String target = System.getenv("GRAVAAI_EXPORT_TO");
		if (target == null || target.isBlank()) {
			return;
		}
		initializeExport();
		ProjectExporter current = exporter;
		if (current == null) {
			LOG.log(System.Logger.Level.WARNING, "export requested but the pipeline is unavailable");
			return;
		}
		Path destination = Path.of(target).toAbsolutePath().normalize();
		String format = destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
				.endsWith(".gif") ? "gif" : "mp4";
		EditorSession session = window.session();
		Thread.ofVirtual().name("gravaai-env-export").start(() -> {
			long started = System.currentTimeMillis();
			try {
				var outcome = current.runTo(session, format, destination);
				if (outcome == null) {
					Startup.log("export finished without a result");
					return;
				}
				Startup.log("export done: " + outcome.path() + " bytes=" + outcome.bytes()
						+ " frames=" + outcome.frames() + " in "
						+ (System.currentTimeMillis() - started) + " ms");
			} catch (Exception error) {
				Startup.log("export failed: " + error);
			}
		});
	}

	/** Releases the export pipeline (app shutdown only; navigation keeps it for the next editor). */
	private void closeExportPipeline() {
		ExportCoordinator coordinator = exportCoordinator;
		if (coordinator != null) {
			try {
				coordinator.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "export shutdown failed: " + error);
			}
			exportCoordinator = null;
			exporter = null;
		}
	}

	private @Nullable EditorSession activeEditorSession() {
		for (int index = editorWindows.size() - 1; index >= 0; index--) {
			EditorWindow window = editorWindows.get(index);
			if (window.stage().isShowing()) {
				return window.session();
			}
		}
		return null;
	}

	/** Auto-captions for one editor window (useAutoCaptionController). */
	private void attachCaptions(EditorWindow window) {
		try {
			EditorSession session = window.session();
			var generator = new dev.gravaai.services.captions.CaptionGenerator(
					dev.gravaai.app.meeting.MeetingIntegration.processRunner(), ffmpegBinary(),
					Path.of(System.getProperty("java.io.tmpdir"), "gravaai-captions"));
			window.setCaptionController(new dev.gravaai.app.editor.AutoCaptionController(
					session.store(), session::videoPath, session::webcamPath,
					new dev.gravaai.core.editor.EditorPreferencesStore(appSettings()),
					new dev.gravaai.services.captions.WhisperModels(userData,
							new dev.gravaai.services.meeting.HttpFileDownloader()),
					dev.gravaai.services.captions.AutoCaptionService.withDaemonThread(generator),
					dev.gravaai.app.meeting.MeetingIntegration.whisperCliPath(),
					() -> window.scene().getRoot()));
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "auto-captions unavailable: " + error);
		}
	}

	/** Auto masking for one editor window: PII takes PII model download and the detection pass. */
	private void attachAutoMask(EditorWindow window) {
		try {
			EditorSession session = window.session();
			var assets = new dev.gravaai.services.privacy.PrivacyAssets(userData.dir());
			var installer = new dev.gravaai.services.privacy.PrivacyAssetInstaller(
					new dev.gravaai.services.meeting.HttpFileDownloader(), assets);
			var service = dev.gravaai.services.privacy.AutoMaskService.withRealDetectors(
					assets.glinerModelDir());
			window.setAutoMaskController(new dev.gravaai.app.editor.AutoMaskController(
					session.store(), session::videoPath, session::durationSeconds,
					() -> window.scene().getRoot(), assets, installer, service));
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "auto masking unavailable: " + error);
		}
	}

	private void attachAgent(EditorWindow window) {
		AgentWiring wiring = agentWiring;
		if (wiring == null) {
			return;
		}
		window.setAgentUiService(wiring.ui());
		wiring.service().editorReady(true);
	}

	private void onEditorWindowClosed() {
		editorWindows.removeIf(window -> !window.stage().isShowing());
		if (switchingViews) {
			// Navigation inside the single main window: keep the app services alive.
			return;
		}
		activeEditor = null;
		AgentWiring wiring = agentWiring;
		if (wiring != null) {
			wiring.service().editorReady(editorWindows.stream()
					.anyMatch(window -> window.stage().isShowing()));
		}
	}

	/** The meetings service (null until {@link #initializeMeetings()} ran, e.g. before the HUD is up). */
	public @Nullable MeetingIntegration meetingIntegration() {
		return meeting;
	}

	/** Opens the meeting window (null path keeps whatever meeting the view already shows). */
	private void openMeetingWindow() {
		openMeetingWindow(null);
	}

	private void openMeetingWindow(@Nullable Path mediaPath) {
		EditorWindow editor = activeEditor;
		if (editor != null) {
			switchingViews = true;
			editor.requestHostClose(() -> {
				try {
					showMeetingInMainWindow(mediaPath);
				} finally {
					switchingViews = false;
				}
			}, () -> switchingViews = false);
			return;
		}
		showMeetingInMainWindow(mediaPath);
	}

	/** Hosts the meeting view in the main window (single-window app; plan §5.1). */
	private void showMeetingInMainWindow(@Nullable Path mediaPath) {
		MeetingIntegration integration = meeting;
		Stage stage = primaryStage;
		if (shutdown || integration == null || stage == null) {
			return;
		}
		try {
			finalizingSceneShown = false;
			if (meetingWindow == null) {
				meetingWindow = MeetingWindow.createIn(stage, integration.service(),
						this::closeMeetingWindow);
			}
			meetingWindow.open(mediaPath);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "meeting window unavailable: " + error);
			meetingWindow = null;
		}
	}

	private static boolean envFlag(String name) {
		String value = System.getenv(name);
		return "1".equals(value) || "true".equalsIgnoreCase(value);
	}

	private void closeMeetingWindow() {
		MeetingWindow window = meetingWindow;
		meetingWindow = null;
		if (window != null && window.isHosted()) {
			// Back from the meeting view: restore the dashboard in the same main window.
			window.dispose();
			showDashboardScene();
		} else if (window != null) {
			window.close();
		}
	}

	/** {@code fireRecordingSaved}: automations for every saved recording (plan P1.13). */
	private void onRecordingSaved(StopResult result) {
		AutomationService automations = desktopServices.automations();
		if (automations == null || result == null || !result.success() || result.path() == null) {
			return;
		}
		try {
			automations.fireRecordingSaved(new AutomationTriggers.RecordingSavedResult(
					true, result.path(), result.meeting() != null));
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "recording-saved automation failed: " + error);
		}
		MeetingIntegration integration = meeting;
		if (result.meeting() != null && integration != null) {
			try {
				integration.service().registerRecording(Path.of(result.path()),
						result.meeting().hasVideo());
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "register meeting recording failed: " + error);
			}
		}
	}

	/** Plan §1.1.3 shutdown order: recording first, then jobs, sockets, GL threads, then the UI. */
	public void shutdown() {
		if (shutdown) {
			return;
		}
		shutdown = true;
		// Stop tray updates before the services they read are torn down.
		desktopServices.close();
		if (recordingWiring != null) {
			try {
				recordingWiring.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "recording wiring shutdown failed: " + error);
			}
			recordingWiring = null;
		}
		if (meetingWindow != null) {
			try {
				meetingWindow.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "meeting window shutdown failed: " + error);
			}
			meetingWindow = null;
		}
		if (meeting != null) {
			try {
				meeting.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "meeting shutdown failed: " + error);
			}
			meeting = null;
		}
		if (lazyHudPort != null) {
			try {
				lazyHudPort.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "HUD port shutdown failed: " + error);
			}
			lazyHudPort = null;
		}
		hudPort = null;
		if (hudWindow != null) {
			hudWindow.dispose();
			hudWindow = null;
		}
		hudController = null;
		if (shortcutsService != null) {
			shortcutsService.close();
		}
		desktopServices.close();
		if (extensionHost != null) {
			try {
				extensionHost.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "extension host shutdown failed: " + error);
			}
			extensionHost = null;
		}
		for (EditorWindow window : List.copyOf(editorWindows)) {
			try {
				// Quitting must not drop the debounced autosave: write what is pending first.
				window.flushAutosave();
				if (window.isHosted()) {
					window.dispose();
				} else {
					window.stage().close();
				}
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "editor window shutdown failed: " + error);
			}
		}
		editorWindows.clear();
		closeExportPipeline();
		if (dashboardView != null) {
			try {
				dashboardView.dispose();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "dashboard shutdown failed: " + error);
			}
			dashboardView = null;
		}
		AgentWiring wiring = agentWiring;
		if (wiring != null) {
			try {
				wiring.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "AI control shutdown failed: " + error);
			}
			agentWiring = null;
		}
		if (windows != null) {
			windows.shutdown();
		}
		LOG.log(System.Logger.Level.INFO, "shutdown");
	}

	public UserData userData() {
		return userData;
	}

	public Windows windows() {
		return Objects.requireNonNull(windows, "the main windows exist once AppContext.start() ran");
	}

	// ── HUD ────────────────────────────────────────────────────────────────

	/** Tray/shortcut/dashboard "Show Overlay": show the HUD window (the startup window). */
	private void showHud() {
		HudOverlayWindow hud = hudWindow;
		if (hud == null) {
			LOG.log(System.Logger.Level.WARNING, "HUD requested but it was never built");
			return;
		}
		LOG.log(System.Logger.Level.INFO, "HUD shown");
		hud.show();
		bringHudToFront();
	}

	/**
	 * A second launch of the app was refused (single instance): raise the main window when it is
	 * open, otherwise show the HUD like a fresh launch would.
	 */
	private void bringToFrontForSecondLaunch() {
		LOG.log(System.Logger.Level.INFO, "second launch: bringing Gravaai to the front");
		if (primaryStage != null && primaryStage.isShowing()) {
			primaryStage.setIconified(false);
			primaryStage.toFront();
			primaryStage.requestFocus();
			return;
		}
		showHud();
	}

	/** Focus/toFront the HUD; kept off the measured window-shown path. */
	private void bringHudToFront() {
		HudOverlayWindow hud = hudWindow;
		if (hud == null) {
			return;
		}
		hud.stage().toFront();
		hud.stage().requestFocus();
	}

	private void hideHud() {
		HudOverlayWindow hud = hudWindow;
		if (hud != null && hud.stage().isShowing()) {
			hud.stage().hide();
		}
	}

	private boolean finalizingSceneShown;

	/**
	 * Stop was pressed: the tray reverts to idle at once and the main window shows a busy
	 * placeholder while the take is written; the editor (or the dashboard on failure) replaces it.
	 */
	private void onRecordingFinalizing(boolean finalizing) {
		if (finalizing) {
			desktopServices.applyTrayRecordingState(false, "");
			Stage stage = primaryStage;
			if (stage == null || shutdown || activeEditor != null) {
				return;
			}
			javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
			javafx.scene.control.Label label = new javafx.scene.control.Label(
					dev.gravaai.ui.i18n.UiI18n.t("launch:recording.finalizingWindow"));
			label.setStyle("-fx-text-fill: white; -fx-font-size: 15px;");
			javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(16, spinner, label);
			box.setAlignment(javafx.geometry.Pos.CENTER);
			box.setStyle("-fx-background-color: #14141a;");
			StageScenes.setSceneKeepingSize(stage, new Scene(box));
			finalizingSceneShown = true;
			if (!stage.isShowing()) {
				stage.show();
			}
			stage.setIconified(false);
			stage.toFront();
			return;
		}
		if (finalizingSceneShown && activeEditor == null) {
			finalizingSceneShown = false;
			showDashboardScene();
		}
	}

	private void stopRecordingFromTray() {
		RecordingWiring wiring = recordingWiring;
		if (wiring == null) {
			LOG.log(System.Logger.Level.INFO, "stop recording from tray: recording service unavailable");
			return;
		}
		LOG.log(System.Logger.Level.INFO, "stop recording from tray");
		HudRecordingPortAdapter port = hudPort;
		if (port != null) {
			port.stopRecordingFromTray();
			return;
		}
		StopResult result = wiring.recording().stopNativeScreenRecording();
		wiring.recording().setRecordingState(false);
		if (!result.success()) {
			LOG.log(System.Logger.Level.WARNING, "tray stop failed: " + result.error());
		}
	}

	// ── Dashboard ──────────────────────────────────────────────────────────

	/**
	 * Opens (or re-focuses) the dashboard on the primary stage. Built lazily so the HUD can be the
	 * startup window and the dashboard can still be opened later from the HUD/tray (P1.10).
	 */
	private void openDashboard() {
		LOG.log(System.Logger.Level.INFO, "open dashboard requested");
		EditorWindow editor = activeEditor;
		if (editor != null) {
			revealMainWindow();
			// Single window: going Home closes the editor inside the same window.
			switchingViews = true;
			editor.requestHostClose(() -> {
				try {
					showDashboardScene();
				} finally {
					switchingViews = false;
				}
			}, () -> switchingViews = false);
			return;
		}
		MeetingWindow meeting = meetingWindow;
		if (meeting != null) {
			meetingWindow = null;
			meeting.dispose();
			showDashboardScene();
			return;
		}
		if (dashboardView != null) {
			if (primaryStage != null && !primaryStage.isShowing()) {
				primaryStage.show();
			}
			if (primaryStage != null) {
				primaryStage.setIconified(false);
				primaryStage.toFront();
				primaryStage.requestFocus();
			}
			dashboardView.refresh();
			return;
		}
		buildDashboard();
	}

	/** Restores the dashboard as the main window content after an editor was hosted there. */
	private void showDashboardScene() {
		activeEditor = null;
		if (primaryStage == null) {
			return;
		}
		if (dashboardScene == null) {
			// Launched straight into a project: the dashboard was never built.
			buildDashboard();
			return;
		}
		primaryStage.setTitle("Gravaai");
		primaryStage.setMinWidth(900);
		primaryStage.setMinHeight(600);
		StageScenes.setSceneKeepingSize(primaryStage, dashboardScene);
		if (!primaryStage.isShowing()) {
			primaryStage.show();
		}
		primaryStage.setIconified(false);
		primaryStage.toFront();
		primaryStage.requestFocus();
		if (dashboardView != null) {
			dashboardView.refresh();
		}
	}

	/**
	 * A language change from the Settings picker: rebuild the dashboard scene (every string on it
	 * is read at build time) and re-render the HUD bar. Deferred one pulse so the picker's click
	 * handler finishes before its scene is swapped out.
	 */
	private void onLocaleChanged() {
		DashboardView current = dashboardView;
		if (current != null) {
			Platform.runLater(() -> {
				if (dashboardView != current || shutdown) {
					return;
				}
				String settingsCategory = current.activeSettingsCategory();
				boolean inSettings = current.model().section() instanceof DashboardSection.Settings;
				current.flushPendingEdits();
				current.dispose();
				dashboardView = null;
				dashboardScene = null;
				if (activeEditor == null) {
					buildDashboard();
					if (inSettings && dashboardView != null) {
						dashboardView.showSettingsSection(settingsCategory);
					}
				}
				// While an editor hosts the window, the dashboard is rebuilt lazily: the next
				// Home press goes through showDashboardScene, which rebuilds a null scene.
			});
		}
		HudOverlayWindow hud = hudWindow;
		if (hud != null) {
			Platform.runLater(hud::retranslate);
		}
	}

	private void buildDashboard() {
		if (primaryStage == null) {
			return;
		}
		try {
			ProjectManager projectManager = ensureProjects();
			if (projectManager == null) {
				throw new IllegalStateException("project library could not be created");
			}
			// The recording service owns the library listing and the recoverable banner (Appendix A);
			// the dashboard consumes them through these read ports.
			RecordingsReadModel recordings = includeSources -> {
				RecordingWiring wiring = recordingWiring;
				if (wiring == null) {
					return List.of();
				}
				return wiring.recording().listRecordings(includeSources).stream()
						.map(entry -> new RecordingEntry(
								entry.path(), entry.name(), entry.createdAt(), entry.bytes()))
						.toList();
			};
			RecoverableRecordingsProvider recoverable = () -> {
				RecordingWiring wiring = recordingWiring;
				if (wiring == null) {
					return List.of();
				}
				return wiring.recording().listRecoverableRecordings().stream()
						.map(item -> new RecoverableRecording(
								item.id(),
								item.manifestPath().toString(),
								item.createdAt(),
								item.hasVideo(),
								item.meeting(),
								item.bytes()))
						.toList();
			};
			DashboardLibraryStore preferences = new DashboardLibraryStore(userData);
			DashboardDependencies.Actions actions = new DashboardDependencies.Actions() {
				@Override
				public void openProject(String path) {
					openProjectInEditor(Path.of(path));
				}

				@Override
				public void openRecording(String path) {
					openProjectInEditor(Path.of(path));
				}

				@Override
				public void openMeeting(String path) {
					// Single window: the meeting view replaces the dashboard content in place.
					openMeetingWindow(path == null ? null : Path.of(path));
				}

				@Override
				public void importMeeting() {
					Portal chooser = desktopServices.portal();
					MeetingIntegration current = meeting;
					if (chooser == null || current == null) {
						LOG.log(System.Logger.Level.WARNING,
								"import meeting: meetings service unavailable");
						return;
					}
					Thread.ofVirtual().name("dashboard-meeting-import").start(() -> {
						try {
							chooser.openFile(Portal.FileDialogRequest.open("Import a recording"))
									.ifPresent(path -> {
										var result = current.service().importMeeting(path);
										if (result.isOk()) {
											Path imported = result.orElse(path);
											Platform.runLater(() -> openMeetingWindow(imported));
										}
									});
						} catch (IOException error) {
							LOG.log(System.Logger.Level.WARNING, "import meeting failed: " + error);
						}
					});
				}

				@Override
				public void importFile() {
					Portal chooser = desktopServices.portal();
					if (chooser == null) {
						LOG.log(System.Logger.Level.WARNING, "import file: no xdg-desktop-portal available");
						return;
					}
					Thread.ofVirtual().name("dashboard-import").start(() -> {
						try {
					chooser.openFile(Portal.FileDialogRequest.open("Import a recording"))
							.ifPresent(path -> Platform.runLater(() -> {
								if (StorageFiles.hasProjectFileExtension(path)) {
									// A picked project file opens in the editor; it is not a
									// recording to list on the dashboard.
									openProjectInEditor(path);
								} else if (dashboardView != null) {
									dashboardView.refresh();
								}
							}));
						} catch (IOException error) {
							LOG.log(System.Logger.Level.WARNING, "import file failed: " + error);
						}
					});
				}

				@Override
				public void revealInFolder(String path) {
					FileManager manager = desktopServices.fileManager();
					if (manager == null) {
						LOG.log(System.Logger.Level.WARNING, "reveal in folder: FileManager1 unavailable for " + path);
						return;
					}
					Thread.ofVirtual().name("dashboard-reveal").start(() -> manager.reveal(Path.of(path)));
				}

				@Override
				public void showRecordingHud() {
					showHud();
				}

				@Override
				public java.util.concurrent.CompletableFuture<String> recordingThumbnail(String videoPath) {
					RecordingWiring wiring = recordingWiring;
					if (wiring == null) {
						return java.util.concurrent.CompletableFuture.completedFuture(null);
					}
					return wiring.thumbnails().getRecordingThumbnail(videoPath);
				}

				@Override
				public void openExternalUrl(String url) {
					Portal opener = desktopServices.portal();
					if (opener == null) {
						LOG.log(System.Logger.Level.WARNING, "no portal to open " + url);
						return;
					}
					try {
						opener.openUri(URI.create(url));
					} catch (RuntimeException error) {
						LOG.log(System.Logger.Level.WARNING, "could not open " + url + ": " + error);
					}
				}

				@Override
				public void recoverRecording(RecoverableRecording recording) {
					RecordingWiring wiring = recordingWiring;
					if (wiring == null) {
						return;
					}
					Thread.ofVirtual().name("dashboard-recover").start(() -> {
						var result = wiring.recording().recoverRecording(recording.id());
						LOG.log(System.Logger.Level.INFO, "recover recording " + recording.id() + ": "
								+ (result.success() ? result.path() : result.error()));
						Platform.runLater(() -> {
							if (dashboardView != null) {
								dashboardView.refresh();
							}
						});
					});
				}

				@Override
				public void discardRecording(RecoverableRecording recording) {
					RecordingWiring wiring = recordingWiring;
					if (wiring == null) {
						return;
					}
					Thread.ofVirtual().name("dashboard-discard").start(() -> {
						wiring.recording().discardRecoverableRecording(recording.id());
						Platform.runLater(() -> {
							if (dashboardView != null) {
								dashboardView.refresh();
							}
						});
					});
				}
			};
			AutomationService automations = desktopServices.automations();
			DashboardDependencies dependencies = new DashboardDependencies(
					userData, projectManager, recordings, recoverable, preferences, Trash.defaultTrash(), actions,
					new ServiceGlobalShortcutsController(requireShortcutsService()),
					new PlaceholderAgentSettingsController(AppLaunchPath.current()),
					automations == null
							? null
							: new ServiceAutomationsController(automations, desktopServices.portal()),
					agentWiring == null ? null : agentWiring.ui(),
					() -> meeting == null ? null
							: new dev.gravaai.ui.meeting.ServiceMeetingUiService(
									meeting.service()));
			DashboardView view = new DashboardView(dependencies);
			// Settings -> Recording: key-press capture and mic noise reduction, read by the
			// recorder at each recording start.
			view.addSettingsSection("recording", new dev.gravaai.ui.dashboard.RecordingSettingsView(
					new AppSettingsStore(userData.appSettingsFile())));
			// Settings -> General / Motion: the editor defaults new projects start from.
			EditorPreferencesStore editorDefaults =
					new EditorPreferencesStore(new AppSettingsStore(userData.appSettingsFile()));
			view.addSettingsSection("general",
					dev.gravaai.ui.dashboard.EditorDefaultsSettingsView.general(editorDefaults));
			view.addSettingsSection("motion",
					dev.gravaai.ui.dashboard.EditorDefaultsSettingsView.motion(editorDefaults));
			dashboardView = view;
			Scene scene = new Scene(new KitRoot(view.ui()), 1280, 800);
			Theme.install(scene);
			DashboardView.installStyles(scene);
			dashboardScene = scene;
			primaryStage.setTitle("Gravaai");
			primaryStage.setMinWidth(900);
			primaryStage.setMinHeight(600);
			StageScenes.setSceneKeepingSize(primaryStage, scene);
			primaryStage.show();
			view.refresh();
			LOG.log(System.Logger.Level.INFO, "dashboard shown");
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "dashboard unavailable, falling back to the main window: " + error);
			Windows mainWindows = windows;
			if (mainWindows != null) {
				mainWindows.showMainWindow();
			}
		}
	}

	// ── Editor (P2.10) ─────────────────────────────────────────────────────

	/** Synchronous startup-window path (P2.11): build/show the editor before the gate log. */
	private void openEditorStartup(Path projectPath) {
	try {
		Path path = projectPath.toAbsolutePath().normalize();
		openEditorInMainWindow(path, true);
		EditorWindow window = activeEditor;
		if (window != null) {
			runEnvExport(window);
		}
	} catch (RuntimeException error) {
		LOG.log(System.Logger.Level.WARNING, "could not open startup editor: " + error);
	}
	}

	private void openProjectInEditor(Path projectPath) {
		if (projectPath == null) {
			return;
		}
		Platform.runLater(() -> {
			Path path = projectPath.toAbsolutePath().normalize();
			Runnable open;
			if (isProjectFile(path)) {
				open = () -> openEditorInMainWindow(path, false);
			} else if (java.nio.file.Files.isRegularFile(path)) {
				// A recording (raw card, the recorder's stop, agent "project new"): a new unsaved
				// project over the video, with the webcam track linked by its session manifest.
				open = () -> openRecordingInMainWindow(path);
			} else {
				LOG.log(System.Logger.Level.WARNING, "open in editor: file not found: " + path);
				return;
			}
			EditorWindow current = activeEditor;
			MeetingWindow meeting = meetingWindow;
			if (meeting != null) {
				// Leaving the meeting view to edit a project: same window, no second stage.
				meetingWindow = null;
				meeting.dispose();
			}
			if (current != null) {
				// Single window: the open project replaces the editor content in the main window.
				switchingViews = true;
				current.requestHostClose(() -> {
					try {
						open.run();
					} finally {
						switchingViews = false;
					}
				}, () -> switchingViews = false);
			} else {
				open.run();
			}
		});
	}

	/**
	 * Builds the editor as the main window content (single-window app, plan §5.1). The dashboard
	 * scene stays alive and is restored by {@link #showDashboardScene()}.
	 */
	private void openEditorInMainWindow(Path path, boolean startup) {
		Stage stage = primaryStage;
		if (stage == null) {
			LOG.log(System.Logger.Level.WARNING, "main window not ready; cannot open the editor");
			return;
		}
		try {
			EditorWindow window = EditorWindow.createIn(stage, path, editorServices());
			hostEditor(window, path, startup);
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "could not open project in editor: " + error);
			switchingViews = false;
		}
	}

	/** A recording as a new, unsaved project (React switchToEditor after stop). */
	private void openRecordingInMainWindow(Path videoPath) {
		Stage stage = primaryStage;
		if (stage == null) {
			LOG.log(System.Logger.Level.WARNING, "main window not ready; cannot open the recording");
			return;
		}
		try {
			dev.gravaai.services.recording.RecordingSessionManifest.RecordingSessionData linked =
					dev.gravaai.services.recording.RecordingSessionManifest
							.resolveRecordingSession(videoPath.toString());
			String webcam = linked == null ? null : linked.webcamPath();
			long offset = linked == null ? 0 : linked.timeOffsetMs();
			EditorWindow window = EditorWindow.createForRecordingIn(stage, videoPath, webcam,
					offset, editorServices());
			hostEditor(window, null, false);
			LOG.log(System.Logger.Level.INFO, "recording opened in the editor: " + videoPath
					+ (webcam == null ? "" : " (webcam " + webcam + ", offset " + offset + " ms)"));
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "could not open recording in editor: " + error);
			switchingViews = false;
		}
	}

	private EditorWindow.Services editorServices() {
		if (appSettings == null) {
			appSettings = new AppSettingsStore(userData.appSettingsFile());
		}
		// The editor saves/renames through the project manager even when the session never
		// opens the dashboard (ensureProjects creates it lazily in that case).
		ensureProjects();
		return new EditorWindow.Services(
				projects,
				appSettings,
				this::openProjectInEditor,
				this::rememberProject,
				this::onEditorWindowClosed,
				null,
				null,
				this::exporter,
				this::recordingService,
				this::openRecordingsFolder);
	}

	/** Shows {@code window} as the main window content; {@code path} is null for an unsaved recording. */
	private void hostEditor(EditorWindow window, @Nullable Path path, boolean startup) {
		window.setOnGoHome(() -> javafx.application.Platform.runLater(this::openDashboard));
		finalizingSceneShown = false;
		activeEditor = window;
		editorWindows.add(window);
		window.show();
		window.setOverlayHooks(previewOverlayHooks(window.session()));
		// The preview is a real-time path: keep inference off the playback render thread so a slow
		// segmenter cannot starve the audio pump. The preview runs the fast model while the timeline
		// plays and the precise one once it stops (or always, when the project asks for it); export
		// and agent renders are synchronous and always precise.
		window.setWebcamSegmenter(new dev.gravaai.media.segmentation.AsyncWebcamSegmenter(
				dev.gravaai.media.segmentation.DualPathWebcamSegmenter.preview()));
		attachAgent(window);
		attachCaptions(window);
		attachAutoMask(window);
		if (path != null) {
			rememberProject(path);
		}
		if (projects != null) {
			projects.setCurrentProjectPath(path);
		}
		if (!startup && dashboardView != null) {
			dashboardView.refresh();
		}
	}

	/** The recording service for the editor's Clips panel ({@code null} before recording init). */
	private @Nullable RecordingService recordingService() {
		RecordingWiring wiring = recordingWiring;
		return wiring == null ? null : wiring.recording();
	}

	/** Reveals the recordings folder in the system file manager (the Clips panel menu). */
	private void openRecordingsFolder() {
		FileManager manager = desktopServices.fileManager();
		if (manager == null) {
			return;
		}
		Thread.ofVirtual().name("gravaai-open-recordings").start(() -> {
			try {
				manager.openFolder(new RecordingsDirectory(userData).getRecordingsDir());
			} catch (java.io.IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "could not open recordings folder: " + error);
			}
		});
	}

	/** The project's preview sidecar as a JavaFX image, when it exists (instant, no child process). */
	private static javafx.scene.image.@Nullable Image loadSidecarPoster(Path projectPath) {
		Path thumbnail = ProjectPoster.sidecar(projectPath);
		if (thumbnail == null) {
			return null;
		}
		try {
			return new javafx.scene.image.Image(thumbnail.toUri().toString());
		} catch (RuntimeException error) {
			// No poster: the skeleton keeps its placeholder (and the ffmpeg fallback runs).
			return null;
		}
	}

	private static boolean isProjectFile(Path path) {
		String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		return name.endsWith(".gravaai") || name.endsWith(".openscreen");
	}

	private void rememberProject(Path projectPath) {
		ProjectManager manager = ensureProjects();
		if (manager != null) {
			manager.rememberRecentProject(projectPath.toString());
		}
		if (dashboardView != null) {
			dashboardView.refresh();
		}
	}

	/**
	 * The project manager and its recent-history store, created on first use. Editor-only
	 * sessions (record → edit → save without ever opening the dashboard) must still remember
	 * saved projects, so the editor's save paths cannot depend on {@link #buildDashboard()}
	 * having run. Null when the library cannot be created; the callers degrade the same way
	 * they did before it existed.
	 */
	private @Nullable ProjectManager ensureProjects() {
		if (projects == null) {
			try {
				RecentProjectsStore store = new RecentProjectsStore(userData.recentProjectsFile());
				projects = new ProjectManager(userData, new RecordingsDirectory(userData), store,
						new FirstFrameThumbnail(ffmpegBinary()));
				recentProjects = store;
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING,
						"project library unavailable: " + error);
			}
		}
		return projects;
	}

	/** Dev/automation trigger: GRAVAAI_EDITOR_OPEN=<project> opens the real editor window. */
	private void openEditorFromEnvironment() {
		String requested = System.getenv("GRAVAAI_EDITOR_OPEN");
		if (requested == null || requested.isBlank()) {
			return;
		}
		Path path = Path.of(requested);
		Thread.ofVirtual().name("gravaai-editor-open").start(() -> Platform.runLater(() -> {
			if (appSettings == null) {
				appSettings = new AppSettingsStore(userData.appSettingsFile());
			}
			openProjectInEditor(path);
		}));
	}

	/** The bundled FFmpeg CLI used for first-frame project thumbnails (plan §5.7 layout). */
	private static Path ffmpegBinary() {
		// NativeHelpers resolves <app>/lib/native/linux-x64 (packaged) or java/native/build (dev);
		// the CLI lives in that directory's ffmpeg/bin subdirectory.
		return AppBinaries.ffmpeg();
	}

	/** Constructs the global-shortcut service (cheap); {@link #startShortcuts()} starts it. */
	private void createShortcuts() {
		if (shortcutsService != null) {
			return;
		}
		try {
			AppSettingsStore settings = appSettings();
			GlobalShortcutSettings settingsPort = new GlobalShortcutSettings() {
				@Override
				public @Nullable JsonNode read() {
					return settings.readSetting(GlobalShortcuts.GLOBAL_SHORTCUTS_SETTING);
				}

				@Override
				public void write(JsonNode value) {
					try {
						settings.writeSetting(GlobalShortcuts.GLOBAL_SHORTCUTS_SETTING, value);
					} catch (IOException error) {
						throw new UncheckedIOException("Could not save the global shortcuts setting", error);
					}
				}
			};
			Windows mainWindows = windows;
			if (mainWindows == null) {
				throw new IllegalStateException("main window not built; cannot create shortcuts");
			}
			dispatcher = new GlobalShortcutDispatcher(mainWindows::hud);
			shortcutsService = new GlobalShortcutsService(
					settingsPort,
					() -> NativeHelpers.path("gravaai-ctl"),
					AppLaunchPath.current(),
					System.getenv(),
					// The control socket reads on a virtual thread, but every action ends in the HUD,
					// which is JavaFX: hand over through the FX thread like the other two sinks.
					action -> Platform.runLater(() -> {
						GlobalShortcutDispatcher current = dispatcher;
						if (current != null) {
							current.dispatch(action);
						}
					}),
					() -> Platform.runLater(this::bringToFrontForSecondLaunch),
					// A second launch with a project/recording file argument (file-manager
					// association) hands the path over the control socket.
					path -> Platform.runLater(() -> openProjectInEditor(path)),
					// KDE Plasma, GNOME 48+ and others register shortcuts through the desktop portal.
					DesktopShortcutPortal.SYSTEM);
			LOG.log(System.Logger.Level.INFO, "global shortcut service created");
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "global shortcuts unavailable: " + error);
		}
	}

	/** The dashboard needs the shortcut service; a build without it falls back to the bare window. */
	private GlobalShortcutsService requireShortcutsService() {
		GlobalShortcutsService current = shortcutsService;
		if (current == null) {
			throw new IllegalStateException(
					"global shortcuts are unavailable; the dashboard cannot be built");
		}
		return current;
	}

	private void startShortcuts() {
		createShortcuts();
		if (shortcutsService == null) {
			return;
		}
		try {
			shortcutsService.init();
			LOG.log(System.Logger.Level.INFO, "global shortcuts ready");
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "global shortcut service failed to start: " + error);
		}
	}

	private void installCloseToTray(Stage stage) {
		if (stage == null) {
			return;
		}
		stage.setOnCloseRequest(event -> {
			event.consume();
			EditorWindow editor = activeEditor;
			if (editor != null && editor.isExportRunning()) {
				// Hiding the window would leave the export running with nothing to show it (and the
				// confirmation dialog invisible): ask to cancel and quit, or keep the window open.
				quit();
				return;
			}
			if (editor != null) {
				// Close-to-tray only hides the window: the engine keeps decoding on its background
				// threads, so the preview must pause before the window disappears.
				editor.pausePlayback();
			}
			stage.hide();
		});
	}

	private void installStageIcons(Stage stage) {
		if (stage == null) {
			return;
		}
		// JavaFX sets the taskbar/window-manager icon from the stage icons. Load every preferred
		// size directly from the packaged UI jar; missing sizes are skipped so a trimmed build still
		// starts and keeps the icons that are present.
		List<Image> stageIcons = icons.loadImages(
				"gravaai-32.png", "gravaai-128.png", "gravaai-256.png");
		if (!stageIcons.isEmpty()) {
			stage.getIcons().setAll(stageIcons);
		}
	}

	private void startTray() {
		Path iconPath = icons.load("gravaai-32.png");
		Path recordingIconPath = icons.loadOrNull("rec-button.png");
		RecordingWiring wiring = recordingWiring;
		boolean recordingActive = wiring != null && wiring.recording().isRecordingActive();
		String sourceName = recordingSourceName(wiring);
		desktopServices.installTray(iconPath, recordingIconPath, recordingActive, sourceName);
		if (wiring != null) {
			desktopServices.subscribeTrayRecordingState(
					wiring.events().onRecordingStateChanged(state -> Platform.runLater(
							() -> desktopServices.applyTrayRecordingState(
									state.recording(), state.sourceName()))));
		}
	}

	/** The name the tray tooltip shows for the current selection. */
	private static String recordingSourceName(@Nullable RecordingWiring wiring) {
		if (wiring == null) {
			return "Screen";
		}
		SelectedSource source = wiring.sources().getSelectedSource();
		return source == null || source.name() == null || source.name().isBlank()
				? "Screen" : source.name();
	}

	/** Shows and raises the main window, e.g. so a confirmation dialog is visible. */
	private void revealMainWindow() {
		Stage stage = primaryStage;
		if (stage == null) {
			return;
		}
		if (!stage.isShowing()) {
			stage.show();
		}
		stage.setIconified(false);
		stage.toFront();
	}

	private void quit() {
		EditorWindow editor = activeEditor;
		if (editor != null && editor.isExportRunning()) {
			revealMainWindow();
			// Never kill a running render silently: confirm first, cancel on approval.
			switchingViews = true;
			editor.requestHostClose(() -> {
				try {
					shutdown();
				} finally {
					switchingViews = false;
				}
				Platform.exit();
			}, () -> switchingViews = false);
			return;
		}
		shutdown();
		Platform.exit();
	}

	private void scheduleExitIfRequested() {
		long exitAfterMs = exitAfterMs();
		if (exitAfterMs <= 0) {
			return;
		}
		PauseTransition exit = new PauseTransition(Duration.millis(exitAfterMs));
		exit.setOnFinished(event -> {
			LOG.log(System.Logger.Level.INFO, "exit requested after " + exitAfterMs + " ms (AOT training)");
			shutdown();
			Platform.exit();
		});
		exit.play();
	}

	private static long exitAfterMs() {
		String property = System.getProperty("gravaai.exitAfterMs");
		if (property == null || property.isBlank()) {
			property = System.getenv("GRAVAAI_EXIT_AFTER_MS");
		}
		if (property == null || property.isBlank()) {
			return 0;
		}
		try {
			return Long.parseLong(property.strip());
		} catch (NumberFormatException error) {
			return 0;
		}
	}

	/**
	 * Logs the session variables a bug report needs and the JavaFX screen list.
	 *
	 * <p>Read-only: the classification itself comes from
	 * {@link dev.gravaai.platform.compositor.DesktopSession}, which the log line names.
	 */
	private static void logEnvironment() {
		LOG.log(System.Logger.Level.INFO, "java " + System.getProperty("java.runtime.version") + " ("
				+ System.getProperty("java.vm.name") + ")");
		LOG.log(System.Logger.Level.INFO, "javafx " + System.getProperty("javafx.runtime.version"));
		DesktopSession session = DesktopSession.current();
		LOG.log(System.Logger.Level.INFO, "session " + session.type() + " / " + session.environment()
				+ " (capture " + session.captureBackend() + ", pointer " + session.cursorBackend()
				+ ", shortcuts " + session.shortcutStrategy() + ")");
		for (String key : new String[] {"XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "WAYLAND_DISPLAY",
				"DISPLAY", "GDK_BACKEND", "GDK_SCALE"}) {
			LOG.log(System.Logger.Level.INFO, "env " + key + "=" + System.getenv(key));
		}
		for (Screen screen : Screen.getScreens()) {
			LOG.log(System.Logger.Level.INFO, "screen bounds=" + screen.getBounds() + " visual="
					+ screen.getVisualBounds() + " scale=" + screen.getOutputScaleX() + "x"
					+ screen.getOutputScaleY() + " dpi=" + screen.getDpi()
					+ (screen.equals(Screen.getPrimary()) ? " primary" : ""));
		}
	}

	/** The tray actions, marshalled to the FX thread by {@link DesktopServices}. */
	private final class TrayOperations implements DesktopServices.OperationSink {

		@Override
		public void showHud() {
			AppContext.this.showHud();
		}

		@Override
		public void openDashboard() {
			AppContext.this.openDashboard();
		}

		@Override
		public void stopRecording() {
			stopRecordingFromTray();
		}

		@Override
		public void quit() {
			AppContext.this.quit();
		}
	}
}
