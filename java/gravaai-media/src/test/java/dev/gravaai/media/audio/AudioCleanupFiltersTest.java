package dev.gravaai.media.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.project.AudioCleanup;

/** Edit-time cleanup chains: stage order, RNNoise/FFT fallback and the loudness filter. */
class AudioCleanupFiltersTest {

	private static final String MODEL = "/opt/gravaai/models/rnnoise/voice.rnnn";

	@Test
	@DisplayName("no stage on means no filter at all")
	void noneMeansNoFilters() {
		assertEquals(List.of(), AudioCleanupFilters.trackFilters(AudioCleanup.NONE, MODEL));
		assertEquals(List.of(), AudioCleanupFilters.trackFilters(null, null));
	}

	@Test
	@DisplayName("the high-pass comes first and is user-controlled at edit time")
	void highPassOnly() {
		AudioCleanup cleanup = new AudioCleanup(AudioCleanup.NoiseReduction.OFF, 120, 0);
		assertEquals(List.of("highpass=f=120"), AudioCleanupFilters.trackFilters(cleanup, MODEL));
	}

	@Test
	@DisplayName("light noise reduction is the FFT denoiser, with or without the model")
	void lightNoiseReduction() {
		AudioCleanup cleanup = new AudioCleanup(AudioCleanup.NoiseReduction.LIGHT, 0, 0);
		List<String> expected = List.of("afftdn=nr=10:nf=-50:tn=1");
		assertEquals(expected, AudioCleanupFilters.trackFilters(cleanup, MODEL));
		assertEquals(expected, AudioCleanupFilters.trackFilters(cleanup, null));
	}

	@Test
	@DisplayName("balanced and strong use RNNoise when the model is there")
	void rnnoiseLevels() {
		AudioCleanup balanced = new AudioCleanup(AudioCleanup.NoiseReduction.BALANCED, 0, 0);
		assertEquals(List.of("arnndn=m=" + MODEL + ":mix=0.85"),
				AudioCleanupFilters.trackFilters(balanced, MODEL));

		AudioCleanup strong = new AudioCleanup(AudioCleanup.NoiseReduction.STRONG, 80, 0);
		assertEquals(List.of("highpass=f=80", "arnndn=m=" + MODEL + ":mix=1",
				"afftdn=nr=6:nf=-50"), AudioCleanupFilters.trackFilters(strong, MODEL));
	}

	@Test
	@DisplayName("without the model the heavier levels fall back to a static-profile FFT denoise")
	void fftFallback() {
		AudioCleanup balanced = new AudioCleanup(AudioCleanup.NoiseReduction.BALANCED, 0, 0);
		assertEquals(List.of("afftdn=nr=12:nf=-30"),
				AudioCleanupFilters.trackFilters(balanced, null));

		AudioCleanup strong = new AudioCleanup(AudioCleanup.NoiseReduction.STRONG, 0, 0);
		assertEquals(List.of("afftdn=nr=22:nf=-30"),
				AudioCleanupFilters.trackFilters(strong, null));
	}

	@Test
	@DisplayName("the model path is escaped for both filtergraph levels")
	void escapesModelPath() {
		List<String> filters = AudioCleanupFilters.trackFilters(
				new AudioCleanup(AudioCleanup.NoiseReduction.BALANCED, 0, 0), "/it's/a:b.rnnn");
		assertEquals(List.of("arnndn=m=/it\\\\\\'s/a\\\\:b.rnnn:mix=0.85"), filters);
	}

	@Test
	@DisplayName("the loudness stage is the EBU R128 single pass, off at 0")
	void loudnorm() {
		assertNull(AudioCleanupFilters.loudnormFilter(0));
		assertNull(AudioCleanupFilters.loudnormFilter(-0.0));
		assertNull(AudioCleanupFilters.loudnormFilter(Double.NaN));
		assertEquals("loudnorm=I=-16:TP=-1.5:LRA=11", AudioCleanupFilters.loudnormFilter(-16));
		assertEquals("loudnorm=I=-14:TP=-1.5:LRA=11", AudioCleanupFilters.loudnormFilter(-14));
		assertEquals("loudnorm=I=-23:TP=-1.5:LRA=11", AudioCleanupFilters.loudnormFilter(-23));
	}

	@Test
	@DisplayName("a measurement turns the loudness stage into the linear two-pass form")
	void loudnormLinear() {
		var measured = new AudioCleanupFilters.MeasuredLoudness(-14.23, -8.51, 3.00, -24.77, 0.04);
		assertEquals("loudnorm=I=-16:TP=-1.5:LRA=11:linear=true"
						+ ":measured_I=-14.23:measured_TP=-8.51:measured_LRA=3.00"
						+ ":measured_thresh=-24.77:offset=0.04",
				AudioCleanupFilters.loudnormFilter(-16, measured));
		// No target or no measurement: the dynamic single pass (or nothing).
		assertEquals("loudnorm=I=-16:TP=-1.5:LRA=11", AudioCleanupFilters.loudnormFilter(-16, null));
		assertNull(AudioCleanupFilters.loudnormFilter(0, measured));
	}

	@Test
	@DisplayName("the bundled RNNoise model resolves to an existing file when present")
	void modelLocator() {
		java.nio.file.Path model = AudioCleanupFilters.rnnoiseModel();
		if (model != null) {
			assertTrue(java.nio.file.Files.isRegularFile(model));
			assertTrue(model.toString().endsWith(AudioCleanupFilters.RNNOISE_MODEL_PATH));
		}
	}
}
