package dev.gravaai.app;

import org.jspecify.annotations.Nullable;

import dev.gravaai.app.editor.EditorSkeletonWindow;
import dev.gravaai.app.agent.AgentWiring;
import dev.gravaai.app.export.ProjectExporter;
import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.app.editor.EditorWindow;
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
import dev.gravaai.core.storage.UserData;
import dev.gravaai.platform.dbus.SessionBus;
import dev.gravaai.media.ffmpeg.MediaProbe;
import dev.gravaai.platform.filemanager.FileManager;
import dev.gravaai.platform.nativehelpers.NativeHelpers;
import dev.gravaai.platform.notification.Notifications;
import dev.gravaai.platform.portal.Portal;
import dev.gravaai.platform.tray.Tray;
import dev.gravaai.platform.tray.TrayIcon;
import dev.gravaai.platform.trash.Trash;
import dev.gravaai.platform.tray.TrayMenu;
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
import dev.gravaai.services.recording.CosmicRecorder.StopResult;
import dev.gravaai.services.recording.RecordingService;
import dev.gravaai.services.shortcuts.GlobalShortcutAction;
import dev.gravaai.services.shortcuts.GlobalShortcutDispatcher;
import dev.gravaai.services.shortcuts.GlobalShortcutSettings;
import dev.gravaai.services.shortcuts.GlobalShortcuts;
import dev.gravaai.services.shortcuts.GlobalShortcutsService;
import dev.gravaai.ui.dashboard.DashboardDependencies;
import dev.gravaai.ui.dashboard.DashboardLibraryStore;
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
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
public final class AppContext {

	private static final System.Logger LOG = System.getLogger("gravaai");

	private final UserData userData;
	private final GlobalShortcutAction pendingAction;
	private final AppIcons icons;

	private @Nullable Stage primaryStage;
	private @Nullable Windows windows;
	private @Nullable AppSettingsStore appSettings;
	private @Nullable GlobalShortcutDispatcher dispatcher;
	private @Nullable GlobalShortcutsService shortcutsService;
	private @Nullable SessionBus bus;
	private @Nullable Portal portal;
	private @Nullable FileManager fileManager;
	private @Nullable Notifications notifications;
	private @Nullable AutomationService automationService;
	private @Nullable Tray tray;
	private @Nullable AutoCloseable trayRecordingSubscription;
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
	private @Nullable ProjectExporter exporter;
	private @Nullable ExportCoordinator exportCoordinator;
	// P3.3 extension host (plan §5.18): userData/extensions drives the render hooks of the editor
	// preview and the exporter. Created off the startup path; a failure disables extensions only.
	private @Nullable ExtensionHost extensionHost;
	private boolean backgroundServicesStarted;
	private boolean shutdown;

	private AppContext(UserData userData, GlobalShortcutAction pendingAction) {
		this.userData = userData;
		this.pendingAction = pendingAction;
		this.icons = new AppIcons(userData);
	}

	public static @Nullable AppContext create(GlobalShortcutAction pendingAction) {
		return new AppContext(UserData.resolve(), pendingAction);
	}

	public void start(Stage primaryStage) {
		this.primaryStage = primaryStage;
		installStageIcons(primaryStage);
		// Single-window app: the primary stage hosts the dashboard, the editor and (later) the
		// meeting view. Gravaai lives in the tray, so closing the main window hides it; only the
		// tray Quit action (or an explicit application shutdown) may exit the process.
		installCloseToTray(primaryStage);
		windows = new Windows(primaryStage, Boolean.getBoolean("gravaai.uiKit"));
		windows.setEditorLauncher(this::openProjectInEditor);
		// P2.11 fast path: when a project is requested at launch, the editor is the startup
		// window. Skip the HUD and the heavy services on the critical path; they start after
		// the editor window is visible.
		String launchProject = System.getenv("GRAVAAI_EDITOR_OPEN");
		if (launchProject != null && !launchProject.isBlank()) {
			// P2.11 skeleton-first: show an editor-shaped skeleton immediately, then build the real
			// EditorWindow on the next FX pulse (the skeleton is the visible startup window).
			Path launchPath = Path.of(launchProject);
			String launchName = launchPath.getFileName().toString().replaceFirst("\\.(gravaai|openscreen)$", "");
			javafx.scene.image.Image preloadedPoster = loadPoster(launchPath);
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
					try {
						java.nio.file.Path poster = extractPoster(launchPath);
						if (poster != null) {
							Platform.runLater(() -> {
								skeleton.setPoster(new javafx.scene.image.Image(poster.toUri().toString()));
								Startup.log("first preview frame");
							});
						}
					} catch (Exception ignored) {
						// Poster is an optimisation: fall back to the skeleton placeholder.
					}
				});
			}
			// The skeleton is visible and the budget lines are logged; load the UI fonts now, before
			// the full editor shell is built (Application.init skips this in the editor fast path).
			Main.ensureFontsLoaded();
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
		startHudWindow();
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
			windows.showMainWindow();
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
		// One D-Bus connection serves the tray, the portal-backed file dialogs and the file manager.
		Optional<SessionBus> connection = SessionBus.tryConnect();
		bus = connection.orElse(null);
		if (bus != null) {
			try {
				portal = new Portal(bus);
			} catch (Exception error) {
				LOG.log(System.Logger.Level.INFO, "xdg-desktop-portal unavailable: " + error);
			}
			try {
				fileManager = new FileManager(bus);
			} catch (Exception error) {
				LOG.log(System.Logger.Level.INFO, "FileManager1 unavailable: " + error);
			}
			try {
				notifications = new Notifications(bus);
			} catch (Exception error) {
				LOG.log(System.Logger.Level.INFO, "notifications unavailable: " + error);
			}
		}
		try {
			automationService = new AutomationService(
					userData.automationsFile(),
					new AppAutomationHost(portal, fileManager, notifications),
					path -> {
						double seconds = MediaProbe.probe(path).durationSeconds();
						return Double.isFinite(seconds) && seconds > 0 ? seconds : null;
					},
					Path.of(System.getProperty("user.home")));
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "automations unavailable: " + error);
		}
	}

	/**
	 * Builds the HUD window with an idle delegating recording port (startup path: no services yet).
	 * Nothing is shown here and no capture starts: the HUD/shortcut/tray flows drive the recording
	 * service once {@link #initializeRecordingServices()} attaches the real port.
	 */
	private void startHudWindow() {
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
			windows.setHud(hudWindow);
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
		try {
			recordingWiring = RecordingWiring.create(userData, appSettings, hudController);
			recordingWiring.onRecordingSaved(this::onRecordingSaved);
			hudPort = HudRecordingPortAdapter.create(
					recordingWiring,
					userData,
					appSettings,
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
						public void openRecordingInEditor(String videoPath) {
							if (videoPath != null && !videoPath.isBlank()) {
								openProjectInEditor(Path.of(videoPath));
							}
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
		if (meeting != null || recordingWiring == null || appSettings == null) {
			return;
		}
		try {
			meeting = MeetingIntegration.create(userData, appSettings, recordingWiring, fileManager,
					automationService);
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
		if (agentWiring != null || appSettings == null) {
			return;
		}
		try {
			AgentWiring wiring = AgentWiring.create(userData, appSettings,
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
		if (exporter != null || automationService == null) {
			return;
		}
		ExportPublisher publisher = new ExportPublisher(fileManager != null ? fileManager::reveal : null);
		exportCoordinator = new ExportCoordinator(publisher, automationService.triggers());
		EditorPreferencesStore preferencesStore = appSettings == null ? null
				: new EditorPreferencesStore(appSettings);
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
		if (extensionHost != null || shutdown) {
			return extensionHost;
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
		return extensionHost;
	}

	/**
	 * Extension render hooks for one editor's preview, bound to that project's timeline duration
	 * (plan §5.18). Dispatched from the compositor on the preview GL thread.
	 */
	private ExtensionFrameOverlayHooks previewOverlayHooks(EditorSession session) {
		return new ExtensionFrameOverlayHooks(extensionHost(),
				() -> session.timelineDataSource().totalDurationMs());
	}

	private ProjectExporter exporter() {
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
					new dev.gravaai.core.editor.EditorPreferencesStore(appSettings),
					new dev.gravaai.services.captions.WhisperModels(userData,
							new dev.gravaai.services.meeting.HttpFileDownloader()),
					dev.gravaai.services.captions.AutoCaptionService.withDaemonThread(generator),
					dev.gravaai.app.meeting.MeetingIntegration.whisperCliPath(),
					() -> window.scene().getRoot()));
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "auto-captions unavailable: " + error);
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
	public MeetingIntegration meetingIntegration() {
		return meeting;
	}

	/** Opens the meeting window (null path keeps whatever meeting the view already shows). */
	private void openMeetingWindow() {
		openMeetingWindow(null);
	}

	private void openMeetingWindow(Path mediaPath) {
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
	private void showMeetingInMainWindow(Path mediaPath) {
		MeetingIntegration integration = meeting;
		if (shutdown || integration == null) {
			return;
		}
		try {
			if (meetingWindow == null) {
				meetingWindow = MeetingWindow.createIn(primaryStage, integration.service(),
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
		if (automationService == null || result == null || !result.success() || result.path() == null) {
			return;
		}
		try {
			automationService.fireRecordingSaved(new AutomationTriggers.RecordingSavedResult(
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
		closeTrayRecordingSubscription();
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
		if (tray != null) {
			tray.close();
		}
		if (extensionHost != null) {
			try {
				extensionHost.close();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "extension host shutdown failed: " + error);
			}
			extensionHost = null;
		}
		if (automationService != null) {
			automationService.close();
			automationService = null;
		}
		if (portal != null) {
			portal.close();
			portal = null;
		}
		if (bus != null) {
			bus.close();
			bus = null;
		}
		for (EditorWindow window : List.copyOf(editorWindows)) {
			try {
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
		return windows;
	}

	// ── HUD ────────────────────────────────────────────────────────────────

	/** Tray/shortcut/dashboard "Show Overlay": show the HUD window (the startup window). */
	private void showHud() {
		HudOverlayWindow hud = hudWindow;
		if (hud == null) {
			windows.showMainWindow();
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
		if (dashboardScene != null) {
			primaryStage.setTitle("Gravaai");
			primaryStage.setMinWidth(900);
			primaryStage.setMinHeight(600);
			StageScenes.setSceneKeepingSize(primaryStage, dashboardScene);
		}
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

	private void buildDashboard() {
		if (primaryStage == null) {
			return;
		}
		try {
			RecordingsDirectory recordingsDirectory = new RecordingsDirectory(userData);
			recentProjects = new RecentProjectsStore(userData.recentProjectsFile());
			projects = new ProjectManager(
					userData,
					recordingsDirectory,
					recentProjects,
					new FirstFrameThumbnail(ffmpegBinary()));
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
					Portal chooser = portal;
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
					Portal chooser = portal;
					if (chooser == null) {
						LOG.log(System.Logger.Level.WARNING, "import file: no xdg-desktop-portal available");
						return;
					}
					Thread.ofVirtual().name("dashboard-import").start(() -> {
						try {
							chooser.openFile(Portal.FileDialogRequest.open("Import a recording"))
									.ifPresent(path -> Platform.runLater(() -> {
										if (dashboardView != null) {
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
					FileManager manager = fileManager;
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
				public java.util.concurrent.@Nullable CompletableFuture<String> recordingThumbnail(String videoPath) {
					RecordingWiring wiring = recordingWiring;
					if (wiring == null) {
						return java.util.concurrent.CompletableFuture.completedFuture(null);
					}
					return wiring.thumbnails().getRecordingThumbnail(videoPath);
				}

				@Override
				public void openExternalUrl(String url) {
					Portal opener = portal;
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
			DashboardDependencies dependencies = new DashboardDependencies(
					userData, projects, recordings, recoverable, preferences, Trash.defaultTrash(), actions,
					new ServiceGlobalShortcutsController(shortcutsService),
					new PlaceholderAgentSettingsController(appPath()),
					automationService == null ? null : new ServiceAutomationsController(automationService, portal),
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
			windows.showMainWindow();
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
		try {
			EditorWindow window = EditorWindow.createIn(primaryStage, path, editorServices());
			hostEditor(window, path, startup);
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "could not open project in editor: " + error);
			switchingViews = false;
		}
	}

	/** A recording as a new, unsaved project (React switchToEditor after stop). */
	private void openRecordingInMainWindow(Path videoPath) {
		try {
			dev.gravaai.services.recording.RecordingSessionManifest.RecordingSessionData linked =
					dev.gravaai.services.recording.RecordingSessionManifest
							.resolveRecordingSession(videoPath.toString());
			String webcam = linked == null ? null : linked.webcamPath();
			long offset = linked == null ? 0 : linked.timeOffsetMs();
			EditorWindow window = EditorWindow.createForRecordingIn(primaryStage, videoPath, webcam,
					offset, editorServices());
			hostEditor(window, null, false);
			LOG.log(System.Logger.Level.INFO, "recording opened in the editor: " + videoPath
					+ (webcam == null ? "" : " (webcam " + webcam + ", offset " + offset + " ms)"));
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "could not open recording in editor: " + error);
			switchingViews = false;
		}
	}

	private EditorWindow.@Nullable Services editorServices() {
		if (appSettings == null) {
			appSettings = new AppSettingsStore(userData.appSettingsFile());
		}
		return new EditorWindow.Services(
				projects,
				appSettings,
				this::openProjectInEditor,
				this::rememberProject,
				this::onEditorWindowClosed,
				null,
				null,
				exporter(),
				this::recordingService,
				this::openRecordingsFolder);
	}

	/** Shows {@code window} as the main window content; {@code path} is null for an unsaved recording. */
	private void hostEditor(EditorWindow window, @Nullable Path path, boolean startup) {
		window.setOnGoHome(() -> javafx.application.Platform.runLater(this::openDashboard));
		activeEditor = window;
		editorWindows.add(window);
		window.show();
		window.setOverlayHooks(previewOverlayHooks(window.session()));
		attachAgent(window);
		attachCaptions(window);
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
		FileManager manager = fileManager;
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

	/** Instant poster for the startup skeleton: the project preview sidecar next to the file. */
	private static javafx.scene.image.@Nullable Image loadPoster(Path projectPath) {
		try {
			Path thumbnail = dev.gravaai.services.projects.ProjectManager
					.getProjectThumbnailPath(projectPath);
			if (java.nio.file.Files.isRegularFile(thumbnail)) {
				return new javafx.scene.image.Image(thumbnail.toUri().toString());
			}
		} catch (RuntimeException ignored) {
			// No poster: the skeleton keeps its placeholder (and the ffmpeg fallback runs).
		}
		return null;
	}

	/** Fallback poster: one frame from the project video through the bundled ffmpeg CLI. */
	private static @Nullable Path extractPoster(Path projectPath) throws java.io.IOException,
			InterruptedException {
		var document = dev.gravaai.core.project.ProjectDocument.load(projectPath);
		String video = document.videoPath();
		if (video == null || video.isBlank()) {
			return null;
		}
		Path ffmpeg = NativeHelpers.nativeDir().resolve("ffmpeg").resolve("bin").resolve("ffmpeg");
		Path poster = java.nio.file.Files.createTempFile("gravaai-poster", ".png");
		Process process = new ProcessBuilder(ffmpeg.toString(), "-y", "-ss", "0", "-i", video,
				"-frames:v", "1", "-f", "image2", poster.toString()).redirectErrorStream(true).start();
		if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
			process.destroyForcibly();
			return null;
		}
		return process.exitValue() == 0 && java.nio.file.Files.isRegularFile(poster) ? poster : null;
	}

	private static boolean isProjectFile(Path path) {
		String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		return name.endsWith(".gravaai") || name.endsWith(".openscreen");
	}

	private void rememberProject(Path projectPath) {
		if (projects != null) {
			projects.rememberRecentProject(projectPath.toString());
		}
		if (dashboardView != null) {
			dashboardView.refresh();
		}
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
		return NativeHelpers.nativeDir().resolve("ffmpeg").resolve("bin").resolve("ffmpeg");
	}

	/** Constructs the global-shortcut service (cheap); {@link #startShortcuts()} starts it. */
	private void createShortcuts() {
		if (shortcutsService != null) {
			return;
		}
		try {
			appSettings = new AppSettingsStore(userData.appSettingsFile());
			GlobalShortcutSettings settingsPort = new GlobalShortcutSettings() {
				@Override
				public @Nullable JsonNode read() {
					return appSettings.readSetting(GlobalShortcuts.GLOBAL_SHORTCUTS_SETTING);
				}

				@Override
				public void write(JsonNode value) {
					try {
						appSettings.writeSetting(GlobalShortcuts.GLOBAL_SHORTCUTS_SETTING, value);
					} catch (IOException error) {
						throw new UncheckedIOException("Could not save the global shortcuts setting", error);
					}
				}
			};
			dispatcher = new GlobalShortcutDispatcher(windows::hud);
			shortcutsService = new GlobalShortcutsService(
					settingsPort,
					() -> NativeHelpers.path("gravaai-ctl"),
					appPath(),
					System.getenv(),
					dispatcher::dispatch,
					() -> Platform.runLater(this::bringToFrontForSecondLaunch));
			LOG.log(System.Logger.Level.INFO, "global shortcut service created");
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "global shortcuts unavailable: " + error);
		}
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

	private static void installCloseToTray(Stage stage) {
		if (stage == null) {
			return;
		}
		stage.setOnCloseRequest(event -> {
			event.consume();
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
		if (bus == null) {
			LOG.log(System.Logger.Level.INFO, "no D-Bus session; tray disabled");
			return;
		}
		if (!Tray.isSupported(bus)) {
			LOG.log(System.Logger.Level.INFO, "no StatusNotifierWatcher on the session bus; tray disabled");
			return;
		}
		try {
			Path iconPath = icons.load("gravaai-32.png");
			Path recordingIconPath = icons.loadOrNull("rec-button.png");
			TrayIcon icon = TrayIcon.load(iconPath);
			TrayIcon recordingIcon = recordingIconPath != null ? TrayIcon.load(recordingIconPath) : icon;
			TrayMenu.Actions actions = new TrayMenu.Actions(
					() -> Platform.runLater(this::showHud),
					() -> Platform.runLater(this::openDashboard),
					() -> Platform.runLater(this::stopRecordingFromTray),
					() -> Platform.runLater(this::quit));
			tray = new Tray(bus, icon, recordingIcon, actions);
			tray.install();
			subscribeTrayRecordingState();
			LOG.log(System.Logger.Level.INFO, "tray installed");
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING, "tray unavailable: " + error);
		}
	}

	/** Keeps the tray icon/tooltip/menu in sync with recording events, including in-flight state. */
	private void subscribeTrayRecordingState() {
		closeTrayRecordingSubscription();
		RecordingWiring wiring = recordingWiring;
		if (wiring == null) {
			return;
		}
		trayRecordingSubscription = wiring.events().onRecordingStateChanged(state ->
				Platform.runLater(() -> applyTrayRecordingState(state.recording(), state.sourceName())));
		var source = wiring.sources().getSelectedSource();
		String sourceName = source == null || source.name() == null || source.name().isBlank()
				? "Screen" : source.name();
		// startTray can run after recording started (editor fast path); seed the tray with the
		// current service state so it is immediately a working stop button.
		applyTrayRecordingState(wiring.recording().isRecordingActive(), sourceName);
	}

	private void applyTrayRecordingState(boolean recording, String sourceName) {
		Tray current = tray;
		if (current != null && !shutdown) {
			current.setRecording(recording, sourceName);
		}
	}

	private void closeTrayRecordingSubscription() {
		AutoCloseable subscription = trayRecordingSubscription;
		trayRecordingSubscription = null;
		if (subscription == null) {
			return;
		}
		try {
			subscription.close();
		} catch (Exception error) {
			LOG.log(System.Logger.Level.WARNING,
					"could not close the tray recording subscription: " + error);
		}
	}

	private void quit() {
		EditorWindow editor = activeEditor;
		if (editor != null && editor.isExportRunning()) {
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

	private static String appPath() {
		String appImage = System.getenv("APPIMAGE");
		if (appImage != null && !appImage.isBlank()) {
			return appImage;
		}
		String jpackagePath = System.getProperty("jpackage.app-path");
		if (jpackagePath != null && !jpackagePath.isBlank()) {
			return jpackagePath;
		}
		return ProcessHandle.current().info().command().orElse("gravaai");
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

	private static void logEnvironment() {
		LOG.log(System.Logger.Level.INFO, "java " + System.getProperty("java.runtime.version") + " ("
				+ System.getProperty("java.vm.name") + ")");
		LOG.log(System.Logger.Level.INFO, "javafx " + System.getProperty("javafx.runtime.version"));
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
}
