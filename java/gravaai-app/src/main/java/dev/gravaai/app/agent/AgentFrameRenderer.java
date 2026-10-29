package dev.gravaai.app.agent;

import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import dev.gravaai.app.editor.CursorTelemetryLoader;
import dev.gravaai.app.editor.EditorSession;
import dev.gravaai.core.editor.ExportPreferences;
import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.compose.CompositorExportFrameProvider;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.media.compose.FrameOverlayHooks;
import dev.gravaai.media.compose.ProjectFrameInputFactory;
import dev.gravaai.services.export.ProjectExport;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders agent {@code frames} images offscreen through the production compositor (plan §5.16:
 * "frames renders PNGs with the compositor offscreen and needs no UI").
 *
 * <p>This is the P3.2b replacement for the React path, which asked the renderer main process to
 * capture the visible preview widget. It builds the same {@link ProjectFrameInputFactory} +
 * {@link CompositorExportFrameProvider} pipeline the exporter uses, renders the requested timeline
 * times, and encodes each frame as a PNG so the MCP client can look at the edit. Extension render
 * hooks are not installed here (the app owns the extension host); with no extensions enabled the
 * output is identical to the export frames.
 */
// Null-safety burn-down: NullAway still reports this type. Fix it and drop the annotation.
@org.jspecify.annotations.NullUnmarked
final class AgentFrameRenderer {

	/** One rendered frame ready for the MCP result. */
	record FrameImage(String mimeType, String data, double timeSeconds) {}

	private static final int FRAME_RATE = 30;
	private static final int MIN_WIDTH = 320;
	private static final int MAX_WIDTH = 1280;

	private AgentFrameRenderer() {}

	/**
	 * Renders one PNG per timeline time.
	 *
	 * @param session the open editor session (state, source paths, edited duration)
	 * @param timesSeconds timeline times in seconds, already clamped by the dispatcher
	 * @param requestedWidth capture width (320–1280), proportionally scaled to the project frame
	 */
	static List<FrameImage> render(EditorSession session, List<Double> timesSeconds,
			int requestedWidth) throws IOException {
		ProjectEditorState state = session.store().state();
		long editedMs = session.timelineDataSource().totalDurationMs();
		if (editedMs <= 0) {
			throw new IllegalArgumentException("the timeline is empty; there is nothing to look at");
		}
		Path videoPath = videoPath(session);
		Path webcamPath = session.webcamPath();
		int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, requestedWidth));

		// The export pipeline decides output dimensions (rotation, aspect, crop, quality); the
		// frames tool only needs the aspect ratio to size the capture.
		ExportPreferences preferences = ExportPreferences.normalizeExportPreferences(
				Json.nodeFactory().objectNode().put("exportQuality", "source"));
		Path placeholder = Path.of(System.getProperty("java.io.tmpdir"), "agent-frames.mp4");
		ProjectExport.Input probe = new ProjectExport.Input(state, videoPath, webcamPath, List.of(),
				preferences, placeholder, false, true, editedMs / 1000.0);
		ProjectExport.Prepared prepared = ProjectExport.prepare(probe);
		int height = Math.max(2, (int) Math.round(
				(double) width * prepared.outputHeight() / prepared.outputWidth()));
		height = height % 2 == 0 ? height : height + 1;

		Path wallpaperRoot = stageWallpapers(state.wallpaper());
		List<CursorSample> cursorSamples;
		try {
			cursorSamples = CursorTelemetryLoader.load(session.videoPath());
		} catch (RuntimeException error) {
			cursorSamples = List.of();
		}
		List<FrameImage> frames = new ArrayList<>();
		try (ProjectFrameInputFactory factory = new ProjectFrameInputFactory(state, videoPath,
				webcamPath, cursorSamples, editedMs / 1000.0, 0, wallpaperRoot, FRAME_RATE);
				CompositorExportFrameProvider provider = new CompositorExportFrameProvider(width,
						height, FRAME_RATE, factory, FrameOverlayHooks.NONE)) {
			ByteBuffer buffer = ByteBuffer.allocate(width * height * 4);
			long index = 0;
			for (double time : timesSeconds) {
				provider.reset();
				buffer.clear();
				provider.renderFrame(index++, clampTime(time, editedMs / 1000.0), buffer);
				frames.add(new FrameImage("image/png", encodePng(buffer, width, height), time));
			}
		} finally {
			deleteRecursively(wallpaperRoot);
		}
		return frames;
	}

	private static double clampTime(double seconds, double durationSeconds) {
		if (!Double.isFinite(seconds) || seconds < 0) {
			return 0;
		}
		return Math.min(seconds, Math.max(0, durationSeconds));
	}

	private static @Nullable Path videoPath(EditorSession session) {
		String path = session.videoPath();
		if (path == null || path.isBlank()) {
			throw new IllegalStateException("the project has no source video");
		}
		return Path.of(path);
	}

	private static @Nullable String encodePng(ByteBuffer rgba, int width, int height) throws IOException {
		int[] pixels = new int[width * height];
		ByteBuffer source = rgba.duplicate();
		source.position(0);
		for (int index = 0; index < pixels.length; index++) {
			int r = source.get() & 0xFF;
			int g = source.get() & 0xFF;
			int b = source.get() & 0xFF;
			int a = source.get() & 0xFF;
			pixels[index] = (a << 24) | (r << 16) | (g << 8) | b;
		}
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, pixels, 0, width);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return Base64.getEncoder().encodeToString(out.toByteArray());
	}

	/**
	 * Copies the project wallpaper resource into a temp {@code wallpapers/} root so the compositor
	 * can read it from the filesystem (same staging as the exporter's {@code stageWallpapers}).
	 */
	private static @Nullable Path stageWallpapers(@Nullable String wallpaper) {
		if (wallpaper == null || !wallpaper.startsWith("/wallpapers/")) {
			return null;
		}
		String name = wallpaper.substring("/wallpapers/".length());
		URL resource = AgentFrameRenderer.class.getResource("/ui/editor/wallpapers/" + name);
		if (resource == null) {
			return null;
		}
		try {
			Path root = Files.createTempDirectory("gravaai-agent-wallpapers");
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

	/** Unused helper kept for the frames text summary (frame plan formatting). */
	static String formatTime(double seconds) {
		if (!Double.isFinite(seconds) || seconds < 0) {
			return "0:00.0";
		}
		long minutes = (long) Math.floor(seconds / 60);
		double rest = seconds - minutes * 60;
		return minutes + ":" + String.format(Locale.ROOT, "%04.1f", rest);
	}
}
