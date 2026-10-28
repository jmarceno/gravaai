package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ported from {@code electron/ipc/recording/micNoiseReduction.test.ts} (the app-path test is N/A: Java bundles the model under lib/native). */
class MicNoiseReductionTest {

	@Test
	@DisplayName("keeps known levels and falls back to the default")
	void keepsKnownLevelsAndFallsBack() {
		assertEquals(MicNoiseReduction.OFF, MicNoiseReduction.DEFAULT);
		assertEquals(MicNoiseReduction.OFF, MicNoiseReductionFilters.normalizeMicNoiseReduction("off"));
		assertEquals(MicNoiseReduction.LIGHT, MicNoiseReductionFilters.normalizeMicNoiseReduction("light"));
		assertEquals(
				MicNoiseReduction.BALANCED,
				MicNoiseReductionFilters.normalizeMicNoiseReduction("balanced"));
		assertEquals(MicNoiseReduction.STRONG, MicNoiseReductionFilters.normalizeMicNoiseReduction("strong"));
		assertEquals(MicNoiseReduction.OFF, MicNoiseReductionFilters.normalizeMicNoiseReduction("loud"));
		assertEquals(MicNoiseReduction.OFF, MicNoiseReductionFilters.normalizeMicNoiseReduction(null));
		assertEquals(MicNoiseReduction.OFF, MicNoiseReductionFilters.normalizeMicNoiseReduction(2));
	}

	@Test
	@DisplayName("leaves plain paths alone")
	void leavesPlainPathsAlone() {
		assertEquals(
				"/opt/Gravaai/voice.rnnn",
				MicNoiseReductionFilters.escapeFfmpegFilterValue("/opt/Gravaai/voice.rnnn"));
	}

	@Test
	@DisplayName("escapes for both the option and the filtergraph level")
	void escapesForBothLevels() {
		assertEquals("/a\\\\:b/c.rnnn", MicNoiseReductionFilters.escapeFfmpegFilterValue("/a:b/c.rnnn"));
		assertEquals(
				"/it\\\\\\'s/m.rnnn",
				MicNoiseReductionFilters.escapeFfmpegFilterValue("/it's/m.rnnn"));
		assertEquals("C\\\\:\\\\\\\\m.rnnn", MicNoiseReductionFilters.escapeFfmpegFilterValue("C:\\m.rnnn"));
		assertEquals(
				"/\\[a\\]\\,b\\;c/m.rnnn",
				MicNoiseReductionFilters.escapeFfmpegFilterValue("/[a],b;c/m.rnnn"));
	}

	@Test
	@DisplayName("returns the chain for each level")
	void returnsTheChainForEachLevel() {
		String model = "/models/voice.rnnn";
		assertEquals(List.of(), MicNoiseReductionFilters.buildMicNoiseFilters(MicNoiseReduction.OFF, model));
		assertEquals(
				List.of("highpass=f=80", "afftdn=nr=10:nf=-50:tn=1"),
				MicNoiseReductionFilters.buildMicNoiseFilters(MicNoiseReduction.LIGHT, model));
		assertEquals(
				List.of("highpass=f=80", "arnndn=m=/models/voice.rnnn:mix=0.85"),
				MicNoiseReductionFilters.buildMicNoiseFilters(MicNoiseReduction.BALANCED, model));
		assertEquals(
				List.of("highpass=f=80", "arnndn=m=/models/voice.rnnn:mix=1", "afftdn=nr=6:nf=-50"),
				MicNoiseReductionFilters.buildMicNoiseFilters(MicNoiseReduction.STRONG, model));
	}

	@Test
	@DisplayName("falls back to the light chain without the model")
	void fallsBackToTheLightChainWithoutTheModel() {
		List<String> light = MicNoiseReductionFilters.buildMicNoiseFilters(MicNoiseReduction.LIGHT, null);
		assertEquals(light, MicNoiseReductionFilters.buildMicNoiseFilters(MicNoiseReduction.BALANCED, null));
		assertEquals(light, MicNoiseReductionFilters.buildMicNoiseFilters(MicNoiseReduction.STRONG, null));
	}

	@Test
	@DisplayName("escapes the model path")
	void escapesTheModelPath() {
		assertEquals(
				"arnndn=m=/My Files\\\\:x/v.rnnn:mix=0.85",
				MicNoiseReductionFilters.buildMicNoiseFilters(
						MicNoiseReduction.BALANCED, "/My Files:x/v.rnnn").get(1));
	}
}
