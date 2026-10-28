package dev.gravaai.services.projects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Port of {@code electron/ipc/project/firstFrameThumbnail.ts}, exercised through a fake ffmpeg. */
@Timeout(60)
class FirstFrameThumbnailTest {

	@TempDir
	Path tempDir;

	private Path script(String body) throws Exception {
		Path script = tempDir.resolve("fake-ffmpeg.sh");
		Files.writeString(script, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
		assertTrue(script.toFile().setExecutable(true), "could not make the fake ffmpeg executable");
		return script;
	}

	@Test
	@DisplayName("returns the PNG frame the tool writes to stdout")
	void returnsPngFrameFromStdout() throws Exception {
		byte[] png = ProjectTestFiles.pngHeader(640, 480);
		Path frame = tempDir.resolve("frame.png");
		Files.write(frame, png);
		Path ffmpeg = script("cat '" + frame + "'");

		byte[] thumbnail = new FirstFrameThumbnail(ffmpeg).createProjectFirstFrameThumbnail(Path.of("video.mp4"));

		assertArrayEquals(png, thumbnail);
	}

	@Test
	@DisplayName("reports a source without a preview frame")
	void reportsMissingPreviewFrame() throws Exception {
		Path ffmpeg = script("exit 0");

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> new FirstFrameThumbnail(ffmpeg).createProjectFirstFrameThumbnail(Path.of("video.mp4")));

		assertEquals("Project source has no preview frame", error.getMessage());
	}

	@Test
	@DisplayName("reports a failing ffmpeg")
	void reportsFailingFfmpeg() throws Exception {
		Path ffmpeg = script("exit 1");

		assertThrows(IllegalStateException.class,
				() -> new FirstFrameThumbnail(ffmpeg).createProjectFirstFrameThumbnail(Path.of("video.mp4")));
	}
}
