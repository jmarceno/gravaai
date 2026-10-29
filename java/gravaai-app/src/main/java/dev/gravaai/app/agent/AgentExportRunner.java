package dev.gravaai.app.agent;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import dev.gravaai.app.editor.CursorTelemetryLoader;
import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.app.export.ProjectExporter;
import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.compose.CompositorExportFrameProvider;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.media.compose.ProjectFrameInputFactory;
import dev.gravaai.media.export.ExportPublisher;
import dev.gravaai.media.export.ExportRunner;
import dev.gravaai.services.export.ProjectExport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the agent's {@code job start kind:"export"} work headlessly (plan §5.16/P3.2b).
 *
 * <p>This mirrors {@code ProjectExporter.runTo} but honours the MCP export options (format,
 * quality, fps, captions sidecar) that the skill recipe passes, and reuses the same production
 * pipeline: {@link ProjectExport} for dimensions/audio/captions, a
 * {@link CompositorExportFrameProvider} for frames, and {@link ExportRunner} for encoding and
 * publishing. It runs on a background thread, so the MCP call returns at once and the dispatcher's
 * {@code job wait} polls progress.
 */
final class AgentExportRunner {

	/** What the job reports back to the agent registry on success. */
	record ExportResult(Path path, long bytes, int frames, String format, double durationSeconds) {
		ObjectNode toJson() {
			ObjectNode node = Json.nodeFactory().objectNode();
			node.put("path", path.toString());
			node.put("bytes", bytes);
			node.put("frames", frames);
			node.put("format", format);
			node.put("durationSeconds", durationSeconds);
			return node;
		}
	}

	private AgentExportRunner() {}

	/**
	 * Resolves the export destination from the job options.
	 *
	 * @throws IllegalArgumentException when {@code path} is missing or relative (the tool contract
	 *         requires an absolute path, like the React main process did)
	 */
	static Path destination(JsonNode options) {
		JsonNode pathNode = options == null ? null : options.path("path");
		if (pathNode == null || !pathNode.isString() || pathNode.asString().isBlank()) {
			throw new IllegalArgumentException(
					"export needs options.path: an absolute destination file");
		}
		Path destination = Path.of(pathNode.asString());
		if (!destination.isAbsolute()) {
			throw new IllegalArgumentException("export \"path\" must be an absolute path");
		}
		String format = format(options);
		String lower = destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		if ("gif".equals(format) && !lower.endsWith(".gif")) {
			throw new IllegalArgumentException("a gif export needs a .gif destination");
		}
		if ("mp4".equals(format) && !lower.endsWith(".mp4")) {
			throw new IllegalArgumentException("an mp4 export needs a .mp4 destination");
		}
		return destination;
	}

	/** {@code mp4} (default) or {@code gif}. */
	static String format(JsonNode options) {
		JsonNode value = options == null ? null : options.path("format");
		String format = value != null && value.isString() ? value.asString().toLowerCase(
				java.util.Locale.ROOT) : "mp4";
		if (!"mp4".equals(format) && !"gif".equals(format)) {
			throw new IllegalArgumentException("unknown export format \"" + format
					+ "\"; use mp4 or gif");
		}
		return format;
	}

	/** Exports the current editor state to {@code destination} on the calling thread. */
	static ExportResult export(EditorSession session, ProjectExporter exporter,
			JsonNode options) throws IOException {
		Path destination = destination(options);
		String format = format(options);
		ProjectEditorState state = session.store().state();
		long editedMs = session.timelineDataSource().totalDurationMs();
		double durationSeconds = editedMs / 1000.0;

		ExportPreferences preferences = preferences(exporter, options, format);

		Path videoPath = Path.of(session.videoPath());
		Path webcamPath = session.webcamPath();
		Path wallpaperRoot = stageWallpapers(state.wallpaper());
		try {
			ProjectExport.Input input = new ProjectExport.Input(state, videoPath, webcamPath,
					List.of(), preferences, destination, false, true, durationSeconds);
			ProjectExport.Prepared prepared = ProjectExport.prepare(input);
			List<CursorSample> cursorSamples;
			try {
				cursorSamples = CursorTelemetryLoader.load(session.videoPath());
			} catch (RuntimeException error) {
				cursorSamples = List.of();
			}
			try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(state,
					prepared.videoPath(), prepared.webcamPath(), cursorSamples,
					prepared.durationSeconds(), 0, wallpaperRoot, prepared.frameRate());
					CompositorExportFrameProvider provider = new CompositorExportFrameProvider(
							prepared.outputWidth(), prepared.outputHeight(), prepared.frameRate(),
							factory, FrameOverlayHooks.NONE)) {
				ExportRunner.Outcome outcome = ProjectExport.run(input, provider,
						new ExportPublisher(null));
				return new ExportResult(outcome.path(), outcome.bytes(), outcome.frames(), format,
						outcome.durationSeconds());
			}
		} finally {
			deleteRecursively(wallpaperRoot);
		}
	}

	private static ExportPreferences preferences(ProjectExporter exporter, JsonNode options,
			String format) {
		ObjectNode raw = exporter == null ? ExportPreferences.defaults().toJson()
				: exporter.exportPreferences().toJson();
		raw.put("exportFormat", format);
		JsonNode quality = options == null ? null : options.path("quality");
		if (quality != null && quality.isString()) {
			raw.put("exportQuality", quality.asString().toLowerCase(java.util.Locale.ROOT));
		}
		JsonNode fps = options == null ? null : options.path("fps");
		if (fps != null && fps.isNumber()) {
			if ("gif".equals(format)) {
				raw.put("gifFrameRate", fps.asInt());
			} else {
				raw.put("mp4FrameRate", fps.asInt());
			}
		}
		JsonNode sidecar = options == null ? null : options.path("captionsSidecar");
		if (sidecar != null && sidecar.isBoolean()) {
			raw.put("includeCaptionSidecar", sidecar.booleanValue());
		}
		return ExportPreferences.normalizeExportPreferences(raw);
	}

	/** Same wallpaper staging as {@code ProjectExporter.stageWallpapers} (private there). */
	private static Path stageWallpapers(String wallpaper) {
		if (wallpaper == null || !wallpaper.startsWith("/wallpapers/")) {
			return null;
		}
		String name = wallpaper.substring("/wallpapers/".length());
		URL resource = AgentExportRunner.class.getResource("/ui/editor/wallpapers/" + name);
		if (resource == null) {
			return null;
		}
		try {
			Path root = Files.createTempDirectory("gravaai-agent-export-wallpapers");
			Path target = root.resolve("wallpapers").resolve(name);
			Files.createDirectories(target.getParent());
			try (var stream = resource.openStream()) {
				Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return root;
		} catch (IOException error) {
			return null;
		}
	}

	private static void deleteRecursively(Path root) {
		if (root == null) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Best effort temp cleanup.
				}
			});
		} catch (IOException ignored) {
			// Best effort temp cleanup.
		}
	}
}
