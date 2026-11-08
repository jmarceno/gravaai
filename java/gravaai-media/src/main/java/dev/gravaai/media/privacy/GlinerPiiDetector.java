package dev.gravaai.media.privacy;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.gravaai.media.onnx.OnnxRuntimeApi;

/**
 * GLiNER zero-shot PII detection ({@code urchade/gliner_multi_pii-v1}, ONNX) — the screen-text half
 * of auto masking.
 *
 * <p>GLiNER is prompt-based: the input is {@code <<ENT>> label <<ENT>> label … <<SEP>> text words},
 * and the graph scores every {@code (token start, width)} span against every label. That is what
 * makes it a good fit here — the entity types are a runtime choice, so the label set stays in one
 * place ({@link #PII_LABELS}) and is not baked into a 35-class token-classification head.</p>
 *
 * <h2>The ONNX contract</h2>
 *
 * <p>Reverse-engineered from the export and pinned by {@code GlinerPiiDetectorTest}, because the
 * graph's published signature hides most of it:</p>
 * <ul>
 *   <li>{@code input_ids}/{@code attention_mask} — the tokenized prompt+text window.</li>
 *   <li>{@code words_mask} — per token, its <em>text</em> word index + 1 for the first subtoken of
 *       each word, {@code 0} for prompt/special/continuation tokens. Word indices restart at 1
 *       after the prompt.</li>
 *   <li>{@code text_lengths} — the number of text words (not tokens).</li>
 *   <li>{@code span_idx}/{@code span_mask} — the {@code (start, start+width)} candidate spans over
 *       <em>words</em>, start-major, {@code max_width} = 12, masked where {@code start+width} runs
 *       past the last word.</li>
 * </ul>
 * <p>The output {@code logits} is {@code [text_words, max_width, labels]} — class index 0 is the
 * first label (there is no background class), and scores are sigmoid probabilities.</p>
 *
 * <h2>Model choice: fp16, not int8</h2>
 *
 * <p>The plan expected the ~300 MB int8 export to be the shipped asset. Measured here, the int8
 * export returns near-uniform scores (max ≈ 0.2 across all spans) and detects nothing at any
 * threshold — the failure mode the research flagged for quantized GLiNER. The fp16 export
 * (580 MB) is correct and runs at ~120 ms per 384-token window on CPU, which matches the plan's
 * budget. {@code GlinerPiiDetectorTest} asserts a real detection, so a future asset bump that
 * breaks the model fails the suite instead of silently masking nothing.</p>
 *
 * <p>Single-caller: an ORT session must not run concurrently, so the analysis pass owns one.</p>
 */
public final class GlinerPiiDetector implements AutoCloseable {

	/**
	 * The entity types the detector asks for, in class-index order. Kept short and screen-shaped:
	 * every extra label costs accuracy and a wider output tensor. Order matters — it is the prompt
	 * order the model keys its class indices to.
	 */
	public static final List<String> PII_LABELS = List.of(
			"person",
			"email",
			"phone number",
			"credit card number",
			"address",
			"date of birth",
			"username",
			"password",
			"social security number",
			"bank account number",
			"name");

	/** The checkpoint's maximum span width in words (its {@code max_width} config). */
	private static final int MAX_SPAN_WIDTH = 12;
	/** The checkpoint's maximum window in tokens (its {@code max_len} config). */
	private static final int MAX_WINDOW_TOKENS = 384;

	private final OnnxRuntimeApi api;
	private final MemorySegment session;
	private final MemorySegment memoryInfo;
	private final String[] inputNames;
	private final GlinerTokenizer tokenizer;
	private final float threshold;
	private boolean closed;

	/**
	 * Opens the model and its tokenizer from one directory (the first-use download keeps both
	 * files together).
	 *
	 * @param threshold sigmoid score below which a span is not reported
	 */
	public GlinerPiiDetector(Path modelDir, float threshold, int threads) {
		Path modelFile = modelDir.resolve("model.onnx");
		if (!Files.isRegularFile(modelFile)) {
			throw new IllegalArgumentException("GLiNER model missing: " + modelFile);
		}
		this.threshold = threshold;
		this.tokenizer = GlinerTokenizer.load(modelDir.resolve("tokenizer.json"));
		this.api = OnnxRuntimeApi.bind();
		MemorySegment env = api.createEnv();
		this.session = api.createSession(env, modelFile, threads);
		this.memoryInfo = api.createCpuMemoryInfo();
		MemorySegment allocator = api.defaultAllocator();
		int count = (int) api.sessionInputCount(session);
		this.inputNames = new String[count];
		for (int i = 0; i < count; i++) {
			inputNames[i] = api.sessionInputName(session, allocator, i);
		}
		// The six-input, single-logits-output shape is how this export is driven; a different
		// export needs a matching decoder, so fail loudly rather than guess.
		if (count != 6) {
			throw new IllegalArgumentException("expected the GLiNER 6-input export, found " + count
					+ " inputs: " + modelFile);
		}
		if (api.sessionOutputCount(session) < 1) {
			throw new IllegalArgumentException("GLiNER model has no outputs: " + modelFile);
		}
	}

	/**
	 * {@link #PII_LABELS} with the production threshold and a single intra-op thread.
	 *
	 * <p>0.5, not the 0.4 that scores identically on the fixture corpus: the ordering above is
	 * what lifts the hard cases (an unspaced card number scores 0.53), and a lower threshold would
	 * buy no recall while letting more false positives through. {@code GlinerPiiDetectorTest}
	 * pins both the detection and the clean-text rejection.</p>
	 */
	public static GlinerPiiDetector open(Path modelDir) {
		return new GlinerPiiDetector(modelDir, 0.5f, 1);
	}

	/**
	 * One detected span: the label, the half-open word range within the text, and the score.
	 *
	 * <p>Word indices refer to {@link GlinerTokenizer#splitWords(String)} of the same text, which
	 * is how the caller maps a span back to its OCR box.</p>
	 */
	public record Span(String label, int startWord, int endWord, float score) {

		public Span {
			if (startWord < 0 || endWord <= startWord) {
				throw new IllegalArgumentException("invalid span range: " + startWord + ".."
						+ endWord);
			}
		}

		public int wordCount() {
			return endWord - startWord;
		}
	}

	/**
	 * Detects PII in one text line. Empty text or an already-cancelled caller returns an empty
	 * list; a text longer than the model window is cut to its first {@link #MAX_WINDOW_TOKENS}
	 * tokens, which for screen text (a single line) never happens.
	 */
	public List<Span> detect(String text) {
		if (closed) {
			throw new IllegalStateException("GLiNER detector is closed");
		}
		if (text == null || text.isBlank()) {
			return List.of();
		}
		GlinerTokenizer.Encoding encoding = tokenizer.encode(text, PII_LABELS);
		if (encoding.length() > MAX_WINDOW_TOKENS) {
			return List.of();
		}
		int words = encoding.textWordCount();
		if (words == 0) {
			return List.of();
		}
		long[] ids = toLongs(encoding.ids());
		long[] wordIds = promptAwareWordMask(encoding);
		long[] attention = new long[ids.length];
		java.util.Arrays.fill(attention, 1);

		int len = ids.length;
		int numSpans = words * MAX_SPAN_WIDTH;
		long[] spanIdx = new long[numSpans * 2];
		byte[] spanMask = new byte[numSpans];
		for (int start = 0; start < words; start++) {
			for (int width = 0; width < MAX_SPAN_WIDTH; width++) {
				int span = start * MAX_SPAN_WIDTH + width;
				spanIdx[span * 2] = start;
				spanIdx[span * 2 + 1] = start + width;
				spanMask[span] = (byte) (start + width < words ? 1 : 0);
			}
		}

		// runMixed copies every array into the arena the call owns, so nothing here outlives it.
		MemorySegment[] outputs = api.runMixed(session, memoryInfo,
				List.of(
						new OnnxRuntimeApi.NamedLong(named(0, "input_ids"),
								new long[] {1, len}, ids),
						new OnnxRuntimeApi.NamedLong(named(1, "attention_mask"),
								new long[] {1, len}, attention),
						new OnnxRuntimeApi.NamedLong(named(2, "words_mask"),
								new long[] {1, len}, wordIds),
						new OnnxRuntimeApi.NamedLong(named(3, "text_lengths"),
								new long[] {1, 1}, new long[] {words}),
						new OnnxRuntimeApi.NamedLong(named(4, "span_idx"),
								new long[] {1, numSpans, 2}, spanIdx)),
				List.of(new OnnxRuntimeApi.NamedBool(named(5, "span_mask"),
						new long[] {1, numSpans}, spanMask)),
				List.of(outputName()));
		try {
			long[] shape = api.valueShape(outputs[0]);
			// [1, words, max_width, labels]
			if (shape.length != 4 || shape[1] != words || shape[2] != MAX_SPAN_WIDTH) {
				throw new IllegalStateException("unexpected GLiNER logits shape: "
						+ java.util.Arrays.toString(shape));
			}
			int labelCount = (int) shape[3];
			if (labelCount != PII_LABELS.size()) {
				throw new IllegalStateException("GLiNER returned " + labelCount
						+ " labels, expected " + PII_LABELS.size());
			}
			float[] logits = api.getTensorFloats(outputs[0],
					shape[0] * shape[1] * shape[2] * shape[3]);
			return decode(logits, words, labelCount);
		} finally {
			for (MemorySegment output : outputs) {
				api.releaseValue(output);
			}
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		api.releaseMemoryInfo(memoryInfo);
		api.releaseSession(session);
		api.close();
	}

	/** A short label for logs. */
	public String describe() {
		return tokenizer.describe() + ", " + PII_LABELS.size() + " labels";
	}

	/**
	 * Turns the raw logits into spans: sigmoid, argmax per span, keep those over the threshold,
	 * then a greedy dedupe that drops a span contained in a higher-scoring span of the same label.
	 */
	private List<Span> decode(float[] logits, int words, int labelCount) {
		int numSpans = words * MAX_SPAN_WIDTH;
		List<Span> found = new ArrayList<>();
		for (int start = 0; start < words; start++) {
			for (int width = 0; width < MAX_SPAN_WIDTH; width++) {
				int end = start + width + 1;
				if (end > words) {
					continue;
				}
				int span = start * MAX_SPAN_WIDTH + width;
				int bestLabel = -1;
				float bestScore = 0;
				for (int label = 0; label < labelCount; label++) {
					int index = (span * labelCount) + label;
					float score = sigmoid(logits[index]);
					if (score > bestScore) {
						bestScore = score;
						bestLabel = label;
					}
				}
				if (bestLabel >= 0 && bestScore >= threshold) {
					found.add(new Span(PII_LABELS.get(bestLabel), start, end, bestScore));
				}
			}
		}
		found.sort(Comparator.comparingDouble(Span::score).reversed());
		List<Span> kept = new ArrayList<>();
		for (Span candidate : found) {
			boolean contained = kept.stream().anyMatch(winner ->
					winner.label().equals(candidate.label())
							&& winner.startWord() <= candidate.startWord()
							&& winner.endWord() >= candidate.endWord());
			if (!contained) {
				kept.add(candidate);
			}
		}
		kept.sort(Comparator.comparingInt(Span::startWord).thenComparingInt(Span::endWord));
		return List.copyOf(kept);
	}

	/** {@code words_mask} in the numbering the graph expects: text words restart at 1. */
	private static long[] promptAwareWordMask(GlinerTokenizer.Encoding encoding) {
		long[] mask = new long[encoding.ids().length];
		for (int i = 0; i < mask.length; i++) {
			mask[i] = encoding.wordIndices()[i] < 0 ? 0 : encoding.wordIndices()[i] + 1L;
		}
		return mask;
	}

	private String named(int index, String expected) {
		String actual = inputNames[index];
		// The export's input order is fixed; a reordered export would mean a different graph.
		if (!expected.equals(actual)) {
			throw new IllegalStateException("GLiNER input " + index + " is " + actual
					+ ", expected " + expected);
		}
		return actual;
	}

	private String outputName() {
		return api.sessionOutputName(session, api.defaultAllocator(), 0);
	}

	private static long[] toLongs(int[] values) {
		long[] out = new long[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = values[i];
		}
		return out;
	}

	private static float sigmoid(float x) {
		return (float) (1 / (1 + Math.exp(-x)));
	}
}
