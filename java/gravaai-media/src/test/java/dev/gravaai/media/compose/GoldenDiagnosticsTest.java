package dev.gravaai.media.compose;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Diagnostics-only golden comparison for all fixtures 01-10 (stage 4+ work driver).
 *
 * <p>Not part of the gate: it runs only when {@code GRAVAAI_GOLDEN_DIAGNOSTICS=1} is set, writes
 * the diff PNGs to {@code target/golden-diff} and prints the measured mean/changed-pixel numbers
 * for every reference time. The acceptance comparison stays the disabled full suite test; this
 * class exists so stage work can be driven by concrete numbers.</p>
 */
class GoldenDiagnosticsTest {

	@Test
	@Timeout(600)
	@DisplayName("prints golden diff diagnostics for the base fixtures")
	void diffDiagnostics() throws Exception {
		Assumptions.assumeTrue("1".equals(System.getenv("GRAVAAI_GOLDEN_DIAGNOSTICS")),
				"set GRAVAAI_GOLDEN_DIAGNOSTICS=1 to run the golden diagnostics");
		List<GoldenFixtures.Case> cases = GoldenFixtures.loadAvailable();
		Assumptions.assumeFalse(cases.isEmpty(), "no golden fixtures published yet");
		Path output = Path.of("target", "golden-diff");
		Files.createDirectories(output);
		for (GoldenFixtures.Case goldenCase : cases) {
			for (int index = 0; index < goldenCase.times().length; index++) {
				double timeSeconds = goldenCase.times()[index];
				RgbaFrame expected = GoldenImages.read(goldenCase.references().get(index));
				RgbaFrame actual = GoldenRenderer.renderFrame(goldenCase, timeSeconds);
				String label = goldenCase.name() + "@" + timeSeconds + "s";
				GoldenComparison.Result result = GoldenComparison.compare(expected, actual,
						output.resolve(label + ".png"), GoldenComparison.PLAN_THRESHOLDS);
				System.out.println("GOLDEN-DIAG " + label + " passed=" + result.passed()
						+ " meanAbsDiff=" + String.format(java.util.Locale.ROOT, "%.3f",
								result.meanAbsDiff())
						+ " changedPixels=" + String.format(java.util.Locale.ROOT, "%.3f%%",
								result.changedPixelRatio() * 100)
						+ " maxChannelDiff=" + result.maxChannelDiff()
						+ " diffPng=" + result.diffPng());
			}
		}
	}
}
