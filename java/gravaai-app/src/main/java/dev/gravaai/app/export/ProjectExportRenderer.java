package dev.gravaai.app.export;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import dev.gravaai.app.editor.CursorTelemetryLoader;
import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.compose.CompositorExportFrameProvider;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.media.compose.ProjectFrameInputFactory;
import dev.gravaai.media.export.ExportFrameProvider;
import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.media.export.ExportRunner;
import dev.gravaai.media.segmentation.LazyWebcamSegmenter;
import dev.gravaai.media.segmentation.WebcamMaskQuality;
import dev.gravaai.services.elements.ElementDiagnostics;
import dev.gravaai.services.elements.ElementOverlayHooks;
import dev.gravaai.services.export.ExportCoordinator;
import dev.gravaai.services.export.ExportQueue;
import dev.gravaai.services.export.ProjectExport;
import dev.gravaai.services.extensions.ExtensionFrameOverlayHooks;
import dev.gravaai.services.extensions.ExtensionHost;

/**
 * The app-side render half of the batch export queue (card "Batch export queue"): loads a queued
 * project headlessly, builds the production compositor frame pipeline and drives the shared
 * {@link ExportCoordinator}, which owns the actual encode and publish.
 *
 * <p>The queue core ({@link ExportQueue}) owns ordering and state; one renderer instance serves
 * every job. {@link #start} answers {@link Optional#empty()} while another export holds the
 * coordinator (a manual editor export, for example), and the queue keeps the job queued until the
 * app pumps it again from {@link ExportCoordinator#addIdleListener(Runnable)}. After a start
 * attempt loses the race and finds the coordinator busy, the renderer itself waits for the next
 * idle notification and retries, so the job starts even if nobody calls {@code pump()} again.
 *
 * <p>Rendering runs on a virtual thread named {@value #THREAD_NAME}. The frame pipeline — frame
 * provider, {@link ProjectFrameInputFactory}, the element lane with its extension hooks and the
 * staged wallpapers — is closed exactly once on every terminal path (completion, cancellation and
 * failure). Jobs are rendered with the timeline maths of {@link ProjectExport}; the input asks
 * {@code prepare} to probe the source duration, matching the editor's own export.
 *
 * <p>When the element lane reports a failure, the encoded file has already been published by the
 * coordinator; the queue entry is then reported failed with the element message, so the user knows
 * why the video is missing an element instead of believing it is complete.
 *
 * <p>Threading: {@link #start} and {@link Handle#cancel()} may be called from any thread; the
 * callbacks reach the queue on the virtual thread or on the coordinator's export thread.
 */
public final class ProjectExportRenderer implements ExportQueue.Renderer {

	/** The virtual thread that loads and starts one queued export. */
	private static final String THREAD_NAME = "gravaai-queue-export";

	private static final System.Logger LOG = System.getLogger("gravaai.export.queue");

	private final ExportCoordinator coordinator;
	private final FramePipelineFactory pipelineFactory;
	/** Extension render hooks (plan §5.18); null keeps queued exports extension-free. */
	private volatile @Nullable ExtensionHost extensionHost;
	/**
	 * Opens the deep-zoom upscaler for one export, or answers null when the preference is off or
	 * the model is not installed. Queued exports honour the same setting as the editor's export.
	 */
	private volatile Supplier<dev.gravaai.media.superres.@Nullable SuperResolutionEngine>
			superResolution = () -> null;

	/**
	 * Production renderer: every queued export is rendered through the compositor pipeline with the
	 * same wiring as the editor's export.
	 *
	 * @param coordinator the shared export coordinator (one export at a time)
	 */
	public ProjectExportRenderer(ExportCoordinator coordinator) {
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		this.pipelineFactory = this::productionPipeline;
	}

	/**
	 * Test seam: builds the frame half through {@code pipelineFactory} instead of the compositor, so
	 * the load/prepare/coordinator/callback half can be exercised without GL (see
	 * {@code ProjectExportRendererTest}).
	 *
	 * @param coordinator the shared export coordinator
	 * @param pipelineFactory builds the frame provider for one prepared export
	 */
	ProjectExportRenderer(ExportCoordinator coordinator, FramePipelineFactory pipelineFactory) {
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		this.pipelineFactory = Objects.requireNonNull(pipelineFactory, "pipelineFactory");
	}

	/**
	 * Installs the extension host whose render hooks run in every queued frame (plan §5.18),
	 * mirroring {@link ProjectExporter}. Null keeps the renderer extension-free; a host without
	 * active extensions renders the same frames as the pre-extension pipeline.
	 *
	 * @param host the extension host, or null to render no extension hooks
	 */
	public void setExtensionHost(@Nullable ExtensionHost host) {
		this.extensionHost = host;
	}

	/**
	 * Installs the deep-zoom upscaler source ("Upscale deep zooms in exports"). The supplier is
	 * asked once per queued export, on the render thread; the engine it returns is owned by that
	 * export's frame provider, which closes it with the GL context.
	 */
	public void setSuperResolution(
			Supplier<dev.gravaai.media.superres.@Nullable SuperResolutionEngine> source) {
		this.superResolution = Objects.requireNonNull(source, "source");
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns {@link Optional#empty()} synchronously while the coordinator is busy; the queue
	 * then keeps the job queued and asks again on the next pump.
	 */
	@Override
	public Optional<Handle> start(ExportQueue.Job job, Callbacks callbacks) {
		Objects.requireNonNull(job, "job");
		Objects.requireNonNull(callbacks, "callbacks");
		if (coordinator.isBusy()) {
			return Optional.empty();
		}
		Run run = new Run(job, callbacks);
		Thread.ofVirtual().name(THREAD_NAME).start(run::render);
		return Optional.of(run);
	}

	/** The extension hooks for one export, bound to the prepared timeline duration. */
	private FrameOverlayHooks extensionHooks(double durationSeconds) {
		ExtensionHost host = extensionHost;
		if (host == null) {
			return FrameOverlayHooks.NONE;
		}
		double durationMs = Double.isFinite(durationSeconds) ? durationSeconds * 1000.0 : 0.0;
		return new ExtensionFrameOverlayHooks(host, () -> durationMs);
	}

	/**
	 * The production frame half: wallpapers staged from the UI resources, the element lane with the
	 * extension hooks, the project frame input factory and the compositor provider — the same
	 * wiring as {@link ProjectExporter}.
	 */
	private FramePipeline productionPipeline(ProjectExport.Prepared prepared,
			ProjectEditorState state, List<CursorSample> cursorSamples) {
		Path wallpaperRoot = WallpaperStaging.stage(state.wallpaper(),
				"gravaai-queue-export-wallpapers");
		ElementOverlayHooks elementHooks = new ElementOverlayHooks(
				extensionHooks(prepared.durationSeconds()), new ElementDiagnostics(), () -> state);
		ProjectFrameInputFactory inputFactory = new ProjectFrameInputFactory(state,
				prepared.videoPath(), prepared.webcamPath(), cursorSamples,
				prepared.durationSeconds(), 0, wallpaperRoot, prepared.frameRate(),
				new LazyWebcamSegmenter(WebcamMaskQuality.PRECISE),
				prepared.outputWidth(), prepared.outputHeight());
		dev.gravaai.media.superres.@Nullable SuperResolutionEngine engine = null;
		try {
			engine = superResolution.get();
		} catch (RuntimeException error) {
			// The upscaler is optional: a queued export never fails because it is unusable.
			LOG.log(System.Logger.Level.WARNING,
					"super-resolution unavailable for the queued export: " + error);
		}
		CompositorExportFrameProvider provider;
		try {
			provider = new CompositorExportFrameProvider(
					prepared.outputWidth(), prepared.outputHeight(), prepared.frameRate(),
					inputFactory, elementHooks, engine);
		} catch (RuntimeException error) {
			// No provider took ownership: release what this method opened.
			closeQuietly(engine);
			closeQuietly(inputFactory);
			closeQuietly(elementHooks);
			deleteRecursively(wallpaperRoot);
			throw error;
		}
		return new FramePipeline(provider, elementHooks::failureMessage,
				List.of(inputFactory, provider, elementHooks), wallpaperRoot);
	}

	/**
	 * Builds the frame half of one queued export (the test seam).
	 *
	 * <p>The production factory is the renderer itself ({@link ProjectExportRenderer#productionPipeline});
	 * tests inject a pipeline backed by a synthetic frame provider so the render flow can run
	 * without GL.
	 */
	@FunctionalInterface
	interface FramePipelineFactory {

		/**
		 * @param prepared the assembled export (dimensions, frame rate, media paths)
		 * @param state the resolved project state
		 * @param cursorSamples the project's cursor telemetry (usually empty)
		 * @return the frame provider plus every resource it owns
		 */
		FramePipeline create(ProjectExport.Prepared prepared, ProjectEditorState state,
				List<CursorSample> cursorSamples);
	}

	/**
	 * The frame half of one export: the provider the coordinator renders through plus the resources
	 * it owns. {@link #close()} releases the frame input factory, the compositor provider and the
	 * element runtime in that order, then deletes the staged wallpapers; it is idempotent, so the
	 * renderer and the coordinator may both close it.
	 */
	static final class FramePipeline implements AutoCloseable {

		private final ExportFrameProvider frames;
		private final Supplier<Optional<String>> failureMessage;
		private final List<AutoCloseable> resources;
		private final @Nullable Path wallpaperRoot;
		private final AtomicBoolean closed = new AtomicBoolean();

		/**
		 * @param frames the frame provider the coordinator drives
		 * @param failureMessage the element lane's first failure, if any
		 * @param resources every resource to close when the export ends
		 * @param wallpaperRoot the staged wallpaper temp root to delete, or null
		 */
		FramePipeline(ExportFrameProvider frames, Supplier<Optional<String>> failureMessage,
				List<AutoCloseable> resources, @Nullable Path wallpaperRoot) {
			this.frames = Objects.requireNonNull(frames, "frames");
			this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
			this.resources = List.copyOf(resources);
			this.wallpaperRoot = wallpaperRoot;
		}

		/** A pipeline owning nothing but the frame provider (tests keep no GL resources). */
		static FramePipeline of(ExportFrameProvider frames) {
			return new FramePipeline(frames, () -> Optional.empty(), List.of(), null);
		}

		/** The frame provider the coordinator renders through. */
		ExportFrameProvider frames() {
			return frames;
		}

		/** Why the element lane failed while rendering, if it did. */
		Optional<String> failure() {
			try {
				return failureMessage.get();
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.DEBUG, "reading element failures: " + error);
				return Optional.empty();
			}
		}

		@Override
		public void close() {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			for (AutoCloseable resource : resources) {
				closeQuietly(resource);
			}
			deleteRecursively(wallpaperRoot);
		}
	}

	/**
	 * One queued export: the render flow on the virtual thread, the coordinator listener and the
	 * cancel handle. All mutable state is guarded by {@link #lock}.
	 */
	private final class Run implements Handle, ExportCoordinator.Listener {

		private final ExportQueue.Job job;
		private final Callbacks callbacks;
		private final Object lock = new Object();
		private @Nullable FramePipeline pipeline;
		/** The idle latch of the current start attempt; cancelled while waiting when the job is. */
		private @Nullable CountDownLatch pendingIdle;
		private boolean cancelled;
		private boolean ownsPipeline;
		private boolean finished;

		Run(ExportQueue.Job job, Callbacks callbacks) {
			this.job = job;
			this.callbacks = callbacks;
		}

		/** Loads the project, builds the pipeline and starts the export (virtual thread). */
		void render() {
			try {
				ProjectDocument document = EditorSession.loadDocumentForExport(job.source());
				ProjectEditorState state = document.editor();
				String videoPath = document.videoPath();
				if (videoPath == null || videoPath.isBlank()) {
					finishFailed("the project has no source video: " + job.source());
					return;
				}
				List<CursorSample> cursorSamples = CursorTelemetryLoader.load(videoPath);
				// The one justified null: the input models a project without a webcam track.
				@Nullable Path webcamPath = EditorSession.webcamPathFor(document).orElse(null);
				ProjectExport.Prepared prepared = ProjectExport.prepare(new ProjectExport.Input(
						state, Path.of(videoPath), webcamPath, List.of(),
						job.preferences(), job.destination(), false, false, 0, cursorSamples));
				FramePipeline frames = pipelineFactory.create(prepared, state, cursorSamples);
				if (!adopt(frames)) {
					return;
				}
				if (!startOnCoordinator(prepared, frames)) {
					finishCancelled();
				}
			} catch (IOException | RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING, "queued export could not start: " + error);
				finishFailed(errorMessage(error));
			}
		}

		/**
		 * Starts the export on the coordinator, waiting (without polling) for the pipeline to go
		 * idle when another export got there first.
		 *
		 * @return {@code false} when the job was cancelled before it could start
		 */
		private boolean startOnCoordinator(ProjectExport.Prepared prepared, FramePipeline frames) {
			while (true) {
				CountDownLatch idle = new CountDownLatch(1);
				synchronized (lock) {
					if (cancelled || finished) {
						return false;
					}
					pendingIdle = idle;
				}
				// The subscription must outlive the wait: the export that beat us usually runs for
				// minutes, and its idle notification is the only thing that wakes this job up.
				AutoCloseable idleSubscription = coordinator.addIdleListener(idle::countDown);
				try {
					if (isCancelled()) {
						return false;
					}
					if (coordinator.start(prepared.request(), frames.frames(), this)) {
						boolean abort = markOwned();
						if (abort) {
							// Cancelled while starting: stop the render we just handed over.
							coordinator.cancel();
						}
						return true;
					}
					awaitIdle(idle);
				} finally {
					closeQuietly(idleSubscription);
					clearPendingIdle(idle);
				}
			}
		}

		/**
		 * Marks the pipeline as owned by this run and reports whether it was cancelled meanwhile.
		 *
		 * @return {@code true} when the export was started but must be cancelled immediately
		 */
		private boolean markOwned() {
			synchronized (lock) {
				pendingIdle = null;
				if (finished) {
					return false;
				}
				if (cancelled) {
					return true;
				}
				ownsPipeline = true;
				return false;
			}
		}

		private boolean isCancelled() {
			synchronized (lock) {
				return cancelled;
			}
		}

		private void awaitIdle(CountDownLatch idle) {
			boolean interrupted = false;
			while (true) {
				try {
					idle.await();
					break;
				} catch (InterruptedException error) {
					interrupted = true;
				}
			}
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}

		private void clearPendingIdle(CountDownLatch idle) {
			synchronized (lock) {
				if (pendingIdle == idle) {
					pendingIdle = null;
				}
			}
		}

		/** Stores the frame pipeline unless the job was already cancelled (it is closed then). */
		private boolean adopt(FramePipeline frames) {
			boolean cancelledNow;
			synchronized (lock) {
				pipeline = frames;
				cancelledNow = cancelled;
			}
			if (cancelledNow) {
				finishCancelled();
				return false;
			}
			return true;
		}

		@Override
		public void cancel() {
			CountDownLatch wait;
			boolean stopExport;
			synchronized (lock) {
				if (finished || cancelled) {
					return;
				}
				cancelled = true;
				wait = pendingIdle;
				// Only the render this run actually started is ours to cancel; a job still queued
				// behind someone else's export must not cancel that export.
				stopExport = ownsPipeline;
			}
			if (wait != null) {
				wait.countDown();
			}
			if (stopExport) {
				coordinator.cancel();
			}
		}

		@Override
		public void onProgress(ExportProgress progress) {
			callbacks.onProgress(progress);
		}

		@Override
		public void onCompleted(ExportRunner.Outcome outcome) {
			// Read before closing: a broken element fails the report even though the encoded file
			// was already published. The queue marks such a job failed (the video is on disk, with
			// the element missing), which is what the editor's export tells the user as well.
			Optional<String> elementFailure = failureOf();
			closePipeline();
			if (!markFinished()) {
				return;
			}
			elementFailure.ifPresentOrElse(callbacks::onFailed,
					() -> callbacks.onCompleted(outcome.path()));
		}

		@Override
		public void onCancelled() {
			closePipeline();
			if (!markFinished()) {
				return;
			}
			callbacks.onCancelled();
		}

		@Override
		public void onFailed(Throwable error) {
			closePipeline();
			if (!markFinished()) {
				return;
			}
			callbacks.onFailed(errorMessage(error));
		}

		/** The element lane's first failure of this run, if any. */
		private Optional<String> failureOf() {
			FramePipeline frames;
			synchronized (lock) {
				frames = pipeline;
			}
			return frames == null ? Optional.empty() : frames.failure();
		}

		/** Closes the frame pipeline exactly once. */
		private void closePipeline() {
			FramePipeline frames;
			synchronized (lock) {
				frames = pipeline;
				pipeline = null;
			}
			if (frames != null) {
				frames.close();
			}
		}

		/** Terminal path for a job that failed before the coordinator started it. */
		private void finishFailed(String message) {
			closePipeline();
			if (!markFinished()) {
				return;
			}
			callbacks.onFailed(message);
		}

		/** Terminal path for a job cancelled before the coordinator started it. */
		private void finishCancelled() {
			closePipeline();
			if (!markFinished()) {
				return;
			}
			callbacks.onCancelled();
		}

		/** Marks the run finished; {@code false} when a terminal callback already won the race. */
		private boolean markFinished() {
			synchronized (lock) {
				if (finished) {
					return false;
				}
				finished = true;
				ownsPipeline = false;
				pendingIdle = null;
				return true;
			}
		}
	}

	private static void closeQuietly(@Nullable AutoCloseable resource) {
		if (resource == null) {
			return;
		}
		try {
			resource.close();
		} catch (Exception error) {
			LOG.log(System.Logger.Level.DEBUG, "closing export resource: " + error);
		}
	}

	private static void deleteRecursively(@Nullable Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			return;
		}
		try (var walk = Files.walk(directory)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException error) {
					LOG.log(System.Logger.Level.DEBUG,
							"could not delete " + path + ": " + error);
				}
			});
		} catch (IOException error) {
			LOG.log(System.Logger.Level.DEBUG, "could not delete " + directory + ": " + error);
		}
	}

	private static String errorMessage(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.toString() : message;
	}
}
