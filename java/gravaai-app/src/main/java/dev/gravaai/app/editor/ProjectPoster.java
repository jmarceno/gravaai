package dev.gravaai.app.editor;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import dev.gravaai.app.AppBinaries;
import dev.gravaai.core.project.ProjectDocument;
import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.services.projects.ProjectManager;

/**
 * The startup poster shown while an editor is being restored: the project's preview sidecar when
 * there is one, otherwise one frame extracted from the project video.
 *
 * <p>Both lookups used to be private static methods inside {@code AppContext}, and the frame
 * extraction hand-rolled its own {@code ProcessBuilder} with a hard-coded three-second wait. This
 * class owns the whole concern, including the rule that a missing poster is never an error — the
 * skeleton simply keeps its placeholder.
 *
 * <p>Blocking: extraction runs a child process. Callers already invoke it off the JavaFX thread.
 */
public final class ProjectPoster {

	/** One frame must not delay the editor any longer than this. */
	private static final Duration EXTRACTION_TIMEOUT = Duration.ofSeconds(3);

	private ProjectPoster() {}

	/**
	 * The poster for a project: the preview sidecar first, then a frame from the video.
	 *
	 * @param projectPath the project file
	 * @return a temp PNG with an extracted frame, or null when neither source produced one
	 */
	public static @Nullable Path posterFile(Path projectPath) {
		Path sidecar = sidecar(projectPath);
		if (sidecar != null) {
			return sidecar;
		}
		return extractFrame(projectPath);
	}

	/** The project thumbnail sidecar, when it exists. */
	public static @Nullable Path sidecar(Path projectPath) {
		try {
			Path thumbnail = ProjectManager.getProjectThumbnailPath(projectPath);
			return thumbnail != null && Files.isRegularFile(thumbnail) ? thumbnail : null;
		} catch (RuntimeException error) {
			// No poster: the skeleton keeps its placeholder.
			return null;
		}
	}

	/**
	 * One frame from the project video through the bundled ffmpeg CLI.
	 *
	 * @return the temp PNG, or null when the project has no video, ffmpeg fails or times out
	 */
	public static @Nullable Path extractFrame(Path projectPath) {
		String video;
		try {
			video = ProjectDocument.load(projectPath).videoPath();
		} catch (IOException | RuntimeException error) {
			return null;
		}
		if (video == null || video.isBlank()) {
			return null;
		}
		Path poster;
		try {
			poster = Files.createTempFile("gravaai-poster", ".png");
		} catch (IOException error) {
			return null;
		}
		try {
			var result = ProcessRunner.of(
					AppBinaries.ffmpeg().toString(), "-y", "-ss", "0", "-i", video,
					"-frames:v", "1", "-f", "image2", poster.toString())
					.timeout(EXTRACTION_TIMEOUT)
					.run();
			if (!result.succeeded() || !Files.isRegularFile(poster)) {
				Files.deleteIfExists(poster);
				return null;
			}
			return poster;
		} catch (IOException error) {
			deleteQuietly(poster);
			return null;
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			deleteQuietly(poster);
			return null;
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// The temp file is in the system temp dir; a failed delete is not worth reporting.
		}
	}
}
