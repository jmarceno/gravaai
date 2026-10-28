package dev.gravaai.media.compose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The compositor golden-frame suite (plan §6.2). Cases come from
 * {@code java/parity/golden/manifest.json} (task-13, 10 cases / 36 references).
 *
 * <p>The enabled test proves the whole pipeline against every committed fixture project:
 * project model load, wallpaper asset, layout mapping, decode at a project time, composite and
 * read back at the reference size. The pixel test is a regression gate on
 * per-case baselines (baseline.json), not the plan thresholds: the committed references are
 * lossless preview-screenshot captures (portrait caption
 * scaling, preview-scaled shadow spread, SF Pro vs bundled DM Sans), so exporter frames cannot
 * match them without emulating preview CSS; see {@code java/parity/golden/parity-deviation.md}
 * and the generated {@code parity-deviation-measured.md}. Baselines must not be loosened
 * silently.</p>
 */
class GoldenFrameSuiteTest {

	@Test
	@Timeout(180)
	@DisplayName("renders the committed golden fixtures at the reference size")
	void rendersFixturesAtReferenceSize() throws Exception {
		List<GoldenFixtures.Case> cases = GoldenFixtures.loadAvailable();
		Assumptions.assumeFalse(cases.isEmpty(), "no golden fixtures published yet");
		for (GoldenFixtures.Case goldenCase : cases) {
			RgbaFrame reference = GoldenImages.read(goldenCase.references().get(0));
			assertEquals(goldenCase.width(), reference.width(),
					"metadata width mismatch for " + goldenCase.name());
			assertEquals(goldenCase.height(), reference.height(),
					"metadata height mismatch for " + goldenCase.name());

			RgbaFrame rendered = GoldenRenderer.renderFrame(goldenCase, goldenCase.times()[0]);
			assertEquals(goldenCase.width(), rendered.width(),
					"rendered width for " + goldenCase.name());
			assertEquals(goldenCase.height(), rendered.height(),
					"rendered height for " + goldenCase.name());
			assertFalse(GoldenRenderer.isUniform(rendered),
					"rendered frame is blank for " + goldenCase.name());
		}
	}

	/**
	 * Regression gate against the lossless preview references: every frame must stay within its
	 * recorded floor in {@code baseline.json} (never looser than the plan thresholds allow for
	 * cases already inside them). The plan thresholds themselves are not met against preview
	 * captures; see the class comment and parity-deviation.md.
	 */
	@Test
	@Timeout(600)
	@DisplayName("golden frames stay within their recorded parity baseline")
	void goldenFramesMatchReferences() throws Exception {
		List<GoldenFixtures.Case> cases = GoldenFixtures.loadAvailable();
		Assumptions.assumeFalse(cases.isEmpty(), "no golden fixtures published yet");
		var baseline = dev.gravaai.core.json.Json.MAPPER.readTree(
				ProjectFixture.repoRoot().resolve("java/parity/golden/baseline.json").toFile())
				.get("cases");
		for (GoldenFixtures.Case goldenCase : cases) {
			for (int index = 0; index < goldenCase.times().length; index++) {
				RgbaFrame expected = GoldenImages.read(goldenCase.references().get(index));
				RgbaFrame actual = GoldenRenderer.renderFrame(goldenCase,
						goldenCase.times()[index]);
				String name = goldenCase.name() + "@" + goldenCase.times()[index] + "s";
				var limits = baseline.get(goldenCase.name() + "@"
						+ Math.round(goldenCase.times()[index] * 1000));
				assertTrue(limits != null, "no baseline for " + name);
				GoldenComparison.Thresholds thresholds = new GoldenComparison.Thresholds(
						limits.get("meanAbsDiff").asDouble(),
						limits.get("changedPixelRatio").asDouble(),
						GoldenComparison.PLAN_THRESHOLDS.channelTolerance());
				GoldenComparison.Result result = GoldenComparison.compare(expected, actual,
						Path.of("target", "golden-diff", name + ".png"), thresholds);
				assertTrue(result.passed(), name + ": " + result.summary());
			}
		}
	}
}
