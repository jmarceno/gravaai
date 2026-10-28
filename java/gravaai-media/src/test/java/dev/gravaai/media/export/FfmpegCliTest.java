package dev.gravaai.media.export;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bundled software ffmpeg CLI used by the export encoders (plan §5.10). */
class FfmpegCliTest {

	@Test
	@DisplayName("finds the bundled software ffmpeg binary")
	void findsBinary() {
		assertTrue(FfmpegCli.version().startsWith("ffmpeg version"), FfmpegCli.version());
	}
}
