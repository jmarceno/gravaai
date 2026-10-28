package dev.gravaai.services.projects;

import java.nio.file.Path;

/**
 * Produces the 640x480 first-frame PNG of a project source (port of
 * {@code createProjectFirstFrameThumbnail} in {@code electron/ipc/project/firstFrameThumbnail.ts}).
 *
 * <p>A functional interface so the manager can be tested without spawning ffmpeg; the real
 * implementation is {@link FirstFrameThumbnail}.
 */
@FunctionalInterface
public interface ProjectThumbnailGenerator {

	/**
	 * @param sourcePath the project's source video
	 * @return PNG bytes of the first frame, cropped to 640x480
	 * @throws Exception when the source has no decodable frame or the tool fails
	 */
	byte[] createProjectFirstFrameThumbnail(Path sourcePath) throws Exception;
}
