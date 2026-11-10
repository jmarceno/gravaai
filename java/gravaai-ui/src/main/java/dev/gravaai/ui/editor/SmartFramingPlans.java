package dev.gravaai.ui.editor;

import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.gravaai.core.project.ProjectEditorState;
import dev.gravaai.media.compose.CursorSample;
import dev.gravaai.media.compose.SmartFraming;
import dev.gravaai.media.ffmpeg.MediaInfo;
import dev.gravaai.media.ffmpeg.MediaProbe;

/**
 * Smart-framing plans for the editor's UI consumers (the preview and the camera-track indicators),
 * with the media-size probes cached per file so scene rebuilds stay cheap. The plan itself is the
 * media layer's deterministic schedule — the same one export walks — so a move the preview shows
 * is the move the export renders.
 */
public final class SmartFramingPlans {

	private static final System.Logger LOG = System.getLogger(SmartFramingPlans.class.getName());

	/** Probed display sizes (rotation applied) keyed by absolute path. */
	private static final Map<String, int[]> VIDEO_SIZES = new ConcurrentHashMap<>();

	private SmartFramingPlans() {
	}

	/**
	 * The plan for a project state, or null when smart framing is off. The stage size is the
	 * compositor output the plan should reason about (the preview's render target, or a nominal
	 * size for the timeline indicators). {@code webcamPath} overrides the state's source path
	 * when the caller has already resolved it through the project media sources.
	 */
	public static SmartFraming.@Nullable Plan forState(ProjectEditorState state,
			List<CursorSample> samples, @Nullable Path videoPath, @Nullable Path webcamPath,
			double durationSeconds, int stageWidth, int stageHeight) {
		var webcam = state.webcam();
		if (webcam == null || !webcam.smartFraming()) {
			return null;
		}
		int[] video = videoSize(videoPath);
		Path cameraPath = webcamPath != null ? webcamPath : webcamSourcePath(state, videoPath);
		int[] camera = videoSize(cameraPath);
		return SmartFraming.plan(state, samples, durationSeconds, video[0], video[1],
				camera[0], camera[1], stageWidth, stageHeight);
	}

	/** The webcam recording behind the state, resolved against the video when relative. */
	private static @Nullable Path webcamSourcePath(ProjectEditorState state,
			@Nullable Path videoPath) {
		var webcam = state.webcam();
		String source = webcam == null ? null : webcam.sourcePath();
		if (source == null || source.isBlank()) {
			return null;
		}
		Path path;
		try {
			path = Path.of(source);
		} catch (RuntimeException invalid) {
			return null;
		}
		if (Files.isRegularFile(path) || path.isAbsolute() || videoPath == null) {
			return path;
		}
		return videoPath.resolveSibling(path);
	}

	private static int[] videoSize(@Nullable Path path) {
		if (path == null) {
			return new int[] {0, 0};
		}
		return VIDEO_SIZES.computeIfAbsent(path.toAbsolutePath().toString(), key -> {
			try {
				MediaInfo info = MediaProbe.probe(path);
				return info.video()
						.map(stream -> new int[] {
								stream.rotationDegrees() % 180 != 0 ? stream.height()
										: stream.width(),
								stream.rotationDegrees() % 180 != 0 ? stream.width()
										: stream.height()})
						.orElse(new int[] {0, 0});
			} catch (RuntimeException error) {
				LOG.log(System.Logger.Level.WARNING,
						"could not probe " + path + " for smart framing: " + error);
				return new int[] {0, 0};
			}
		});
	}
}
