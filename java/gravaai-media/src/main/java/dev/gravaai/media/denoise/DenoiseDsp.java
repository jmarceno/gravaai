package dev.gravaai.media.denoise;

/**
 * The signal processing around the DeepFilterNet3 network ({@link SpeechDenoiser}): the framing,
 * the spectral features the network reads and the resynthesis of its output. It follows the
 * reference implementation (libDF) value for value, because the network was trained on exactly
 * these features — a different window, scale or normalisation would still "run" and quietly
 * produce worse audio.
 *
 * <p>Everything here is fixed by the model's {@code config.ini}: 48 kHz, a 960-sample Vorbis
 * window hopped by 480 (20 ms frames every 10 ms), 32 ERB bands for the gain mask and the lowest
 * 96 bins for deep filtering. No ONNX here, so the maths is unit-testable on plain arrays.
 */
public final class DenoiseDsp {

	/** The only sample rate the model handles. */
	public static final int SAMPLE_RATE = 48_000;
	/** STFT size: 20 ms. */
	public static final int FFT_SIZE = 960;
	/** Hop: 10 ms, half the window. */
	public static final int HOP_SIZE = 480;
	/** Complex bins of one frame ({@code FFT_SIZE / 2 + 1}). */
	public static final int BINS = FFT_SIZE / 2 + 1;
	/** ERB bands of the gain mask. */
	public static final int ERB_BANDS = 32;
	/** Low bins (0–4.8 kHz) the deep filter reconstructs. */
	public static final int DF_BINS = 96;
	/** No ERB band is narrower than this many bins. */
	private static final int MIN_ERB_BINS = 2;
	/**
	 * The feature normalisation's smoothing factor: {@code exp(-hop / sr / 1 s)} rounded to the
	 * first precision that stays below one, exactly as the reference computes it.
	 */
	static final double NORM_ALPHA = 0.99;

	private static final double[] WINDOW = vorbisWindow();
	private static final int[] ERB_WIDTHS = erbWidths();
	private static final int[] BIN_TO_BAND = binToBand();

	private DenoiseDsp() {}

	/** The Vorbis power-complementary window: its square and its half-shifted square sum to one. */
	static double[] vorbisWindow() {
		double[] window = new double[FFT_SIZE];
		int half = FFT_SIZE / 2;
		for (int i = 0; i < FFT_SIZE; i++) {
			double sine = Math.sin(0.5 * Math.PI * (i + 0.5) / half);
			window[i] = Math.sin(0.5 * Math.PI * sine * sine);
		}
		return window;
	}

	/** How many bins each ERB band covers, low band first; the widths sum to {@link #BINS}. */
	static int[] erbWidths() {
		double nyquist = SAMPLE_RATE / 2.0;
		double binWidth = (double) SAMPLE_RATE / FFT_SIZE;
		double low = frequencyToErb(0);
		double high = frequencyToErb(nyquist);
		double step = (high - low) / ERB_BANDS;
		int[] widths = new int[ERB_BANDS];
		int previousBin = 0;
		int carried = 0;
		for (int band = 1; band <= ERB_BANDS; band++) {
			int bin = (int) Math.round(erbToFrequency(low + band * step) / binWidth);
			int width = bin - previousBin - carried;
			if (width < MIN_ERB_BINS) {
				carried = MIN_ERB_BINS - width;
				width = MIN_ERB_BINS;
			} else {
				carried = 0;
			}
			widths[band - 1] = width;
			previousBin = bin;
		}
		// The table counts FFT_SIZE / 2 bins; the Nyquist bin joins the last band.
		widths[ERB_BANDS - 1] += 1;
		int total = 0;
		for (int width : widths) {
			total += width;
		}
		if (total > BINS) {
			widths[ERB_BANDS - 1] -= total - BINS;
		}
		return widths;
	}

	private static double frequencyToErb(double hertz) {
		return 9.265 * Math.log1p(hertz / (24.7 * 9.265));
	}

	private static double erbToFrequency(double erb) {
		return 24.7 * 9.265 * (Math.exp(erb / 9.265) - 1);
	}

	private static int[] binToBand() {
		int[] bands = new int[BINS];
		int bin = 0;
		for (int band = 0; band < ERB_BANDS; band++) {
			for (int i = 0; i < ERB_WIDTHS[band] && bin < BINS; i++) {
				bands[bin++] = band;
			}
		}
		while (bin < BINS) {
			bands[bin++] = ERB_BANDS - 1;
		}
		return bands;
	}

	/** The ERB band a bin belongs to (the mask's gain for the band applies to the bin). */
	static int bandOf(int bin) {
		return BIN_TO_BAND[bin];
	}

	/**
	 * One channel's analysis: turns successive 480-sample hops into a spectrum plus the two
	 * normalised feature vectors the network reads. Stateful (window overlap and the running
	 * feature statistics), so one instance follows one channel from its first sample.
	 */
	static final class Analyzer {

		private final Fft fft = new Fft(FFT_SIZE);
		private final double[] overlap = new double[HOP_SIZE];
		private final double[] real = new double[FFT_SIZE];
		private final double[] imaginary = new double[FFT_SIZE];
		private final double[] erbMean = new double[ERB_BANDS];
		private final double[] unitNorm = new double[DF_BINS];

		Analyzer() {
			// The reference's start values: a plausible speech spectrum, so the first second is
			// not normalised against silence.
			for (int band = 0; band < ERB_BANDS; band++) {
				erbMean[band] = -60.0 + (-90.0 + 60.0) * band / (ERB_BANDS - 1);
			}
			for (int bin = 0; bin < DF_BINS; bin++) {
				unitNorm[bin] = 0.001 + (0.0001 - 0.001) * bin / (DF_BINS - 1);
			}
		}

		/**
		 * Analyses one hop.
		 *
		 * @param hop {@link #HOP_SIZE} new samples
		 * @param spectrum out: {@code BINS} complex bins as interleaved re/im
		 * @param erbFeatures out: {@link #ERB_BANDS} normalised log band energies
		 * @param specFeatures out: {@link #DF_BINS} unit-normalised complex bins, interleaved
		 */
		void analyze(float[] hop, int offset, float[] spectrum, float[] erbFeatures,
				float[] specFeatures) {
			for (int i = 0; i < HOP_SIZE; i++) {
				real[i] = overlap[i] * WINDOW[i];
				double sample = hop[offset + i];
				real[HOP_SIZE + i] = sample * WINDOW[HOP_SIZE + i];
				overlap[i] = sample;
			}
			java.util.Arrays.fill(imaginary, 0);
			fft.transform(real, imaginary, false);
			// The reference scales the forward transform so that the unscaled inverse and the
			// squared window reconstruct the input exactly.
			double scale = 1.0 / FFT_SIZE;
			for (int bin = 0; bin < BINS; bin++) {
				spectrum[2 * bin] = (float) (real[bin] * scale);
				spectrum[2 * bin + 1] = (float) (imaginary[bin] * scale);
			}
			int bin = 0;
			for (int band = 0; band < ERB_BANDS; band++) {
				double energy = 0;
				int width = ERB_WIDTHS[band];
				for (int i = 0; i < width; i++, bin++) {
					double re = spectrum[2 * bin];
					double im = spectrum[2 * bin + 1];
					energy += re * re + im * im;
				}
				double decibel = 10 * Math.log10(energy / width + 1e-10);
				erbMean[band] = decibel * (1 - NORM_ALPHA) + erbMean[band] * NORM_ALPHA;
				erbFeatures[band] = (float) ((decibel - erbMean[band]) / 40.0);
			}
			for (int low = 0; low < DF_BINS; low++) {
				double re = spectrum[2 * low];
				double im = spectrum[2 * low + 1];
				unitNorm[low] = Math.hypot(re, im) * (1 - NORM_ALPHA) + unitNorm[low] * NORM_ALPHA;
				double divisor = Math.sqrt(unitNorm[low]);
				specFeatures[2 * low] = (float) (re / divisor);
				specFeatures[2 * low + 1] = (float) (im / divisor);
			}
		}
	}

	/** One channel's resynthesis: inverse transform, window and overlap-add. */
	static final class Synthesizer {

		private final Fft fft = new Fft(FFT_SIZE);
		private final double[] overlap = new double[HOP_SIZE];
		private final double[] real = new double[FFT_SIZE];
		private final double[] imaginary = new double[FFT_SIZE];

		/**
		 * Synthesises one hop from a spectrum.
		 *
		 * @param spectrum {@code BINS} complex bins, interleaved re/im
		 * @param out receives {@link #HOP_SIZE} samples at {@code offset}
		 */
		void synthesize(float[] spectrum, float[] out, int offset) {
			for (int bin = 0; bin < BINS; bin++) {
				real[bin] = spectrum[2 * bin];
				imaginary[bin] = spectrum[2 * bin + 1];
			}
			// The upper half is the conjugate mirror of a real signal's spectrum.
			for (int bin = BINS; bin < FFT_SIZE; bin++) {
				real[bin] = real[FFT_SIZE - bin];
				imaginary[bin] = -imaginary[FFT_SIZE - bin];
			}
			fft.transform(real, imaginary, true);
			for (int i = 0; i < HOP_SIZE; i++) {
				out[offset + i] = (float) (real[i] * WINDOW[i] + overlap[i]);
				overlap[i] = real[HOP_SIZE + i] * WINDOW[HOP_SIZE + i];
			}
		}
	}

	/**
	 * A complex FFT for sizes that are products of small primes (960 = 2⁶·3·5), unscaled in both
	 * directions. Recursive mixed radix with a shared twiddle table; a frame is transformed in
	 * microseconds, which is all the denoiser needs.
	 */
	static final class Fft {

		private final int size;
		private final double[] cosine;
		private final double[] sine;
		private final double[] workReal;
		private final double[] workImaginary;

		Fft(int size) {
			this.size = size;
			this.cosine = new double[size];
			this.sine = new double[size];
			for (int i = 0; i < size; i++) {
				double angle = -2 * Math.PI * i / size;
				cosine[i] = Math.cos(angle);
				sine[i] = Math.sin(angle);
			}
			this.workReal = new double[size];
			this.workImaginary = new double[size];
		}

		/** Transforms in place; {@code inverse} conjugates the kernel (no 1/N scaling). */
		void transform(double[] real, double[] imaginary, boolean inverse) {
			System.arraycopy(real, 0, workReal, 0, size);
			System.arraycopy(imaginary, 0, workImaginary, 0, size);
			recurse(workReal, workImaginary, 0, 1, size, real, imaginary, 0, inverse);
		}

		private void recurse(double[] inReal, double[] inImaginary, int inOffset, int stride,
				int length, double[] outReal, double[] outImaginary, int outOffset,
				boolean inverse) {
			if (length == 1) {
				outReal[outOffset] = inReal[inOffset];
				outImaginary[outOffset] = inImaginary[inOffset];
				return;
			}
			int radix = smallestFactor(length);
			int sub = length / radix;
			for (int q = 0; q < radix; q++) {
				recurse(inReal, inImaginary, inOffset + q * stride, stride * radix, sub, outReal,
						outImaginary, outOffset + q * sub, inverse);
			}
			int twiddleStep = size / length;
			double[] bufferReal = new double[radix];
			double[] bufferImaginary = new double[radix];
			for (int k = 0; k < sub; k++) {
				for (int q = 0; q < radix; q++) {
					bufferReal[q] = outReal[outOffset + q * sub + k];
					bufferImaginary[q] = outImaginary[outOffset + q * sub + k];
				}
				for (int j = 0; j < radix; j++) {
					int index = k + j * sub;
					double sumReal = 0;
					double sumImaginary = 0;
					for (int q = 0; q < radix; q++) {
						int twiddle = (int) (((long) q * index % length) * twiddleStep);
						double c = cosine[twiddle];
						double s = inverse ? -sine[twiddle] : sine[twiddle];
						sumReal += bufferReal[q] * c - bufferImaginary[q] * s;
						sumImaginary += bufferReal[q] * s + bufferImaginary[q] * c;
					}
					outReal[outOffset + index] = sumReal;
					outImaginary[outOffset + index] = sumImaginary;
				}
			}
		}

		private static int smallestFactor(int value) {
			for (int factor = 2; (long) factor * factor <= value; factor++) {
				if (value % factor == 0) {
					return factor;
				}
			}
			return value;
		}
	}
}
