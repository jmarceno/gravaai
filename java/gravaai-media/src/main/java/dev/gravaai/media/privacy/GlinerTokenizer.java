package dev.gravaai.media.privacy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * Minimal pure-Java port of the SentencePiece Unigram tokenizer that
 * {@code urchade/gliner_multi_pii-v1} ships in its {@code tokenizer.json}.
 *
 * <p>The plan left this open ("hand-rolled … decision gate: token-identical output vs the HF
 * reference"); {@code GlinerTokenizerTest} is that gate. It reproduces the reference on the
 * committed corpus, which is what the GLiNER prompt construction needs — a tokenizer that is
 * merely close would shift span boundaries.</p>
 *
 * <p>Three pieces of the upstream pipeline are reproduced:</p>
 * <ol>
 *   <li><b>Normalizer.</b> SentencePiece's precompiled charsmap is NFKC plus a whitespace/control
 *       mapping. Rather than ship the 237 KB binary blob, this applies {@link Normalizer#NFKC} and
 *       then the exact codepoint sets derived from the reference normalizer (29 codepoints become a
 *       space, 30 C0 controls are dropped), then collapses runs of spaces. The codepoint lists are
 *       verified against the reference in {@code GlinerTokenizerTest}.</li>
 *   <li><b>Word splitting.</b> GLiNER tokenizes whitespace-separated words with punctuation split
 *       off ({@code \w+|[^\w\s]}), and the prompt markers ({@code <<ENT>>}, {@code <<SEP>>}) are
 *       separate words. This is why the model sees the text one word at a time instead of as one
 *       character stream.</li>
 *   <li><b>Unigram Viterbi.</b> Best-scoring segmentation of {@code "▁" + word} against the
 *       checkpoint's 250k-piece vocabulary, with {@code [UNK]} for unreachable characters.</li>
 * </ol>
 *
 * <p>Special tokens are added as the HuggingFace post-processor does: {@code [CLS]} first,
 * {@code [SEP]} last, and per-word ids where {@code null} marks a special/prompt token. The
 * per-word id is what GLiNER's {@code words_mask} is derived from, so it has to be exact.</p>
 */
public final class GlinerTokenizer {

	/** SentencePiece's metaspace character: every word is prefixed with it before encoding. */
	private static final char METASPACE = '\u2581';

	/** The prompt markers the checkpoint was trained with ({@code ent_token}/{@code sep_token}). */
	public static final String ENT_TOKEN = "<<ENT>>";
	public static final String SEP_TOKEN = "<<SEP>>";

	private static final int CLS_ID = 1;
	private static final int SEP_ID = 2;

	/**
	 * Codepoints the reference normalizer turns into a space: whitespace (Zs/Zl/Zp) plus the
	 * separator-ish format characters it treats the same way.
	 */
	private static final Set<Integer> TO_SPACE = Set.of(
			0x9, 0xa, 0xc, 0xd, 0x20, 0xa0, 0x1680,
			0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009,
			0x200a, 0x200b, 0x200c, 0x200d, 0x200e, 0x200f,
			0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff);

	/** Codepoints the reference normalizer deletes (C0/C1 controls it does not treat as space). */
	private static final Set<Integer> DROP = Set.of(
			0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8, 0xb,
			0xe, 0xf, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a,
			0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x7f, 0x8f, 0x9f);

	/** GLiNER's words splitter: runs of word characters, else one non-space character. */
	private static final Pattern WORD = Pattern.compile("\\w+|[^\\w\\s]",
			Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern SPACE_RUN = Pattern.compile(" {2,}");

	/** Piece id and Unigram log-probability, in vocabulary order. */
	private record Piece(int id, float score) {}

	private final Map<String, Piece> vocab;
	/**
	 * Added tokens by literal text ({@code <<ENT>>}, {@code <<SEP>>}). Kept apart from {@link #vocab}
	 * because the Unigram vocabulary also contains bare words like {@code person}, and a bare
	 * lookup would shadow the metaspace-prefixed piece the reference tokenizer picks.
	 */
	private final Map<String, Integer> addedTokens;
	/** Longest vocabulary piece in codepoints: bounds the Viterbi look-ahead. */
	private final int maxPieceCodepoints;
	private final int unkId;

	private GlinerTokenizer(Map<String, Piece> vocab, Map<String, Integer> addedTokens,
			int maxPieceCodepoints, int unkId) {
		this.vocab = vocab;
		this.addedTokens = addedTokens;
		this.maxPieceCodepoints = maxPieceCodepoints;
		this.unkId = unkId;
	}

	/**
	 * Loads a HuggingFace {@code tokenizer.json}. The file ships next to the GLiNER model (it is
	 * part of the same first-use download), so it is read from the model directory.
	 *
	 * @throws IllegalStateException when the file is missing or not a Unigram tokenizer
	 */
	public static GlinerTokenizer load(Path tokenizerJson) {
		if (!Files.isRegularFile(tokenizerJson)) {
			throw new IllegalStateException("tokenizer.json not found: " + tokenizerJson);
		}
		try {
			JsonNode root = Json.MAPPER.readTree(Files.readString(tokenizerJson));
			JsonNode model = root.path("model");
			String type = model.path("type").asString("");
			if (!"Unigram".equals(type)) {
				throw new IllegalStateException("expected a Unigram tokenizer, found " + type
						+ ": " + tokenizerJson);
			}
			JsonNode vocabNode = model.path("vocab");
			if (!vocabNode.isArray() || vocabNode.isEmpty()) {
				throw new IllegalStateException("tokenizer.json has no vocabulary: "
						+ tokenizerJson);
			}
			Map<String, Piece> vocab = new HashMap<>(vocabNode.size() * 2);
			int maxCodepoints = 1;
			int id = 0;
			for (JsonNode entry : vocabNode) {
				if (entry.isArray() && entry.size() >= 2) {
					String piece = entry.get(0).asString("");
					float score = (float) entry.get(1).asDouble();
					vocab.putIfAbsent(piece, new Piece(id, score));
					maxCodepoints = Math.max(maxCodepoints, piece.codePointCount(0, piece.length()));
				}
				id++;
			}
			int unkId = model.path("unk_id").asInt(3);
			// The prompt markers are added tokens, not vocabulary pieces: they never appear in a
			// Unigram segmentation, so they must be registered explicitly or encodeWordPieces() would
			// spell them out into [UNK]s and the prompt would be worthless.
			Map<String, Integer> addedTokens = new HashMap<>();
			JsonNode added = root.path("added_tokens");
			if (added.isArray()) {
				for (JsonNode token : added) {
					String content = token.path("content").asString("");
					if (!content.isEmpty() && token.has("id")) {
						addedTokens.put(content, token.get("id").asInt());
					}
				}
			}
			return new GlinerTokenizer(vocab, addedTokens, maxCodepoints, unkId);
		} catch (IOException error) {
			throw new UncheckedIOException("could not read " + tokenizerJson, error);
		}
	}

	/**
	 * One encoded window: ids for the model, the per-word indices (with {@code -1} for special and
	 * prompt tokens, which is how GLiNER's {@code words_mask} is built) and the word count.
	 */
	public record Encoding(int[] ids, int[] wordIndices, int textWordCount) {

		public Encoding {
			ids = ids.clone();
			wordIndices = wordIndices.clone();
		}

		public int length() {
			return ids.length;
		}
	}

	/**
	 * Encodes {@code text} as a GLiNER prompt: one {@code <<ENT>> + label} pair per entity type,
	 * then {@code <<SEP>>}, then the text's words. The returned word indices are relative to the
	 * text (the first text word is 0); prompt words are {@code -1}.
	 */
	public Encoding encode(String text, List<String> entityTypes) {
		return encodeWords(splitWords(normalize(text)), entityTypes);
	}

	/**
	 * The same encoding over an explicit word list, so a caller chunking a long text into windows
	 * can encode each window without re-splitting it. The word indices are relative to
	 * {@code words}, so the caller adds the window's base offset back.
	 */
	public Encoding encodeWords(List<String> words, List<String> entityTypes) {
		// The reference feeds the prompt as a pretokenized word sequence, so a multi-word label
		// ("phone number") arrives as separate words. Normalize and split a label like text.
		List<String> prompt = new ArrayList<>(entityTypes.size() * 2 + 1);
		for (String type : entityTypes) {
			prompt.add(ENT_TOKEN);
			prompt.addAll(splitWords(normalize(type)));
		}
		prompt.add(SEP_TOKEN);

		List<Integer> ids = new ArrayList<>(prompt.size() + words.size() * 2 + 2);
		List<Integer> wordIndices = new ArrayList<>();
		ids.add(CLS_ID);
		wordIndices.add(-1);

		for (String word : prompt) {
			// Prompt words split like text words ("credit card number" is three pieces, and even
			// "id number" starts with a bare metaspace piece); every one is a non-word token.
			for (int id : encodeWordPieces(word)) {
				ids.add(id);
				wordIndices.add(-1);
			}
		}
		for (int word = 0; word < words.size(); word++) {
			// A word may split into several pieces; only the first is a word boundary for GLiNER
			// (subtoken_pooling=first), so the continuations carry -1 like a special token.
			boolean first = true;
			for (int id : encodeWordPieces(words.get(word))) {
				ids.add(id);
				wordIndices.add(first ? word : -1);
				first = false;
			}
		}
		ids.add(SEP_ID);
		wordIndices.add(-1);

		int[] idArray = new int[ids.size()];
		int[] wordArray = new int[wordIndices.size()];
		for (int i = 0; i < idArray.length; i++) {
			idArray[i] = ids.get(i);
			wordArray[i] = wordIndices.get(i);
		}
		return new Encoding(idArray, wordArray, words.size());
	}

	/**
	 * The words of {@code text} as the model sees them: whitespace separated, punctuation split
	 * off. Public because the OCR-to-region mapping needs the same segmentation to attach a
	 * detected span back to a text box.
	 */
	public static List<String> splitWords(String text) {
		List<String> words = new ArrayList<>();
		Matcher matcher = WORD.matcher(text);
		while (matcher.find()) {
			words.add(matcher.group());
		}
		return words;
	}

	/** One word with its half-open character range in the text it was split from. */
	public record WordSpan(String word, int start, int end) {
		public WordSpan {
			if (start < 0 || end <= start) {
				throw new IllegalArgumentException("invalid word range: " + start + ".." + end);
			}
		}

		public int length() {
			return end - start;
		}
	}

	/**
	 * The same segmentation as {@link #splitWords}, but carrying each word's character offsets in
	 * {@code text}. Callers that map a detected span back onto the source need these: the words
	 * alone lose the separators between them, so an offset accumulated from word lengths drifts.
	 */
	public static List<WordSpan> splitWordsWithOffsets(String text) {
		List<WordSpan> words = new ArrayList<>();
		Matcher matcher = WORD.matcher(text);
		while (matcher.find()) {
			words.add(new WordSpan(matcher.group(), matcher.start(), matcher.end()));
		}
		return words;
	}

	/** Reproduces the checkpoint's normalizer (see the class comment for the derivation). */
	public static String normalize(String text) {
		String composed = Normalizer.normalize(text, Normalizer.Form.NFKC);
		StringBuilder out = new StringBuilder(composed.length());
		for (int i = 0; i < composed.length(); ) {
			int cp = composed.codePointAt(i);
			i += Character.charCount(cp);
			if (TO_SPACE.contains(cp)) {
				out.append(' ');
			} else if (!DROP.contains(cp)) {
				out.appendCodePoint(cp);
			}
		}
		// Strip only real spaces here: String.strip() also trims NUL and other control
		// characters, which would resurrect ones the DROP rule above already removed.
		int begin = 0;
		int end = out.length();
		while (begin < end && out.charAt(begin) == ' ') {
			begin++;
		}
		while (end > begin && out.charAt(end - 1) == ' ') {
			end--;
		}
		return SPACE_RUN.matcher(out.substring(begin, end)).replaceAll(" ");
	}

	/**
	 * Sequenced ids for one word. Added tokens ({@code <<ENT>>}, {@code <<SEP>>}) match whole and
	 * return their literal id; everything else goes through the metaspace-prefixed SentencePiece
	 * path, which is how the reference tokenizer treats a pretokenized word sequence.
	 */
	private int[] encodeWordPieces(String word) {
		Integer added = addedTokens.get(word);
		if (added != null) {
			return new int[] {added};
		}
		String piece = METASPACE + word;
		int[] codepoints = piece.codePoints().toArray();
		int n = codepoints.length;
		// Dynamic-programming Viterbi: best[i] is the best score up to codepoint i, back[i] the
		// start of the piece that ends there.
		float[] best = new float[n + 1];
		int[] back = new int[n + 1];
		int[] backId = new int[n + 1];
		java.util.Arrays.fill(best, Float.NEGATIVE_INFINITY);
		best[0] = 0;
		for (int start = 0; start < n; start++) {
			if (best[start] == Float.NEGATIVE_INFINITY) {
				continue;
			}
			int limit = Math.min(n, start + maxPieceCodepoints);
			for (int end = start + 1; end <= limit; end++) {
				String candidate = new String(codepoints, start, end - start);
				Piece known = vocab.get(candidate);
				if (known == null) {
					continue;
				}
				float score = best[start] + known.score();
				if (score > best[end]) {
					best[end] = score;
					back[end] = start;
					backId[end] = known.id();
				}
			}
		}
		if (best[n] == Float.NEGATIVE_INFINITY) {
			// No path: emit [UNK] per character, exactly what SentencePiece does for unknown text.
			int[] unknown = new int[n];
			java.util.Arrays.fill(unknown, unkId);
			return unknown;
		}
		int pieces = 0;
		for (int at = n; at > 0; at = back[at]) {
			pieces++;
		}
		int[] ids = new int[pieces];
		int at = n;
		for (int i = pieces - 1; i >= 0; i--) {
			ids[i] = backId[at];
			at = back[at];
		}
		return ids;
	}


	/** Diagnostic: a short description for log lines. */
	public String describe() {
		return String.format(Locale.ROOT, "GLiNER tokenizer (%,d pieces, unk %d)", vocab.size(),
				unkId);
	}
}
