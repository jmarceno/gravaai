package dev.gravaai.ui.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Port of the level maths in src/hooks/useAudioLevelMeter.ts (plan §5.6.9): fftSize 256,
 * smoothing 0.8, minDecibels -100, maxDecibels -30, RMS of the byte bins scaled x2 and clamped.
 */
class AudioLevelAnalyserTest {

	@DisplayName("analyser constants match useAudioLevelMeter.ts")
	@Test
	void constants() {
		assertEquals(256, AudioLevelAnalyser.FFT_SIZE);
		assertEquals(0.8, AudioLevelAnalyser.SMOOTHING);
		assertEquals(-100, AudioLevelAnalyser.MIN_DECIBELS);
		assertEquals(-30, AudioLevelAnalyser.MAX_DECIBELS);
	}

	@DisplayName("FFT of a constant signal puts all energy in the DC bin")
	@Test
	void fftDc() {
		double[] real = new double[256];
		double[] imaginary = new double[256];
		java.util.Arrays.fill(real, 1);
		AudioLevelAnalyser.fft(real, imaginary);
		assertEquals(256, real[0], 1e-9);
		for (int bin = 1; bin < 256; bin++) {
			assertEquals(0, Math.hypot(real[bin], imaginary[bin]), 1e-9);
		}
	}

	@DisplayName("FFT of a sine wave peaks at its bin")
	@Test
	void fftSinePeak() {
		int size = 256;
		int peakBin = 8;
		double[] real = new double[size];
		double[] imaginary = new double[size];
		for (int i = 0; i < size; i++) {
			real[i] = Math.sin(2 * Math.PI * peakBin * i / size);
		}
		AudioLevelAnalyser.fft(real, imaginary);
		double peak = Math.hypot(real[peakBin], imaginary[peakBin]);
		for (int bin = 1; bin < size / 2; bin++) {
			if (bin != peakBin) {
				assertTrue(peak > Math.hypot(real[bin], imaginary[bin]), "bin " + bin);
			}
		}
	}

	@DisplayName("byte bins: silence is 0, full scale clamps to 100")
	@Test
	void levels() {
		assertEquals(0, AudioLevelAnalyser.levelFromMagnitudes(new double[128]), 1e-9);
		double[] fullScale = new double[128];
		java.util.Arrays.fill(fullScale, 1.0);
		assertEquals(100, AudioLevelAnalyser.levelFromMagnitudes(fullScale), 1e-9);
		double[] inaudible = new double[128];
		java.util.Arrays.fill(inaudible, 1e-9);
		assertEquals(0, AudioLevelAnalyser.levelFromMagnitudes(inaudible), 1e-9);
	}

	@DisplayName("analyse returns 0 for silence, 0..100 for a loud sine, and smooths over frames")
	@Test
	void analyses() {
		AudioLevelAnalyser analyser = new AudioLevelAnalyser();
		float[] silence = new float[256];
		assertEquals(0, analyser.analyse(silence), 1e-9);
		float[] sine = new float[256];
		for (int i = 0; i < sine.length; i++) {
			sine[i] = (float) (0.8 * Math.sin(2 * Math.PI * 4 * i / sine.length));
		}
		double first = analyser.analyse(sine);
		double second = analyser.analyse(sine);
		assertTrue(first > 0 && first <= 100, "level " + first);
		assertTrue(second >= first, "smoothing should approach the steady level");
	}
}
