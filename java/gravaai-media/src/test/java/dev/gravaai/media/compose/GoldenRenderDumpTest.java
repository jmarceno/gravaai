package dev.gravaai.media.compose;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Diagnostics-only: writes our rendered golden frames next to the diffs for visual comparison. */
class GoldenRenderDumpTest {

	@Test
	@Timeout(600)
	@DisplayName("dumps rendered golden frames for visual inspection")
	void dumpRenderedFrames() throws Exception {
		Assumptions.assumeTrue("1".equals(System.getenv("GRAVAAI_GOLDEN_RENDER_DUMP")),
				"set GRAVAAI_GOLDEN_RENDER_DUMP=1 to dump rendered golden frames");
		List<GoldenFixtures.Case> cases = GoldenFixtures.loadAvailable();
		Assumptions.assumeFalse(cases.isEmpty(), "no golden fixtures published yet");
		Path output = Path.of("target", "golden-diff", "rendered");
		Files.createDirectories(output);
		for (GoldenFixtures.Case goldenCase : cases) {
			for (int index = 0; index < goldenCase.times().length; index++) {
				double timeSeconds = goldenCase.times()[index];
				RgbaFrame rendered = GoldenRenderer.renderFrame(goldenCase, timeSeconds);
				Path file = output.resolve(goldenCase.name() + "@" + timeSeconds + "s.png");
				GoldenImages.writePng(rendered, file);
				System.out.println("GOLDEN-RENDER " + goldenCase.name() + "@" + timeSeconds
						+ "s -> " + file);
			}
		}
	}
}
