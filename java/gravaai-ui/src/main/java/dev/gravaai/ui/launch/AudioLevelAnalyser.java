package dev.gravaai.ui.launch;

/**
 * Port of the level maths in {@code src/hooks/useAudioLevelMeter.ts} (plan §5.6.9) on top of the
 * PulseAudio PCM stream.
 *
 * <p>The Web Audio analyser uses {@code fftSize = 256}, a Blackman window,
 * {@code smoothingTimeConstant = 0.8} and the default {@code minDecibels = -100},
 * {@code maxDecibels = -30}; {@code getByteFrequencyData} maps each bin to 0..255. The hook then
 * takes the RMS of those 128 bytes and scales it {@code rms / 255 * 100 * 2}, clamped to 100.
 * This class reproduces exactly that chain, so the HUD meter matches the React one.
 */
public final class AudioLevelAnalyser {

	/** {@code analyser.fftSize} in useAudioLevelMeter.ts. */
	public static final int FFT_SIZE = 256;
	/** {@code analyser.smoothingTimeConstant}. */
	public static final double SMOOTHING = 0.8;
	/** {@code analyser.minDecibels} (default). */
	public static final double MIN_DECIBELS = -100;
	/** {@code analyser.maxDecibels} (default). */
	public static final double MAX_DECIBELS = -30;

	private final double[] window = new double[FFT_SIZE];
	private final double[] previousMagnitudes = new double[FFT_SIZE / 2];

	public AudioLevelAnalyser() {
		// Blackman window, the window function WebAudio's AnalyserNode uses.
		for (int i = 0; i < FFT_SIZE; i++) {
			double phase = (2 * Math.PI * i) / FFT_SIZE;
			window[i] = 0.42 - 0.5 * Math.cos(phase) + 0.08 * Math.cos(2 * phase);
		}
	}

	/** One analysis frame: level 0..100 from exactly {@link #FFT_SIZE} mono float samples. */
	public double analyse(float[] samples) {
		if (samples.length < FFT_SIZE) {
			return 0;
		}
		double[] real = new double[FFT_SIZE];
		double[] imaginary = new double[FFT_SIZE];
		double windowSum = 0;
		for (int i = 0; i < FFT_SIZE; i++) {
			real[i] = samples[i] * window[i];
			windowSum += window[i];
		}
		fft(real, imaginary);
		double[] magnitudes = new double[FFT_SIZE / 2];
		for (int bin = 0; bin < magnitudes.length; bin++) {
			double magnitude = 2 * Math.hypot(real[bin], imaginary[bin]) / windowSum;
			// smoothingTimeConstant: average with the previous frame in the magnitude domain.
			magnitudes[bin] = SMOOTHING * previousMagnitudes[bin] + (1 - SMOOTHING) * magnitude;
			previousMagnitudes[bin] = magnitudes[bin];
		}
		return levelFromMagnitudes(magnitudes);
	}

	/** {@code getByteFrequencyData} + the hook's RMS/scale, exposed for tests. */
	public static double levelFromMagnitudes(double[] magnitudes) {
		double sum = 0;
		for (double magnitude : magnitudes) {
			double decibels = 20 * Math.log10(Math.max(magnitude, 1e-12));
			double normalized = (decibels - MIN_DECIBELS) / (MAX_DECIBELS - MIN_DECIBELS);
			double byteValue = Math.max(0, Math.min(255, Math.round(normalized * 255)));
			sum += byteValue * byteValue;
		}
		double rms = Math.sqrt(sum / magnitudes.length);
		return Math.min(100, (rms / 255) * 100 * 2);
	}

	/** In-place iterative radix-2 FFT (forward, no scaling), exposed for tests. */
	public static void fft(double[] real, double[] imaginary) {
		int n = real.length;
		if (Integer.bitCount(n) != 1 || imaginary.length != n) {
			throw new IllegalArgumentException("FFT size must be a power of two and match the real array");
		}
		for (int i = 1, j = 0; i < n; i++) {
			int bit = n >> 1;
			for (; (j & bit) != 0; bit >>= 1) {
				j ^= bit;
			}
			j ^= bit;
			if (i < j) {
				double tempReal = real[i];
				real[i] = real[j];
				real[j] = tempReal;
				double tempImaginary = imaginary[i];
				imaginary[i] = imaginary[j];
				imaginary[j] = tempImaginary;
			}
		}
		for (int length = 2; length <= n; length <<= 1) {
			double angle = -2 * Math.PI / length;
			double wReal = Math.cos(angle);
			double wImaginary = Math.sin(angle);
			for (int i = 0; i < n; i += length) {
				double currentReal = 1;
				double currentImaginary = 0;
				for (int j = 0; j < length / 2; j++) {
					int even = i + j;
					int odd = i + j + length / 2;
					double oddReal = real[odd] * currentReal - imaginary[odd] * currentImaginary;
					double oddImaginary = real[odd] * currentImaginary + imaginary[odd] * currentReal;
					real[odd] = real[even] - oddReal;
					imaginary[odd] = imaginary[even] - oddImaginary;
					real[even] += oddReal;
					imaginary[even] += oddImaginary;
					double nextReal = currentReal * wReal - currentImaginary * wImaginary;
					currentImaginary = currentReal * wImaginary + currentImaginary * wReal;
					currentReal = nextReal;
				}
			}
		}
	}
}
