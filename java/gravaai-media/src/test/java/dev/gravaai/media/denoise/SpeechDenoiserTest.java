package dev.gravaai.media.denoise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link SpeechDenoiser} against the real DeepFilterNet3 model. Skipped when the native payload
 * has not been staged (the model is committed under {@code java/native/models/denoise}).
 */
class SpeechDenoiserTest {

	private static Path modelDir() {
		Path staged = DenoisedAudio.modelDir();
		if (SpeechDenoiser.isInstalled(staged)) {
			return staged;
		}
		return Path.of("..", "native", "models", "denoise").toAbsolutePath().normalize();
	}

	private static float[] denoise(SpeechDenoiser denoiser, float[] input) {
		SpeechDenoiser.Stream stream = denoiser.newStream();
		float[] output = new float[input.length];
		int produced = 0;
		// Odd-sized writes: the stream must not care how the caller slices the audio.
		for (int at = 0; at < input.length; at += 1777) {
			stream.write(input, at, Math.min(1777, input.length - at));
			produced += stream.read(output, produced, output.length - produced);
		}
		stream.finish();
		produced += stream.read(output, produced, output.length - produced);
		assertEquals(input.length, produced, "every sample written comes back out");
		assertEquals(0, stream.available(), "and nothing more");
		return output;
	}

	private static double rmsDb(float[] samples, int from, int to) {
		double sum = 0;
		for (int i = from; i < to; i++) {
			sum += samples[i] * samples[i];
		}
		return 10 * Math.log10(sum / (to - from) + 1e-20);
	}

	@Test
	@Timeout(120)
	@DisplayName("steady noise is removed and the output keeps the input's length")
	void removesSteadyNoise() {
		Path model = modelDir();
		Assumptions.assumeTrue(SpeechDenoiser.isInstalled(model), "denoiser model not staged");
		int seconds = 4;
		float[] noise = new float[seconds * DenoiseDsp.SAMPLE_RATE + 123];
		java.util.Random random = new java.util.Random(3);
		for (int i = 0; i < noise.length; i++) {
			noise[i] = (float) (0.05 * (random.nextDouble() * 2 - 1));
		}
		try (SpeechDenoiser denoiser = SpeechDenoiser.open(model)) {
			float[] cleaned = denoise(denoiser, noise);
			double before = rmsDb(noise, DenoiseDsp.SAMPLE_RATE, noise.length);
			double after = rmsDb(cleaned, DenoiseDsp.SAMPLE_RATE, cleaned.length);
			assertTrue(after < before - 25, "white noise must drop by well over 25 dB once the"
					+ " network has settled: " + before + " -> " + after + " dBFS");
		}
	}

	@Test
	@Timeout(120)
	@DisplayName("a voice-like harmonic tone passes sample-aligned while noise around it goes")
	void keepsAHarmonicSignalAligned() {
		Path model = modelDir();
		Assumptions.assumeTrue(SpeechDenoiser.isInstalled(model), "denoiser model not staged");
		int length = 3 * DenoiseDsp.SAMPLE_RATE;
		float[] voice = new float[length];
		float[] noisy = new float[length];
		java.util.Random random = new java.util.Random(5);
		for (int i = 0; i < length; i++) {
			double t = i / (double) DenoiseDsp.SAMPLE_RATE;
			// A 150 Hz glottal-like tone with harmonics and a slow syllable envelope.
			double envelope = 0.5 + 0.5 * Math.sin(2 * Math.PI * 3 * t);
			double sample = 0;
			for (int harmonic = 1; harmonic <= 12; harmonic++) {
				sample += Math.sin(2 * Math.PI * 150 * harmonic * t) / harmonic;
			}
			voice[i] = (float) (0.15 * envelope * sample);
			noisy[i] = voice[i] + (float) (0.01 * (random.nextDouble() * 2 - 1));
		}
		try (SpeechDenoiser denoiser = SpeechDenoiser.open(model)) {
			float[] cleaned = denoise(denoiser, noisy);
			// Best alignment between the output and the clean tone must be at lag zero.
			int from = DenoiseDsp.SAMPLE_RATE;
			int span = DenoiseDsp.SAMPLE_RATE;
			double best = Double.NEGATIVE_INFINITY;
			int bestLag = Integer.MIN_VALUE;
			for (int lag = -120; lag <= 600; lag += 4) {
				double correlation = 0;
				for (int i = from; i < from + span; i++) {
					correlation += voice[i] * cleaned[i + lag];
				}
				if (correlation > best) {
					best = correlation;
					bestLag = lag;
				}
			}
			assertEquals(0, bestLag, "the output must be sample-aligned with the input");
			assertTrue(rmsDb(cleaned, from, from + span) > rmsDb(voice, from, from + span) - 6,
					"the harmonic signal must survive");
		}
	}
}
