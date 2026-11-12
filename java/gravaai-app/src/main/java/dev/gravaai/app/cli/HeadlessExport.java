package dev.gravaai.app.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.gravaai.app.CliCommand;
import dev.gravaai.app.editor.CursorTelemetryLoader;
import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.app.export.WallpaperStaging;
import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.media.compose.CompositorExportFrameProvider;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.media.compose.ProjectFrameInputFactory;
import dev.gravaai.media.export.ExportException;
import dev.gravaai.media.export.ExportProgress;
import dev.gravaai.media.export.ExportProgressListener;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.media.export.ExportRunner;
import dev.gravaai.media.segmentation.LazyWebcamSegmenter;
import dev.gravaai.media.segmentation.WebcamMaskQuality;
import dev.gravaai.services.elements.ElementDiagnostics;
import dev.gravaai.services.elements.ElementOverlayHooks;
import dev.gravaai.services.export.ProjectExport;
import dev.gravaai.services.extensions.ExtensionFrameOverlayHooks;
import dev.gravaai.services.extensions.ExtensionHost;
import dev.gravaai.services.meeting.FileTrees;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code gravaai export <project> --out <file>}: render a project file without opening the editor
 * (card "Headless CLI").
 *
 * <p>It is the editor's export pipeline without the editor: {@link ProjectExport} assembles the
 * request (dimensions, audio plan, caption and chapter sidecars), {@link ProjectFrameInputFactory}
 * resolves each frame and {@link CompositorExportFrameProvider} composites it on its own EGL
 * context. The only things the GUI adds are the popover, the save dialog and progress to a window.
 *
 * <p>Preferences come from {@code app-settings.json} — the keys the export popover writes — and the
 * request overrides {@code --format}, {@code --preset} and {@code --fps} on top. A raw recording is
 * exported as the one-clip project {@link EditorSession#recordingDocument} defines, so the CLI and
 * the editor see the same timeline.
 */
final class HeadlessExport {

	private static final System.Logger LOG = System.getLogger("gravaai.cli.export");

	/**
	 * What an export reads from a document.
	 *
	 * @param state the resolved editor state
	 * @param videoPath the source recording
	 * @param webcamPath the linked webcam track, or empty when the project has none
	 * @param durationSeconds the source duration
	 */
	private record Source(dev.gravaai.core.project.ProjectEditorState state, Path videoPath,
			Optional<Path> webcamPath, double durationSeconds) {
	}

	private final CliEvents events;

	HeadlessExport(CliEvents events) {
		this.events = events;
	}

	/**
	 * Runs one export.
	 *
	 * @param request the parsed request
	 * @param preferences the persisted export preferences
	 * @param extensionsDir the extensions directory to render hooks from, or empty for none
	 * @return the process exit code (0 on success)
	 */
	int run(CliCommand.Export request, ExportPreferences preferences,
			Optional<Path> extensionsDir) {
		Path project = request.project().toAbsolutePath().normalize();
		Path destination = request.out().toAbsolutePath().normalize();
		try {
			if (Files.exists(destination) && !request.overwrite()) {
				return fail("destination already exists: " + destination
						+ " (pass --overwrite to replace it)");
			}
			Source source = open(project);
			ExportPreferences effective = effectivePreferences(preferences, request);
			ExportRunner.Outcome outcome = render(source, effective, destination, extensionsDir);

			Map<String, Object> facts = new LinkedHashMap<>();
			facts.put("bytes", outcome.bytes());
			facts.put("frames", outcome.frames());
			facts.put("durationSeconds", outcome.durationSeconds());
			facts.put("format", request.format().wire());
			events.result(outcome.path(), facts);
			events.note("exported " + outcome.path() + " (" + outcome.bytes() + " bytes, "
					+ outcome.frames() + " frames)");
			return 0;
		} catch (ExportException error) {
			return fail(message(error));
		} catch (IOException | RuntimeException | LinkageError error) {
			LOG.log(System.Logger.Level.WARNING, "headless export failed: " + error);
			return fail(message(error));
		}
	}

	/** Reports the failure on both streams and answers the exit code. */
	private int fail(String message) {
		events.emit("error", Map.of("message", message));
		events.note("gravaai export: " + message);
		return 1;
	}

	private static String message(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.toString() : message;
	}

	/**
	 * The preferences one run uses: the persisted export settings with the request's format, preset
	 * and frame rate on top. Normalising through the JSON form keeps every other key, and the enum
	 * parsing, in {@link ExportPreferences} where they belong.
	 */
	private static ExportPreferences effectivePreferences(ExportPreferences persisted,
			CliCommand.Export request) {
		ObjectNode raw = persisted.toJson();
		raw.put("exportFormat", request.format().wire());
		raw.put("exportQuality", request.quality().wire());
		request.frameRate().ifPresent(rate -> raw.put(
				request.format() == CliCommand.Export.Format.GIF ? "gifFrameRate" : "mp4FrameRate",
				rate));
		return ExportPreferences.normalizeExportPreferences(raw);
	}

	/**
	 * Opens the source: a {@code .gravaai} project, or a raw recording through the same document the
	 * editor builds for a fresh take.
	 */
	private Source open(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("no such file: " + file);
		}
		boolean projectFile = CliCommand.extensionOf(file.toString())
				.map("gravaai"::equalsIgnoreCase).orElse(false);
		ProjectDocument document = projectFile
				? EditorSession.loadDocumentForExport(file)
				: EditorSession.recordingDocument(file, Optional.empty(), 0);
		Path video = Path.of(document.videoPath());
		if (!Files.isRegularFile(video)) {
			throw new IllegalArgumentException(projectFile
					? "the project's video is missing: " + video
					: "not a readable recording: " + file);
		}
		double duration = probeDuration(video);
		if (duration <= 0) {
			throw new IllegalArgumentException("could not read a duration from " + video);
		}
		events.emit("export_start", Map.of("source", video.toString()));
		events.note("exporting " + file.getFileName() + " (" + duration + " s)");
		return new Source(document.editor(), video, EditorSession.webcamPathFor(document), duration);
	}

	private static double probeDuration(Path video) {
		try {
			double seconds = dev.gravaai.media.ffmpeg.MediaProbe.probe(video).durationSeconds();
			return Double.isFinite(seconds) && seconds > 0 ? seconds : 0;
		} catch (RuntimeException | LinkageError error) {
			LOG.log(System.Logger.Level.WARNING, "could not probe " + video + ": " + error);
			return 0;
		}
	}

	/** Renders and publishes one export, releasing every frame resource on every path. */
	private ExportRunner.Outcome render(Source source, ExportPreferences preferences,
			Path destination, Optional<Path> extensionsDir) throws IOException {
		List<CursorSample> cursorSamples = CursorTelemetryLoader.load(source.videoPath().toString());
		// ProjectExport.Input models "no webcam track" as a null path (a project without one).
		ProjectExport.Input input = new ProjectExport.Input(source.state(), source.videoPath(),
				source.webcamPath().orElse(null), List.of(), preferences, destination, false, true,
				source.durationSeconds(), cursorSamples)
				// On this thread a missing denoised copy is rendered and awaited, like the queue.
				.withDenoised(dev.gravaai.services.denoise.DenoiseCache.shared()::ensureBlocking);
		ProjectExport.Prepared prepared = ProjectExport.prepare(input);
		// The encoder reports 0% on its first tick; announcing the resolved geometry here means the
		// preparation line is the one that carries it, rather than a bare 0% arriving right after.
		events.progress(0, Optional.of(prepared.outputWidth() + "x" + prepared.outputHeight() + " "
				+ prepared.frameRate() + " fps"));

		Optional<ExtensionHost> extensions = openExtensions(extensionsDir);
		// A project wallpaper can be absent from the bundle; ProjectFrameInputFactory models that
		// as a null root and the compositor then draws black.
		Optional<Path> wallpaperRoot = Optional.ofNullable(
				WallpaperStaging.stage(source.state().wallpaper(), "gravaai-cli-export-wallpapers"));
		try (ElementOverlayHooks elements = new ElementOverlayHooks(
				extensions.map(host -> (FrameOverlayHooks) new ExtensionFrameOverlayHooks(host,
						() -> prepared.durationSeconds() * 1000.0)).orElse(FrameOverlayHooks.NONE),
				new ElementDiagnostics(), source::state);
				ProjectFrameInputFactory factory = new ProjectFrameInputFactory(source.state(),
						prepared.videoPath(), prepared.webcamPath(), cursorSamples,
						prepared.durationSeconds(), 0, wallpaperRoot.orElse(null),
						prepared.frameRate(),
						new LazyWebcamSegmenter(WebcamMaskQuality.PRECISE),
						prepared.outputWidth(), prepared.outputHeight());
				CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
						prepared.outputWidth(), prepared.outputHeight(), prepared.frameRate(),
						factory, elements)) {
			ExportRunner.Outcome outcome = ProjectExport.run(input, provider, progressListener(),
					new ExportPublisher(null));
			// A broken element means the video is missing something the preview shows: fail loudly
			// rather than publish it, exactly as the editor's export does.
			elements.requireNoFailures();
			return outcome;
		} finally {
			wallpaperRoot.ifPresent(FileTrees::deleteQuietly);
			extensions.ifPresent(HeadlessExport::closeQuietly);
		}
	}

	/** Encoder progress to the CLI stream (whole per cent, throttled by {@link CliEvents}). */
	private ExportProgressListener progressListener() {
		return progress -> {
			if (progress.phase() == ExportProgress.Phase.SAVING) {
				// The frames are encoded and the file is being published; keep it below 100 so the
				// final line is the result rather than a progress line that looks done.
				events.progress(99, Optional.of("writing"));
				return;
			}
			events.progress(Math.min(99, progress.percentage()), Optional.empty());
		};
	}

	/**
	 * Opens the extension host when the user has extensions installed.
	 *
	 * <p>A scripted export must produce the same video the app would, so the hooks are installed
	 * exactly when the editor installs them. A host with nothing active answers empty and is closed
	 * at once, keeping the frame path identical to the pre-extension pipeline.
	 */
	private static Optional<ExtensionHost> openExtensions(Optional<Path> extensionsDir) {
		if (extensionsDir.isEmpty() || !Files.isDirectory(extensionsDir.get())) {
			return Optional.empty();
		}
		ExtensionHost host = new ExtensionHost(extensionsDir.get());
		try {
			int total = host.enableAll().size();
			if (host.activeCount() == 0) {
				closeQuietly(host);
				return Optional.empty();
			}
			LOG.log(System.Logger.Level.INFO,
					"headless export: " + host.activeCount() + " active of " + total + " extensions");
			return Optional.of(host);
		} catch (RuntimeException error) {
			LOG.log(System.Logger.Level.WARNING, "extensions unavailable: " + error);
			closeQuietly(host);
			return Optional.empty();
		}
	}

	private static void closeQuietly(AutoCloseable resource) {
		try {
			resource.close();
		} catch (Exception error) {
			LOG.log(System.Logger.Level.DEBUG, "closing export resource: " + error);
		}
	}
}
