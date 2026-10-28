package dev.gravaai.services.projects;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import dev.gravaai.platform.process.ProcessResult;
import dev.gravaai.platform.process.ProcessRunner;
import dev.gravaai.platform.process.RunningProcess;

/**
 * First-frame project preview through the bundled ffmpeg CLI (port of
 * {@code electron/ipc/project/firstFrameThumbnail.ts}).
 *
 * <p>The TS code pipes one PNG frame to stdout; this port does the same with the raw stdout pipe
 * of {@link ProcessRunner} (the default text collector would corrupt binary data). Calls are
 * serialised like the module-level promise queue in TS so a large library cannot spawn a decode per
 * project at once.
 */
public final class FirstFrameThumbnail implements ProjectThumbnailGenerator {

	private static final System.Logger LOG = System.getLogger("gravaai.projects");

	/** Port of {@code maxBuffer: 4 * 1024 * 1024}. */
	private static final int MAXIMUM_OUTPUT_BYTES = 4 * 1024 * 1024;

	/** Port of {@code timeout: 15000}. */
	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	/** Module-level queue in the TS source: one ffmpeg preview at a time. */
	private static final Object QUEUE_LOCK = new Object();

	private final Path ffmpegBinary;

	public FirstFrameThumbnail(Path ffmpegBinary) {
		this.ffmpegBinary = ffmpegBinary;
	}

	@Override
	public byte[] createProjectFirstFrameThumbnail(Path sourcePath) throws Exception {
		synchronized (QUEUE_LOCK) {
			return generate(sourcePath);
		}
	}

	private byte[] generate(Path sourcePath) throws IOException, InterruptedException {
		List<String> command = List.of(
				ffmpegBinary.toString(),
				"-hide_banner",
				"-loglevel",
				"error",
				"-nostdin",
				"-threads",
				"1",
				"-i",
				sourcePath.toString(),
				"-frames:v",
				"1",
				"-an",
				"-vf",
				"scale=" + ThumbnailFreshness.PROJECT_THUMBNAIL_WIDTH + ":"
						+ ThumbnailFreshness.PROJECT_THUMBNAIL_HEIGHT
						+ ":force_original_aspect_ratio=increase,crop="
						+ ThumbnailFreshness.PROJECT_THUMBNAIL_WIDTH + ":"
						+ ThumbnailFreshness.PROJECT_THUMBNAIL_HEIGHT,
				"-f",
				"image2pipe",
				"-c:v",
				"png",
				"pipe:1");
		try (RunningProcess process = ProcessRunner.of(command).rawStdout().timeout(TIMEOUT).start()) {
			byte[] thumbnail;
			try (InputStream stdout = process.stdout()) {
				thumbnail = stdout.readNBytes(MAXIMUM_OUTPUT_BYTES + 1);
			}
			ProcessResult result = process.waitForExit();
			if (thumbnail.length > MAXIMUM_OUTPUT_BYTES) {
				throw new IllegalStateException("Project preview frame exceeds the 4 MB limit");
			}
			if (!result.succeeded()) {
				LOG.log(System.Logger.Level.DEBUG, "ffmpeg thumbnail failed: " + result.stderr());
				throw new IllegalStateException("Project source has no preview frame");
			}
			if (thumbnail.length == 0) {
				throw new IllegalStateException("Project source has no preview frame");
			}
			return thumbnail;
		}
	}
}
