package dev.gravaai.app.export;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.core.editor.EditorPreferences;
import dev.gravaai.core.editor.EditorPreferencesStore;
import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.editor.ExportPreferences.ExportFormat;
import dev.gravaai.app.editor.CursorTelemetryLoader;
import dev.gravaai.media.compose.CompositorExportFrameProvider;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.media.compose.ProjectFrameInputFactory;
import dev.gravaai.services.extensions.ExtensionFrameOverlayHooks;
import dev.gravaai.services.extensions.ExtensionHost;
import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.media.export.ExportRunner;
import dev.gravaai.services.export.ExportCoordinator;
import dev.gravaai.services.export.ProjectExport;
import dev.gravaai.ui.editor.export.ExportSessionModel;
import dev.gravaai.ui.kit.Toast;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * P3 export controller (port of {@code useExportRunner.ts} + {@code useExportDialogActions.ts},
 * plan §5.10/§5.12): renders the current {@link EditorSession} to a staging temp file inside a
 * per-run {@code 0700} temp directory (the Electron {@code exportStream.ts} semantics), then asks
 * for the destination with a save dialog and publishes. The editor's {@link ExportSessionModel} is
 * driven on the FX thread; late callbacks of cancelled runs are dropped through run ids.
 *
 * <p>Cursor telemetry comes from the recording sidecar (the same loader as the preview). Wallpapers
 * are resolved from the UI resources into a per-export temp root. The headless
 * {@link #runTo} path is unchanged. Extension render hooks (plan §5.18) run in every exported
 * frame through the compositor, with the same phase order as the editor preview.
 */
public final class ProjectExporter {

	private static final System.Logger LOG = System.getLogger("gravaai.export");

	private final ExportCoordinator coordinator;
	private final ExportPublisher publisher;
	private final EditorPreferencesStore preferencesStore;
	/** Extension render hooks (plan §5.18); null until the app wires the extension host. */
	private ExtensionHost extensionHost;

	public ProjectExporter(ExportCoordinator coordinator, ExportPublisher publisher,
			EditorPreferencesStore preferencesStore) {
		this.coordinator = coordinator;
		this.publisher = publisher;
		this.preferencesStore = preferencesStore;
	}

	/**
	 * Installs the extension host whose render hooks run in every exported frame (plan §5.18).
	 * Null keeps the exporter extension-free; a host with no active extensions renders identical
	 * frames to the pre-extension pipeline.
	 */
	public void setExtensionHost(ExtensionHost host) {
		this.extensionHost = host;
	}

	/** The extension hooks for one export, bound to the prepared timeline duration. */
	private FrameOverlayHooks overlayHooks(double durationSeconds) {
		ExtensionHost host = extensionHost;
		if (host == null) {
			return FrameOverlayHooks.NONE;
		}
		double durationMs = Double.isFinite(durationSeconds) ? durationSeconds * 1000.0 : 0.0;
		return new ExtensionFrameOverlayHooks(host, () -> durationMs);
	}

	/** The persisted export settings (the popover's initial state). */
	public ExportPreferences exportPreferences() {
		if (preferencesStore == null) {
			return ExportPreferences.defaults();
		}
		EditorPreferences preferences = preferencesStore.loadPreferences();
		return new ExportPreferences(preferences.exportEncodingMode(),
				preferences.exportBackendPreference(), preferences.exportPipelineModel(),
				preferences.exportQuality(), preferences.mp4FrameRate(), preferences.exportFormat(),
				preferences.gifFrameRate(), preferences.gifLoop(), preferences.gifSizePreset(),
				preferences.publishDestination(), preferences.includeCaptionSidecar());
	}

	/** Persists an export-settings change (the 11 export JSON keys, merged over the prefs). */
	public void saveExportPreferences(ExportPreferences preferences) {
		if (preferencesStore != null && preferences != null) {
			preferencesStore.savePreferences(preferences.toJson());
		}
	}

	/**
	 * Starts the export with the popover settings: render to the staging file first, then the save
	 * dialog ({@code handleStartExportFromDropdown} + {@code handleExport}).
	 */
	public boolean startExport(EditorSession session, ExportPreferences preferences,
			ExportSessionModel model, Window owner) {
		int runId = model.beginExportRun();
		model.setDropdownOpen(true);
		model.setExporting(true);
		model.setProgress(null);
		model.setError(null);
		model.setExportedFilePath(null);
		model.setPendingExportSave(null);
		discardStagingDir();
		try {
			Path stagingDir = Files.createTempDirectory("gravaai-export-");
			try {
				Files.setPosixFilePermissions(stagingDir,
						java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
			} catch (UnsupportedOperationException | IOException permissionError) {
				LOG.log(System.Logger.Level.DEBUG,
						"staging dir permissions: " + permissionError);
			}
			ExportFormat format = preferences.exportFormat() == null ? ExportFormat.MP4
					: preferences.exportFormat();
			Path staged = stagingDir.resolve("export-" + System.currentTimeMillis()
					+ (format == ExportFormat.GIF ? ".gif" : ".mp4"));
			ProjectExport.Input input = new ProjectExport.Input(session.store().state(),
					Path.of(session.videoPath()), session.webcamPath(), List.of(), preferences,
					staged, false, true,
					session.timelineDataSource().totalDurationMs() / 1000.0);
			ProjectExport.Prepared prepared = ProjectExport.prepare(input);
			Path wallpaperRoot = stageWallpapers(session);
			ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
					session.store().state(), prepared.videoPath(), prepared.webcamPath(),
					CursorTelemetryLoader.load(session.videoPath()), prepared.durationSeconds(), 0,
					wallpaperRoot, prepared.frameRate());
			CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
					prepared.outputWidth(), prepared.outputHeight(), prepared.frameRate(), factory,
					overlayHooks(prepared.durationSeconds()));
			Run run = new Run(session, model, runId, prepared, stagingDir, owner, provider, factory);
			boolean started = coordinator.start(prepared.request(), provider, run);
			if (!started) {
				run.closeFactory();
				throw new IllegalStateException("An export is already running.");
			}
			return true;
		} catch (IOException | RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "export failed to start: " + error);
			model.setExporting(false);
			model.setError(errorMessage(error));
			return false;
		}
	}

	/** {@code handleCancelExport}: cancel the exporter, discard the pending save, reset. */
	public void cancelExport(ExportSessionModel model) {
		if (!model.isExporting()) {
			return;
		}
		model.cancelRun();
		coordinator.cancel();
		discardPending(model);
		model.setExporting(false);
		model.setProgress(null);
		model.setError(null);
		model.setExportedFilePath(null);
		model.setDropdownOpen(false);
	}

	/** {@code handleRetrySaveExport}: save the staged export again without re-rendering. */
	public void retrySaveExport(EditorSession session, ExportSessionModel model, Window owner) {
		ExportSessionModel.PendingExportSave pending = model.pendingExportSave();
		if (pending == null) {
			return;
		}
		saveStaged(session, model, pending, owner);
	}

	/** {@code revealExportedFile}: reveal the saved file in the file manager. */
	public void revealExportedFile(ExportSessionModel model) {
		String path = model.exportedFilePath();
		if (path == null || path.isBlank()) {
			return;
		}
		reveal(Path.of(path));
	}

	private void reveal(Path path) {
		try {
			publisher.reveal(path);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING,
					"could not reveal the exported file: " + error.getMessage());
		}
	}

	/** The success toast of {@code useExportSuccessToast}. */
	private void showExportSuccessToast(Window owner, Path saved) {
		Toast toast = showToast(owner, "Exported successfully to " + saved);
		if (toast != null) {
			toast.variant(Toast.Variant.SUCCESS)
					.action("Show in Folder", () -> reveal(saved));
		}
	}

	/** Toast in the export window ({@code null} owner: the toast is skipped, logged instead). */
	private static Toast showToast(Window owner, String title) {
		javafx.scene.Node node = owner != null && owner.getScene() != null
				? owner.getScene().getRoot() : null;
		if (node == null) {
			LOG.log(System.Logger.Level.INFO, title);
			return null;
		}
		return Toast.show(node, title);
	}

	/**
	 * The save half of {@code handleExport}: ask for the destination, then move the staged file
	 * (and its caption sidecars) into place. Runs on the FX thread.
	 */
	private void saveStaged(EditorSession session, ExportSessionModel model,
			ExportSessionModel.PendingExportSave pending, Window owner) {
		Path stagedFile = pending.stagedFile();
		Path stagingDir = stagedFile == null ? null : stagedFile.getParent();
		FileChooser chooser = new FileChooser();
		boolean gif = stagedFile != null && stagedFile.getFileName().toString().endsWith(".gif");
		chooser.setTitle(gif ? "Export GIF" : "Export video");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
				gif ? "GIF" : "MP4 video", gif ? "*.gif" : "*.mp4"));
		chooser.setInitialFileName(pending.fileName() == null ? "Untitled.mp4"
				: pending.fileName());
		java.io.File file = chooser.showSaveDialog(owner);
		if (file == null) {
			model.setError(
					"Save dialog canceled. Click Save Again to save without re-rendering.");
			Toast canceled = showToast(owner,
					"Save canceled. You can save again without re-exporting.");
			if (canceled != null) {
				canceled.variant(Toast.Variant.DEFAULT);
			}
			return;
		}
		Path destination = file.toPath();
		try {
			publisher.publish(stagedFile,
					new ExportPublisher.Destination(destination, false), true);
			moveSidecars(stagedFile, destination);
			model.setPendingExportSave(null);
			model.setError(null);
			model.setExportedFilePath(destination.toString());
			model.setExporting(false);
			deleteRecursively(stagingDir);
			showExportSuccessToast(owner, destination);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "could not save the export: " + error);
			String message = errorMessage(error);
			model.setError(message);
			Toast failure = showToast(owner, message);
			if (failure != null) {
				failure.variant(Toast.Variant.DANGER);
			}
			if (stagedFile == null || !Files.isRegularFile(stagedFile)) {
				// The staged file is gone; nothing to retry.
				model.setPendingExportSave(null);
				model.setExporting(false);
				deleteRecursively(stagingDir);
			}
		}
	}

	/** Moves the caption sidecars written next to the staged file to the final destination. */
	private static void moveSidecars(Path stagedFile, Path destination) {
		if (stagedFile == null) {
			return;
		}
		String stagedName = stagedFile.getFileName().toString();
		int dot = stagedName.lastIndexOf('.');
		String stagedBase = dot > 0 ? stagedName.substring(0, dot) : stagedName;
		String finalName = destination.getFileName().toString();
		int finalDot = finalName.lastIndexOf('.');
		String finalBase = finalDot > 0 ? finalName.substring(0, finalDot) : finalName;
		for (String extension : List.of(".srt", ".vtt")) {
			Path sidecar = stagedFile.resolveSibling(stagedBase + extension);
			if (!Files.isRegularFile(sidecar)) {
				continue;
			}
			try {
				Files.move(sidecar, destination.resolveSibling(finalBase + extension),
						StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException error) {
				LOG.log(System.Logger.Level.WARNING,
						"could not move the caption sidecar: " + error);
			}
		}
	}

	/** {@code clearPendingExportSave}: drops the pending save and deletes its staged file. */
	public void discardPendingExportSave(ExportSessionModel.PendingExportSave pending) {
		if (pending == null || pending.stagedFile() == null) {
			return;
		}
		deleteRecursively(pending.stagedFile().getParent());
	}

	private void discardPending(ExportSessionModel model) {
		discardPendingExportSave(model.clearPendingExportSave());
	}

	private Path stagingDir;

	private void discardStagingDir() {
		Path dir = stagingDir;
		stagingDir = null;
		if (dir != null) {
			deleteRecursively(dir);
		}
	}

	private static void deleteRecursively(Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			return;
		}
		try (var paths = Files.list(directory)) {
			paths.forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException error) {
					LOG.log(System.Logger.Level.DEBUG, "could not delete " + path + ": " + error);
				}
			});
			Files.deleteIfExists(directory);
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG,
					"could not delete the staging dir: " + error);
		}
	}

	private static String errorMessage(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.toString() : message;
	}

	/** Headless export on the calling thread (acceptance harness / tests). */
	public ExportRunner.Outcome runTo(EditorSession session, String format, Path destination)
			throws IOException {
		ExportPreferences defaults = ExportPreferences.defaults();
		ExportPreferences preferences = new ExportPreferences(defaults.exportEncodingMode(),
				defaults.exportBackendPreference(), defaults.exportPipelineModel(),
				defaults.exportQuality(), defaults.mp4FrameRate(),
				"gif".equalsIgnoreCase(format) ? ExportFormat.GIF : ExportFormat.MP4,
				defaults.gifFrameRate(), defaults.gifLoop(), defaults.gifSizePreset(),
				defaults.publishDestination(), defaults.includeCaptionSidecar());
		ProjectExport.Input input = new ProjectExport.Input(session.store().state(),
				Path.of(session.videoPath()), session.webcamPath(), List.of(), preferences,
				destination, false, false,
				session.timelineDataSource().totalDurationMs() / 1000.0);
		ProjectExport.Prepared prepared = ProjectExport.prepare(input);
		FrameOverlayHooks hooks = overlayHooks(prepared.durationSeconds());
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(
				session.store().state(), prepared.videoPath(), prepared.webcamPath(),
				CursorTelemetryLoader.load(session.videoPath()), prepared.durationSeconds(), 0,
				stageWallpapers(session), prepared.frameRate());
				CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
						prepared.outputWidth(), prepared.outputHeight(), prepared.frameRate(),
						factory, hooks)) {
			return ProjectExport.run(input, provider, publisher);
		}
	}

	/**
	 * Materialises the project wallpaper resources into a temp root ({@code wallpapers/...}) so the
	 * compositor can read them from the filesystem in the packaged app.
	 */
	private static Path stageWallpapers(EditorSession session) {
		String wallpaper = session.store().state().wallpaper();
		if (wallpaper == null || !wallpaper.startsWith("/wallpapers/")) {
			return null;
		}
		String name = wallpaper.substring("/wallpapers/".length());
		URL resource = ProjectExporter.class.getResource("/ui/editor/wallpapers/" + name);
		if (resource == null) {
			return null;
		}
		try {
			Path root = Files.createTempDirectory("gravaai-export-wallpapers");
			Path target = root.resolve("wallpapers").resolve(name);
			Files.createDirectories(target.getParent());
			try (var stream = resource.openStream()) {
				Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return root;
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "wallpaper staging failed: " + error);
			return null;
		}
	}

	/** One export run: the coordinator listener with run-id guards and FX marshalling. */
	private final class Run implements ExportCoordinator.Listener {

		private final EditorSession session;
		private final ExportSessionModel model;
		private final int runId;
		private final ProjectExport.Prepared prepared;
		private final Path stagingDir;
		private final Window owner;
		private final CompositorExportFrameProvider provider;
		private final ProjectFrameInputFactory factory;
		private final AtomicBoolean factoryClosed = new AtomicBoolean();

		Run(EditorSession session, ExportSessionModel model, int runId,
				ProjectExport.Prepared prepared, Path stagingDir, Window owner,
				CompositorExportFrameProvider provider, ProjectFrameInputFactory factory) {
			this.session = session;
			this.model = model;
			this.runId = runId;
			this.prepared = prepared;
			this.stagingDir = stagingDir;
			this.owner = owner;
			this.provider = provider;
			this.factory = factory;
			ProjectExporter.this.stagingDir = stagingDir;
		}

		@Override
		public void onProgress(ExportProgress progress) {
			Platform.runLater(() -> {
				if (!model.isCurrentRun(runId)) {
					return;
				}
				model.setProgress(progress);
			});
		}

		@Override
		public void onCompleted(ExportRunner.Outcome outcome) {
			Platform.runLater(() -> {
				closeFactory();
				if (!model.isCurrentRun(runId)) {
					return;
				}
				finishRender(outcome);
			});
		}

		@Override
		public void onCancelled() {
			Platform.runLater(this::closeFactory);
		}

		@Override
		public void onFailed(Throwable error) {
			Platform.runLater(() -> {
				closeFactory();
				if (!model.isCurrentRun(runId)) {
					return;
				}
				LOG.log(System.Logger.Level.WARNING, "export failed: " + error);
				model.setExporting(false);
				model.setError(errorMessage(error));
				// The runner deleted its temp file; the staged destination never existed.
				deleteRecursively(stagingDir);
			});
		}

		/** {@code markExportAsSaving} + the save dialog ({@code finalizeExportedVideo}). */
		private void finishRender(ExportRunner.Outcome outcome) {
			model.markExportAsSaving();
			ExportSessionModel.PendingExportSave pending = new ExportSessionModel.PendingExportSave(
					outcome.path().getFileName().toString(), outcome.path(),
					prepared.captionPayload());
			model.setPendingExportSave(pending);
			saveStaged(session, model, pending, owner);
		}

		void closeFactory() {
			if (!factoryClosed.compareAndSet(false, true)) {
				return;
			}
			try {
				factory.close();
			} catch (RuntimeException ignored) {
				// Best effort.
			}
			try {
				provider.close();
			} catch (RuntimeException ignored) {
				// The coordinator normally closes it; best effort here too.
			}
		}
	}
}
