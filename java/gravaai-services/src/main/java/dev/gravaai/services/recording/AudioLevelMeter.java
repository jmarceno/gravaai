package dev.gravaai.services.recording;

/**
 * Microphone level maths for the HUD audio meter, ported from
 * {@code src/hooks/useAudioLevelMeter.ts} (plan §5.6.9).
 *
 * <p>The React hook reads {@code AnalyserNode.getByteFrequencyData} with {@code fftSize = 256}
 * (128 frequency bins), the default {@code minDecibels = -100} / {@code maxDecibels = -30} and
 * {@code smoothingTimeConstant = 0.8}, then computes the RMS of those bins and scales it to
 * {@code min(100, (rms / 255) * 100 * 2)}. This class reproduces that chain over PCM the media
 * layer records through libpulse-simple: Blackman window, real FFT, smoothed magnitudes in dB,
 * byte mapping, RMS and scale. Opening the PulseAudio stream is the media layer's job.
 */
public final class AudioLevelMeter {

	/** {@code analyser.fftSize}. */
	public static final int FFT_SIZE = 256;
	/** {@code analyser.frequencyBinCount}. */
	public static final int FREQUENCY_BIN_COUNT = FFT_SIZE / 2;
	/** {@code analyser.smoothingTimeConstant}. */
	public static final double DEFAULT_SMOOTHING_FACTOR = 0.8;
	/** {@code analyser.minDecibels}. */
	public static final double MIN_DECIBELS = -100;
	/** {@code analyser.maxDecibels}. */
	public static final double MAX_DECIBELS = -30;

	private final double smoothingFactor;
	private final double[] smoothedMagnitudes = new double[FREQUENCY_BIN_COUNT];
	private final double[] window = blackmanWindow(FFT_SIZE);
	private final double[] real = new double[FFT_SIZE];
	private final double[] imaginary = new double[FFT_SIZE];

	public AudioLevelMeter() {
		this(DEFAULT_SMOOTHING_FACTOR);
	}

	public AudioLevelMeter(double smoothingFactor) {
		this.smoothingFactor = smoothingFactor;
	}

	private static double[] blackmanWindow(int size) {
		// WebAudio's AnalyserNode applies a Blackman window to the time-domain data
		// before the FFT (W3C Web Audio specification).
		double alpha = 0.16;
		double a0 = (1 - alpha) / 2;
		double a1 = 0.5;
		double a2 = alpha / 2;
		double[] coefficients = new double[size];
		for (int index = 0; index < size; index++) {
			double x = (2 * Math.PI * index) / (size - 1);
			coefficients[index] = a0 - a1 * Math.cos(x) + a2 * Math.cos(2 * x);
		}
		return coefficients;
	}

	/**
	 * Feeds the newest PCM window (48 kHz mono float, at least {@link #FFT_SIZE} samples; the last
	 * 256 are used) and returns the meter level in {@code 0..100}.
	 */
	public double update(float[] samples) {
		if (samples == null || samples.length == 0) {
			return 0;
		}
		int start = Math.max(0, samples.length - FFT_SIZE);
		for (int index = 0; index < FFT_SIZE; index++) {
			int sampleIndex = start + index;
			double sample = sampleIndex < samples.length ? samples[sampleIndex] : 0;
			real[index] = sample * window[index];
			imaginary[index] = 0;
		}
		fft(real, imaginary);
		return levelFromFrequencyData(toFrequencyBytes(real, imaginary));
	}

	/** Magnitudes -> dB -> byte, with per-bin smoothing between frames. */
	private byte[] toFrequencyBytes(double[] transformedReal, double[] transformedImaginary) {
		byte[] data = new byte[FREQUENCY_BIN_COUNT];
		for (int bin = 0; bin < FREQUENCY_BIN_COUNT; bin++) {
			// Normalised amplitude so a full-scale sine reads 0 dB.
			double magnitude = 2 * Math.hypot(transformedReal[bin], transformedImaginary[bin]) / FFT_SIZE;
			double smoothed = smoothingFactor * smoothedMagnitudes[bin]
					+ (1 - smoothingFactor) * magnitude;
			smoothedMagnitudes[bin] = smoothed;
			double decibels = smoothed > 0 ? 20 * Math.log10(smoothed) : Double.NEGATIVE_INFINITY;
			double scaled = 255 * (decibels - MIN_DECIBELS) / (MAX_DECIBELS - MIN_DECIBELS);
			scaled = Math.max(0, Math.min(255, scaled));
			data[bin] = (byte) (scaled <= 0 ? 0 : Math.round(scaled));
		}
		return data;
	}

	/** RMS of the analyser bytes, scaled like the hook: {@code min(100, rms/255*100*2)}. */
	static double levelFromFrequencyData(byte[] frequencyData) {
		if (frequencyData.length == 0) {
			return 0;
		}
		double sum = 0;
		for (byte value : frequencyData) {
			double unsigned = value & 0xFF;
			sum += unsigned * unsigned;
		}
		double rms = Math.sqrt(sum / frequencyData.length);
		return Math.min(100, (rms / 255) * 100 * 2);
	}

	/** In-place iterative radix-2 FFT (final Java features only; no Math library dependency). */
	private static void fft(double[] real, double[] imaginary) {
		int size = real.length;
		for (int index = 1, reversed = 0; index < size; index++) {
			int bit = size >> 1;
			for (; (reversed & bit) != 0; bit >>= 1) {
				reversed ^= bit;
			}
			reversed ^= bit;
			if (index < reversed) {
				double temporary = real[index];
				real[index] = real[reversed];
				real[reversed] = temporary;
				temporary = imaginary[index];
				imaginary[index] = imaginary[reversed];
				imaginary[reversed] = temporary;
			}
		}
		for (int length = 2; length <= size; length <<= 1) {
			double angle = -2 * Math.PI / length;
			double wReal = Math.cos(angle);
			double wImaginary = Math.sin(angle);
			for (int offset = 0; offset < size; offset += length) {
				double currentReal = 1;
				double currentImaginary = 0;
				for (int index = 0; index < length / 2; index++) {
					int even = offset + index;
					int odd = even + length / 2;
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
