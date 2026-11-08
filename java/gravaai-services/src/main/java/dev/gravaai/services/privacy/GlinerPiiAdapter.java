package dev.gravaai.services.privacy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.gravaai.media.privacy.GlinerPiiDetector;
import dev.gravaai.media.privacy.GlinerTokenizer;

/**
 * Adapter from the media module's GLiNER detector to the service-side {@link PiiDetector} port.
 *
 * <p>The dependency arrow has to point one way: {@code gravaai-media} owns ONNX and knows nothing
 * about services, while {@code gravaai-services} depends on media. So the media detector exposes
 * word-indexed spans and this class converts them into character offsets in the original text,
 * which is what the OCR-to-box mapping needs.</p>
 *
 * <p>Character offsets are recovered from the tokenizer's own word split: the detector reports
 * {@code [startWord, endWord)} and {@link GlinerTokenizer#splitWordsWithOffsets} of the normalised
 * text gives each word's real character range, separators included. Because normalisation can
 * change character counts, the offsets are into the normalised text — the caller masks the whole
 * OCR box anyway, so a handful of characters of drift cannot leak anything.</p>
 */
public final class GlinerPiiAdapter implements PiiDetector {

	/** Production threshold; the detector test pins the same value's behaviour. */
	public static final float DEFAULT_THRESHOLD = 0.5f;

	private final GlinerPiiDetector detector;

	private GlinerPiiAdapter(GlinerPiiDetector detector) {
		this.detector = detector;
	}

	/** Opens the GLiNER model in {@code modelDir} (the first-use download directory). */
	public static GlinerPiiAdapter open(Path modelDir) {
		return new GlinerPiiAdapter(new GlinerPiiDetector(modelDir, DEFAULT_THRESHOLD, 1));
	}

	/** Diagnostic label for logs and the inspector status line. */
	@Override
	public String describe() {
		return detector.describe();
	}

	@Override
	public List<PiiSpan> detect(String text) {
		List<GlinerPiiDetector.Span> spans = detector.detect(text);
		if (spans.isEmpty()) {
			return List.of();
		}
		String normalised = GlinerTokenizer.normalize(text);
		List<GlinerTokenizer.WordSpan> words = GlinerTokenizer.splitWordsWithOffsets(normalised);
		List<PiiSpan> out = new ArrayList<>(spans.size());
		for (GlinerPiiDetector.Span span : spans) {
			int endWord = Math.min(span.endWord(), words.size());
			if (span.startWord() >= endWord) {
				continue;
			}
			// Offsets come from the matcher, not from accumulated word lengths: the separators
			// between words are part of the text and would otherwise shift every later span.
			int start = words.get(span.startWord()).start();
			int end = words.get(endWord - 1).end();
			if (end > start) {
				out.add(new PiiSpan(span.label(), start, end, span.score()));
			}
		}
		return List.copyOf(out);
	}

	@Override
	public void close() {
		detector.close();
	}
}
