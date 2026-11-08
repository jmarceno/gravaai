package dev.gravaai.media.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.gravaai.media.onnx.OnnxLibs;

/**
 * Real-inference coverage for {@link GlinerPiiDetector} against the shipped GLiNER checkpoint.
 *
 * <p>Device-bound and asset-bound: it needs both the ONNX Runtime library (bundled by
 * {@code build-onnxruntime.sh}) and the GLiNER first-use download, so it aborts cleanly when either
 * is missing. That is also why it is the guard for the model asset itself — the research flagged
 * that a broken quantization silently returns flat scores, and the assertions below would fail
 * rather than let auto masking produce nothing.</p>
 */
class GlinerPiiDetectorTest {

	private static Path modelDir;

	@BeforeAll
	static void locateModel() {
		String property = System.getProperty("gravaai.privacy.model.dir");
		Path candidate = property != null && !property.isBlank()
				? Path.of(property)
				: Path.of(System.getProperty("user.home"), ".local", "share", "gravaai", "privacy",
						"models", "gliner-multi-pii-v1");
		boolean hasFiles = Files.isRegularFile(candidate.resolve("model.onnx"))
				&& Files.isRegularFile(candidate.resolve("tokenizer.json"));
		modelDir = hasFiles ? candidate : null;
		Assumptions.assumeTrue(modelDir != null, "GLiNER model not found: set"
				+ " -Dgravaai.privacy.model.dir=<dir> (the first-use download)");
		try {
			OnnxLibs.ensureLoaded();
		} catch (RuntimeException unavailable) {
			Assumptions.abort("libonnxruntime.so not found: run"
					+ " java/native/build-onnxruntime.sh");
		}
	}

	/**
	 * The words a detected span covers. The model works on whitespace-split words with punctuation
	 * broken out, so this reassembles without punctuation spacing rather than pretending to
	 * reproduce the original line byte for byte.
	 */
	private static String textOf(String line, GlinerPiiDetector.Span span) {
		List<String> words = GlinerTokenizer.splitWords(GlinerTokenizer.normalize(line));
		return String.join("", words.subList(span.startWord(), span.endWord()))
				.replace(" ", "");
	}

	private static GlinerPiiDetector.Span find(List<GlinerPiiDetector.Span> spans, String label) {
		return spans.stream().filter(span -> span.label().equals(label)).findFirst()
				.orElseThrow(() -> new AssertionError("no " + label + " in " + spans));
	}

	@Test
	@Timeout(300)
	@DisplayName("a text far longer than the model window is chunked, not silently skipped")
	void longTextIsChunked() {
		// The old behaviour returned an empty list for any text over 384 tokens, which would leave
		// personal data unmasked without a word of warning. A long paragraph with the PII at the end
		// only passes if the tail is actually analysed.
		String filler = "lorem ipsum dolor sit amet consectetur adipiscing elit ".repeat(30);
		String line = filler + "Contact John Smith at john.smith@example.com";
		assertTrue(GlinerTokenizer.splitWords(line).size() > 200,
				"the fixture must be long enough to exercise the chunking path");
		try (GlinerPiiDetector detector = GlinerPiiDetector.open(modelDir)) {
			List<GlinerPiiDetector.Span> spans = detector.detect(line);
			assertFalse(spans.isEmpty(), "a long line must still be analysed, not skipped");
			assertTrue(spans.stream().anyMatch(span -> span.label().equals("email")
							|| span.label().equals("person") || span.label().equals("name")),
					"the PII at the end of a long line must be found: " + spans);
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("a name, an email and a phone number in one line of screen text")
	void detectsClassicPii() {
		String line = "Contact John Smith at john.smith@example.com or +1 555 123 4567.";
		try (GlinerPiiDetector detector = GlinerPiiDetector.open(modelDir)) {
			List<GlinerPiiDetector.Span> spans = detector.detect(line);
			assertFalse(spans.isEmpty(), "the model must find PII in an obvious PII line");
			assertEquals("JohnSmith", textOf(line, find(spans, "person")));
			assertEquals("john.smith@example.com", textOf(line, find(spans, "email")));
			assertEquals("+15551234567", textOf(line, find(spans, "phone number")));
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("detects an account number and a postal address")
	void detectsNumbersAndAddresses() {
		try (GlinerPiiDetector detector = GlinerPiiDetector.open(modelDir)) {
			String account = "Your account 4532 0151 1283 0366 expires 05/28.";
			assertEquals("4532015112830366",
					textOf(account, find(detector.detect(account), "bank account number")));

			String address = "Hi, my name is Maria Garcia and I live at 742 Evergreen Terrace,"
					+ " Springfield.";
			List<GlinerPiiDetector.Span> addressSpans = detector.detect(address);
			assertEquals("MariaGarcia", textOf(address, find(addressSpans, "person")));
			assertEquals("742EvergreenTerrace,Springfield",
					textOf(address, find(addressSpans, "address")));
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("an unspaced card number is still caught (the label order that makes it work)")
	void detectsUnspacedCardNumber() {
		// OCR routinely drops the spaces inside a card number. The label order in PII_LABELS is
		// what makes this line detectable at all: with "name" next to "person" the model spends
		// the span on a person label instead and scores the card number below threshold.
		String line = "Card 4532015112830366";
		try (GlinerPiiDetector detector = GlinerPiiDetector.open(modelDir)) {
			assertEquals("4532015112830366",
					textOf(line, find(detector.detect(line), "credit card number")));
		}
	}

	@Test
	@Timeout(300)
	@DisplayName("a line with no personal data yields no spans")
	void cleanTextHasNoSpans() {
		try (GlinerPiiDetector detector = GlinerPiiDetector.open(modelDir)) {
			assertEquals(List.of(), detector.detect("Save the project before exporting."),
					"ordinary UI copy must not be masked");
			assertEquals(List.of(), detector.detect(""),
					"empty text must not produce spans");
		}
	}
}
