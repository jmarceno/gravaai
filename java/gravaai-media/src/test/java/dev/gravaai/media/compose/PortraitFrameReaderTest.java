package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression: converting 1080-wide portrait frames used to let swscale write past the RGBA
 * buffer, corrupting the native heap; the JVM then crashed when the converter was freed (found
 * with a real 1080x1920 phone-style recording).
 */
class PortraitFrameReaderTest {

	@Test
	@Timeout(60)
	@DisplayName("decodes and closes 1080x1920 frames without corrupting the native heap")
	void portraitDecodeAndClose() throws Exception {
		Path clip = ProjectFixture.repoRoot()
				.resolve("java/parity/fixtures/media/portrait-1080x1920.mp4");
		assertTrue(Files.isReadable(clip), "fixture missing: " + clip);
		for (int round = 0; round < 3; round++) {
			try (MediaFrameReader reader = new MediaFrameReader(clip, 0, true)) {
				RgbaFrame frame = reader.decode(0.5);
				assertNotNull(frame);
				assertEquals(1080, frame.width());
				assertEquals(1920, frame.height());
				assertNotNull(reader.decodeForward(0.6));
			}
		}
	}
}
