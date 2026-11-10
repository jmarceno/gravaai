package dev.gravaai.media.denoise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link DenoiseDsp}: the framing, the band layout and the features the network was trained on. */
class DenoiseDspTest {

	@Test
	@DisplayName("the window is power complementary: analysis then synthesis returns the input")
	void analysisThenSynthesisReconstructs() {
		DenoiseDsp.Analyzer analyzer = new DenoiseDsp.Analyzer();
		DenoiseDsp.Synthesizer synthesizer = new DenoiseDsp.Synthesizer();
		int hops = 12;
		float[] input = new float[hops * DenoiseDsp.HOP_SIZE];
		java.util.Random random = new java.util.Random(7);
		for (int i = 0; i < input.length; i++) {
			input[i] = (float) (0.4 * Math.sin(2 * Math.PI * 440 * i / DenoiseDsp.SAMPLE_RATE)
					+ 0.1 * (random.nextDouble() - 0.5));
		}
		float[] output = new float[input.length];
		float[] spectrum = new float[DenoiseDsp.BINS * 2];
		float[] erb = new float[DenoiseDsp.ERB_BANDS];
		float[] spec = new float[DenoiseDsp.DF_BINS * 2];
		for (int hop = 0; hop < hops; hop++) {
			analyzer.analyze(input, hop * DenoiseDsp.HOP_SIZE, spectrum, erb, spec);
			synthesizer.synthesize(spectrum, output, hop * DenoiseDsp.HOP_SIZE);
		}
		// The overlap-add hands every sample back exactly one hop late.
		for (int i = 0; i < input.length - DenoiseDsp.HOP_SIZE; i++) {
			assertEquals(input[i], output[i + DenoiseDsp.HOP_SIZE], 1e-4, "sample " + i);
		}
	}

	@Test
	@DisplayName("the 32 ERB bands cover every bin once, none narrower than two bins")
	void erbBandsCoverTheSpectrum() {
		int[] widths = DenoiseDsp.erbWidths();
		assertEquals(DenoiseDsp.ERB_BANDS, widths.length);
		int total = 0;
		for (int width : widths) {
			assertTrue(width >= 2, "band narrower than the model's minimum: " + width);
			total += width;
		}
		assertEquals(DenoiseDsp.BINS, total);
		// The reference layout for 48 kHz / 960: narrow bands at the bottom, wide at the top.
		assertEquals(2, widths[0]);
		assertTrue(widths[DenoiseDsp.ERB_BANDS - 1] > 40, "top band: "
				+ widths[DenoiseDsp.ERB_BANDS - 1]);
		assertEquals(0, DenoiseDsp.bandOf(0));
		assertEquals(DenoiseDsp.ERB_BANDS - 1, DenoiseDsp.bandOf(DenoiseDsp.BINS - 1));
	}

	@Test
	@DisplayName("the mixed-radix FFT matches the transform's definition and inverts itself")
	void fftMatchesTheDefinition() {
		int size = 60;
		DenoiseDsp.Fft fft = new DenoiseDsp.Fft(size);
		double[] real = new double[size];
		double[] imaginary = new double[size];
		// One cosine at bin 7: the spectrum is two spikes of size / 2.
		for (int i = 0; i < size; i++) {
			real[i] = Math.cos(2 * Math.PI * 7 * i / size);
		}
		double[] original = real.clone();
		fft.transform(real, imaginary, false);
		for (int bin = 0; bin < size; bin++) {
			double expected = bin == 7 || bin == size - 7 ? size / 2.0 : 0;
			assertEquals(expected, real[bin], 1e-9, "re " + bin);
			assertEquals(0, imaginary[bin], 1e-9, "im " + bin);
		}
		fft.transform(real, imaginary, true);
		for (int i = 0; i < size; i++) {
			assertEquals(original[i], real[i] / size, 1e-9, "inverse " + i);
		}
	}

	@Test
	@DisplayName("a steady signal's ERB features settle towards zero: they are mean-normalised")
	void erbFeaturesAreMeanNormalised() {
		DenoiseDsp.Analyzer analyzer = new DenoiseDsp.Analyzer();
		float[] hop = new float[DenoiseDsp.HOP_SIZE];
		float[] spectrum = new float[DenoiseDsp.BINS * 2];
		float[] erb = new float[DenoiseDsp.ERB_BANDS];
		float[] spec = new float[DenoiseDsp.DF_BINS * 2];
		java.util.Random random = new java.util.Random(11);
		float first = 0;
		for (int frame = 0; frame < 1500; frame++) {
			for (int i = 0; i < hop.length; i++) {
				hop[i] = (float) (0.2 * (random.nextDouble() - 0.5));
			}
			analyzer.analyze(hop, 0, spectrum, erb, spec);
			if (frame == 1) {
				first = Math.abs(erb[10]);
			}
		}
		assertTrue(Math.abs(erb[10]) < first / 4, "the running mean must absorb a steady level: "
				+ first + " -> " + erb[10]);
		// The complex features are divided by the square root of the bin's running magnitude
		// (the reference's "unit norm"), so a steady bin lands near the square root of its level.
		double raw = Math.hypot(spectrum[40], spectrum[41]);
		double feature = Math.hypot(spec[40], spec[41]);
		double ratio = feature / Math.sqrt(raw);
		assertTrue(ratio > 0.1 && ratio < 10, "unit-normalised bin: " + feature + " for " + raw);
	}
}
