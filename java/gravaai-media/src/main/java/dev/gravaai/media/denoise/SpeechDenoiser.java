package dev.gravaai.media.denoise;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.media.onnx.OnnxRuntimeApi;
import dev.gravaai.media.onnx.gen.onnxruntime_h;

/**
 * The speech denoiser: DeepFilterNet3 (48 kHz full band) on the bundled ONNX Runtime, with the
 * signal processing of {@link DenoiseDsp} around it.
 *
 * <p>The network has three parts, shipped as the upstream project exports them: an encoder that
 * reads the ERB and complex features, a decoder that predicts one gain per ERB band, and a decoder
 * that predicts a five-tap complex filter for each of the lowest 96 bins. Above 4.8 kHz the output
 * is the input scaled by the band gains; below it the deep filter rebuilds the speech harmonics
 * from five neighbouring frames (two of them in the future), which is what lets it remove noise
 * <em>between</em> harmonics instead of only turning noisy bands down.
 *
 * <p>Offline by design: audio is pushed in order through a {@link Stream}, which runs the network
 * over long windows (with a few seconds of already-processed frames in front, so the recurrent
 * layers have settled) and hands back the cleaned samples. The output is the input's length and
 * sample-aligned with it. One instance owns three ORT sessions and must not be used from two
 * threads at once; streams of one instance (the channels of one file) are driven from one thread.
 */
public final class SpeechDenoiser implements AutoCloseable {

	/** The encoder, ERB decoder and deep-filter decoder files inside a model directory. */
	public static final String ENCODER_FILE = "enc.onnx";
	public static final String ERB_DECODER_FILE = "erb_dec.onnx";
	public static final String DF_DECODER_FILE = "df_dec.onnx";

	/**
	 * Bumped whenever the output for the same input changes (model, features, filtering), so
	 * cached renders of an older engine are not mistaken for current ones.
	 */
	public static final int ENGINE_VERSION = 1;

	/** Taps of the deep filter and how many of them look ahead. */
	private static final int DF_ORDER = 5;
	private static final int DF_LOOKAHEAD = 2;
	/** How many frames the network's features are shifted into the future. */
	private static final int CONV_LOOKAHEAD = 2;
	/** New frames per network run: 20 s, large enough that the per-run overhead disappears. */
	private static final int CHUNK_FRAMES = 2000;
	/** Already-processed frames replayed in front of a chunk so the GRUs have converged: 3 s. */
	private static final int CONTEXT_FRAMES = 300;
	/** Frames kept behind a chunk for the look-ahead of the features and the filter. */
	private static final int LOOKAHEAD_FRAMES = Math.max(DF_LOOKAHEAD, CONV_LOOKAHEAD);
	/**
	 * Intra-op threads: the graphs are small convolutions and GRUs over a long sequence; four
	 * threads is where the speed-up flattens, and it leaves the editor's other threads alone.
	 */
	private static final int THREADS = 4;

	private final OnnxRuntimeApi api;
	private final MemorySegment encoder;
	private final MemorySegment erbDecoder;
	private final MemorySegment dfDecoder;
	private final MemorySegment memoryInfo;
	private boolean closed;

	private SpeechDenoiser(OnnxRuntimeApi api, MemorySegment encoder, MemorySegment erbDecoder,
			MemorySegment dfDecoder, MemorySegment memoryInfo) {
		this.api = api;
		this.encoder = encoder;
		this.erbDecoder = erbDecoder;
		this.dfDecoder = dfDecoder;
		this.memoryInfo = memoryInfo;
	}

	/** True when {@code modelDir} holds the three model files. */
	public static boolean isInstalled(Path modelDir) {
		return Files.isRegularFile(modelDir.resolve(ENCODER_FILE))
				&& Files.isRegularFile(modelDir.resolve(ERB_DECODER_FILE))
				&& Files.isRegularFile(modelDir.resolve(DF_DECODER_FILE));
	}

	/**
	 * Opens the model (loading ONNX Runtime if needed).
	 *
	 * @throws IllegalArgumentException when a model file is missing
	 * @throws IllegalStateException when the runtime or a model cannot be bound
	 */
	public static SpeechDenoiser open(Path modelDir) {
		if (!isInstalled(modelDir)) {
			throw new IllegalArgumentException("speech denoiser model missing in " + modelDir);
		}
		OnnxRuntimeApi api = OnnxRuntimeApi.bind();
		List<MemorySegment> sessions = new ArrayList<>();
		MemorySegment memoryInfo = MemorySegment.NULL;
		try {
			MemorySegment env = api.createEnv();
			for (String file : List.of(ENCODER_FILE, ERB_DECODER_FILE, DF_DECODER_FILE)) {
				sessions.add(api.createSession(env, modelDir.resolve(file), THREADS));
			}
			memoryInfo = api.createCpuMemoryInfo();
			return new SpeechDenoiser(api, sessions.get(0), sessions.get(1), sessions.get(2),
					memoryInfo);
		} catch (RuntimeException error) {
			if (memoryInfo.address() != 0) {
				api.releaseMemoryInfo(memoryInfo);
			}
			for (MemorySegment session : sessions) {
				api.releaseSession(session);
			}
			api.close();
			throw error;
		}
	}

	/** A new stream for one channel of audio. */
	public Stream newStream() {
		if (closed) {
			throw new IllegalStateException("speech denoiser is closed");
		}
		return new Stream();
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		api.releaseMemoryInfo(memoryInfo);
		api.releaseSession(encoder);
		api.releaseSession(erbDecoder);
		api.releaseSession(dfDecoder);
		api.close();
	}

	/** What one network run predicts for a window of frames. */
	private record Prediction(float[] gains, float[] coefficients) {}

	/**
	 * Runs the three graphs over {@code frames} frames of features.
	 *
	 * @param erbFeatures {@code frames x 32}
	 * @param specFeatures {@code 2 x frames x 96} (real plane, then imaginary plane)
	 */
	private Prediction predict(float[] erbFeatures, float[] specFeatures, int frames) {
		if (closed) {
			throw new IllegalStateException("speech denoiser is closed");
		}
		int floatType = onnxruntime_h.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT();
		try (Arena arena = Arena.ofConfined()) {
			List<String> encoderOutputs = List.of("e0", "e1", "e2", "e3", "emb", "c0");
			MemorySegment[] encoded = api.run(encoder, List.of(
					new OnnxRuntimeApi.Input("feat_erb", floatType,
							new long[] {1, 1, frames, DenoiseDsp.ERB_BANDS},
							arena.allocateFrom(ValueLayout.JAVA_FLOAT, erbFeatures)),
					new OnnxRuntimeApi.Input("feat_spec", floatType,
							new long[] {1, 2, frames, DenoiseDsp.DF_BINS},
							arena.allocateFrom(ValueLayout.JAVA_FLOAT, specFeatures))),
					encoderOutputs, memoryInfo);
			List<OnnxRuntimeApi.Input> carried = new ArrayList<>();
			try {
				for (int index = 0; index < encoded.length; index++) {
					long[] shape = api.valueShape(encoded[index]);
					long count = 1;
					for (long dimension : shape) {
						count *= dimension;
					}
					float[] values = api.getTensorFloats(encoded[index], count);
					carried.add(new OnnxRuntimeApi.Input(encoderOutputs.get(index), floatType,
							shape, arena.allocateFrom(ValueLayout.JAVA_FLOAT, values)));
				}
			} finally {
				for (MemorySegment value : encoded) {
					api.releaseValue(value);
				}
			}
			OnnxRuntimeApi.Input e0 = carried.get(0);
			OnnxRuntimeApi.Input e1 = carried.get(1);
			OnnxRuntimeApi.Input e2 = carried.get(2);
			OnnxRuntimeApi.Input e3 = carried.get(3);
			OnnxRuntimeApi.Input emb = carried.get(4);
			OnnxRuntimeApi.Input c0 = carried.get(5);
			float[] gains = runSingle(erbDecoder, List.of(emb, e3, e2, e1, e0), "m",
					(long) frames * DenoiseDsp.ERB_BANDS);
			float[] coefficients = runSingle(dfDecoder, List.of(emb, c0), "coefs",
					(long) frames * DenoiseDsp.DF_BINS * DF_ORDER * 2);
			return new Prediction(gains, coefficients);
		}
	}

	private float[] runSingle(MemorySegment session, List<OnnxRuntimeApi.Input> inputs,
			String output, long expected) {
		MemorySegment[] values = api.run(session, inputs, List.of(output), memoryInfo);
		try {
			long count = 1;
			for (long dimension : api.valueShape(values[0])) {
				count *= dimension;
			}
			if (count != expected) {
				throw new IllegalStateException("speech denoiser output " + output + " holds "
						+ count + " values, expected " + expected);
			}
			return api.getTensorFloats(values[0], count);
		} finally {
			api.releaseValue(values[0]);
		}
	}

	/** One analysed frame, kept until every output frame that reads it has been produced. */
	private record Frame(float[] spectrum, float[] erbFeatures, float[] specFeatures) {}

	/**
	 * One channel's pass through the denoiser. Push the samples in order with {@link #write},
	 * call {@link #finish} once at the end, and collect cleaned samples from {@link #read} in
	 * between: everything written comes back out, the same length and aligned.
	 */
	public final class Stream {

		private final DenoiseDsp.Analyzer analyzer = new DenoiseDsp.Analyzer();
		private final DenoiseDsp.Synthesizer synthesizer = new DenoiseDsp.Synthesizer();
		/** Analysed frames from global index {@link #firstFrame} on. */
		private final List<Frame> frames = new ArrayList<>();
		private int firstFrame;
		/** Frames already synthesised. */
		private int producedFrames;
		private final float[] hop = new float[DenoiseDsp.HOP_SIZE];
		private int hopFill;
		private long samplesWritten;
		private long samplesDelivered;
		private boolean finished;
		/** Synthesised samples not yet read. */
		private float[] ready = new float[CHUNK_FRAMES * DenoiseDsp.HOP_SIZE];
		private int readyCount;
		/**
		 * The overlap-add hands every sample back one hop late; that first hop of the output is
		 * dropped so the result lines up with the input.
		 */
		private int latencyToDrop = DenoiseDsp.FFT_SIZE - DenoiseDsp.HOP_SIZE;

		private Stream() {}

		/** Feeds {@code count} mono samples (48 kHz, -1..1). */
		public void write(float[] samples, int offset, int count) {
			if (finished) {
				throw new IllegalStateException("the stream is finished");
			}
			samplesWritten += count;
			int position = offset;
			int remaining = count;
			while (remaining > 0) {
				int take = Math.min(remaining, DenoiseDsp.HOP_SIZE - hopFill);
				System.arraycopy(samples, position, hop, hopFill, take);
				hopFill += take;
				position += take;
				remaining -= take;
				if (hopFill == DenoiseDsp.HOP_SIZE) {
					analyzeHop();
				}
			}
			while (frames.size() - (producedFrames - firstFrame)
					>= CHUNK_FRAMES + LOOKAHEAD_FRAMES) {
				produce(CHUNK_FRAMES);
			}
		}

		/** Flushes the tail; after this {@link #read} drains the last samples. */
		public void finish() {
			if (finished) {
				return;
			}
			finished = true;
			if (hopFill > 0) {
				java.util.Arrays.fill(hop, hopFill, DenoiseDsp.HOP_SIZE, 0f);
				analyzeHop();
			}
			// One more (silent) hop pushes the last real hop through the overlap-add.
			java.util.Arrays.fill(hop, 0f);
			analyzeHop();
			int pending = firstFrame + frames.size() - producedFrames;
			while (pending > 0) {
				int count = Math.min(pending, CHUNK_FRAMES);
				produce(count);
				pending -= count;
			}
		}

		/** Cleaned samples available to {@link #read}. */
		public int available() {
			// Never more than was written: the flush hops at the end are not the caller's audio.
			return (int) Math.min(readyCount, samplesWritten - samplesDelivered);
		}

		/** Copies up to {@code count} cleaned samples out; returns how many were copied. */
		public int read(float[] out, int offset, int count) {
			int take = Math.min(count, available());
			if (take <= 0) {
				return 0;
			}
			System.arraycopy(ready, 0, out, offset, take);
			System.arraycopy(ready, take, ready, 0, readyCount - take);
			readyCount -= take;
			samplesDelivered += take;
			return take;
		}

		private void analyzeHop() {
			Frame frame = new Frame(new float[DenoiseDsp.BINS * 2],
					new float[DenoiseDsp.ERB_BANDS], new float[DenoiseDsp.DF_BINS * 2]);
			analyzer.analyze(hop, 0, frame.spectrum(), frame.erbFeatures(), frame.specFeatures());
			frames.add(frame);
			hopFill = 0;
		}

		private @org.jspecify.annotations.Nullable Frame frame(int globalIndex) {
			int local = globalIndex - firstFrame;
			return local >= 0 && local < frames.size() ? frames.get(local) : null;
		}

		/** Runs the network for the next {@code count} frames and synthesises them. */
		private void produce(int count) {
			int total = firstFrame + frames.size();
			int windowStart = Math.max(firstFrame, producedFrames - CONTEXT_FRAMES);
			int windowEnd = Math.min(total, producedFrames + count + LOOKAHEAD_FRAMES);
			int windowFrames = windowEnd - windowStart;
			float[] erbFeatures = new float[windowFrames * DenoiseDsp.ERB_BANDS];
			float[] specFeatures = new float[2 * windowFrames * DenoiseDsp.DF_BINS];
			int imaginaryPlane = windowFrames * DenoiseDsp.DF_BINS;
			for (int t = 0; t < windowFrames; t++) {
				// The network reads the features of two frames ahead (zeros past the end).
				Frame ahead = frame(windowStart + t + CONV_LOOKAHEAD);
				if (ahead == null) {
					continue;
				}
				System.arraycopy(ahead.erbFeatures(), 0, erbFeatures, t * DenoiseDsp.ERB_BANDS,
						DenoiseDsp.ERB_BANDS);
				float[] spec = ahead.specFeatures();
				int row = t * DenoiseDsp.DF_BINS;
				for (int bin = 0; bin < DenoiseDsp.DF_BINS; bin++) {
					specFeatures[row + bin] = spec[2 * bin];
					specFeatures[imaginaryPlane + row + bin] = spec[2 * bin + 1];
				}
			}
			Prediction prediction = predict(erbFeatures, specFeatures, windowFrames);
			float[] enhanced = new float[DenoiseDsp.BINS * 2];
			ensureReadyCapacity(count * DenoiseDsp.HOP_SIZE);
			for (int index = 0; index < count; index++) {
				int global = producedFrames + index;
				int t = global - windowStart;
				Frame current = frames.get(global - firstFrame);
				float[] spectrum = current.spectrum();
				// Above the deep-filter band: the input scaled by its ERB band's gain.
				int gainRow = t * DenoiseDsp.ERB_BANDS;
				for (int bin = DenoiseDsp.DF_BINS; bin < DenoiseDsp.BINS; bin++) {
					float gain = prediction.gains()[gainRow + DenoiseDsp.bandOf(bin)];
					enhanced[2 * bin] = spectrum[2 * bin] * gain;
					enhanced[2 * bin + 1] = spectrum[2 * bin + 1] * gain;
				}
				// The deep-filter band: a complex five-tap filter over frames t-2 .. t+2.
				int coefficientRow = t * DenoiseDsp.DF_BINS * DF_ORDER * 2;
				java.util.Arrays.fill(enhanced, 0, DenoiseDsp.DF_BINS * 2, 0f);
				for (int tap = 0; tap < DF_ORDER; tap++) {
					Frame source = frame(global + tap - (DF_ORDER - 1 - DF_LOOKAHEAD));
					if (source == null) {
						continue;
					}
					float[] sourceSpectrum = source.spectrum();
					for (int bin = 0; bin < DenoiseDsp.DF_BINS; bin++) {
						int at = coefficientRow + (bin * DF_ORDER + tap) * 2;
						float cr = prediction.coefficients()[at];
						float ci = prediction.coefficients()[at + 1];
						float sr = sourceSpectrum[2 * bin];
						float si = sourceSpectrum[2 * bin + 1];
						enhanced[2 * bin] += sr * cr - si * ci;
						enhanced[2 * bin + 1] += sr * ci + si * cr;
					}
				}
				synthesizer.synthesize(enhanced, ready, readyCount);
				readyCount += DenoiseDsp.HOP_SIZE;
				if (latencyToDrop > 0) {
					int drop = Math.min(latencyToDrop, readyCount);
					System.arraycopy(ready, drop, ready, 0, readyCount - drop);
					readyCount -= drop;
					latencyToDrop -= drop;
				}
			}
			producedFrames += count;
			// Keep what the next chunk replays as context and reads for its filter taps.
			int keepFrom = producedFrames - CONTEXT_FRAMES - DF_ORDER;
			int drop = Math.min(frames.size(), keepFrom - firstFrame);
			if (drop > 0) {
				frames.subList(0, drop).clear();
				firstFrame += drop;
			}
		}

		private void ensureReadyCapacity(int extra) {
			if (readyCount + extra > ready.length) {
				ready = java.util.Arrays.copyOf(ready, Math.max(ready.length * 2, readyCount + extra));
			}
		}
	}
}
