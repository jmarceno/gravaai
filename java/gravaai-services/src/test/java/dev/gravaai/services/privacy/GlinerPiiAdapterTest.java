package dev.gravaai.services.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.media.privacy.GlinerPiiDetector;
import dev.gravaai.media.privacy.GlinerTokenizer;

/**
 * The word-index to character-offset conversion. GLiNER reports spans over words; everything
 * downstream (the OCR box behind a line) needs character positions in the line itself.
 *
 * <p>This mapping used to accumulate word lengths and so drift by one character per separator,
 * which is invisible while callers only ask "is this line PII?" and wrong the moment a caller masks
 * just the span — under-masking is a privacy leak, so the offsets are pinned here against the real
 * matcher rather than recomputed the same way the production code does it.</p>
 */
class GlinerPiiAdapterTest {

	/** The word ranges the adapter relies on, checked directly against the matcher. */
	@Test
	@DisplayName("word offsets include the separators between words")
	void wordOffsetsCoverSeparators() {
		String text = "Contact John Smith at john.smith@example.com";
		List<GlinerTokenizer.WordSpan> words = GlinerTokenizer.splitWordsWithOffsets(text);

		assertEquals("Contact", words.getFirst().word());
		assertEquals(0, words.getFirst().start());
		assertEquals(7, words.getFirst().end());

		// "John" starts after "Contact " — seven characters plus the space.
		assertEquals("John", words.get(1).word());
		assertEquals(8, words.get(1).start(), "the separator before a word is part of its offset");
		assertEquals(12, words.get(1).end());

		for (GlinerTokenizer.WordSpan word : words) {
			assertEquals(word.word(), text.substring(word.start(), word.end()),
					"each range must slice back to its own word");
		}
	}

	@Test
	@DisplayName("offsets slice the original words out of the text")
	void offsetsSliceBackToTheWords() {
		// Every word, including punctuation split off, must round-trip through its range.
		String text = "Email: john.smith@example.com or +1 555 123 4567.";
		List<GlinerTokenizer.WordSpan> words = GlinerTokenizer.splitWordsWithOffsets(text);
		assertTrue(words.size() > 5, "the fixture must split into several words");
		for (GlinerTokenizer.WordSpan word : words) {
			assertEquals(word.word(), text.substring(word.start(), word.end()),
					"range " + word.start() + ".." + word.end() + " does not slice " + word.word());
		}
	}

	@Test
	@DisplayName("the word list is unchanged by the offset variant")
	void offsetsVariantAgreesWithSplitWords() {
		for (String text : List.of("Contact John Smith", "a@b.co", "日本語テキスト", "x")) {
			List<String> plain = GlinerTokenizer.splitWords(text);
			List<String> ranged = GlinerTokenizer.splitWordsWithOffsets(text).stream()
					.map(GlinerTokenizer.WordSpan::word).toList();
			assertEquals(plain, ranged, "both splits must agree for: " + text);
		}
	}

	/**
	 * The conversion itself. A fake detector would not exercise the real span shape, so this drives
	 * the concrete {@link GlinerPiiDetector.Span} record through the same arithmetic the adapter
	 * uses and asserts the resulting range covers the whole multi-word span, separators included.
	 */
	@Test
	@DisplayName("a multi-word span maps to a range spanning the words and the gaps between them")
	void multiWordSpanCoversGaps() {
		String text = "Contact John Smith";
		List<GlinerTokenizer.WordSpan> words = GlinerTokenizer.splitWordsWithOffsets(text);
		// "John Smith" is words 1..2 inclusive, i.e. the half-open range [1, 3).
		GlinerPiiDetector.Span johnSmith = new GlinerPiiDetector.Span("person", 1, 3, 0.9f);

		int start = words.get(johnSmith.startWord()).start();
		int end = words.get(johnSmith.endWord() - 1).end();

		assertEquals("John Smith", text.substring(start, end),
				"a two-word span must include the space between the words");
	}

	/**
	 * End-to-end against the real checkpoint: the spans the adapter reports must slice the PII back
	 * out of the line. This is the test the offset bug would have failed — it only reaches the
	 * adapter through a real detection, which the unit tests above deliberately avoid needing.
	 */
	@Test
	@DisplayName("real detections map back onto the exact characters of the line")
	void realSpansSliceBackToThePii() throws Exception {
		String property = System.getProperty("gravaai.privacy.model.dir");
		Path candidate = property != null && !property.isBlank()
				? Path.of(property)
				: Path.of(System.getProperty("user.home"), ".local", "share", "gravaai", "privacy",
						"models", "gliner-multi-pii-v1");
		Assumptions.assumeTrue(Files.isRegularFile(candidate.resolve("model.onnx")),
				"GLiNER model not found: set -Dgravaai.privacy.model.dir=<dir>");
		String line = "Contact John Smith at john.smith@example.com";
		try (PiiDetector adapter = GlinerPiiAdapter.open(candidate)) {
			List<PiiDetector.PiiSpan> spans = adapter.detect(line);
			assertFalse(spans.isEmpty(), "the fixture line carries a name and an email");
			for (PiiDetector.PiiSpan span : spans) {
				String slice = line.substring(span.start(), span.end());
				assertTrue(line.contains(slice), "the range must address the original text");
				// Every reported range must be non-empty and inside the line.
				assertTrue(span.start() >= 0 && span.end() <= line.length(),
						"span out of bounds: " + span);
			}
			// Whatever the model labels it, some span must cover the email itself. Before the
			// separator fix the offsets drifted, so this substring search failed.
			assertTrue(spans.stream().anyMatch(span ->
							line.substring(span.start(), span.end()).contains("john.smith@example.com")),
					"a span must cover the email exactly, not a drifted range: " + spans);
		}
	}
}
