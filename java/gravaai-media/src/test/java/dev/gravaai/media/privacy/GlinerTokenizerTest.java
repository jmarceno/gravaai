package dev.gravaai.media.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * The tokenizer decision gate the plan asked for: hand-rolled Unigram output must be
 * <em>token-identical</em> to the HuggingFace reference, not merely close. The fixtures in
 * {@code privacy/tokenizer_fixture.json} were produced by {@code tokenizers==0.23} on the
 * checkpoint's own {@code tokenizer.json}, so a regression here means span boundaries would drift.
 *
 * <p>Needs the model directory (which carries {@code tokenizer.json}); aborts cleanly otherwise,
 * like the other device-bound tests.</p>
 */
class GlinerTokenizerTest {

	/** Model directory: {@code -Dgravaai.privacy.model.dir}, else the first-use download path. */
	private static Path modelDir;

	@BeforeAll
	static void locateModel() {
		String property = System.getProperty("gravaai.privacy.model.dir");
		Path candidate = property != null && !property.isBlank()
				? Path.of(property)
				: Path.of(System.getProperty("user.home"), ".local", "share", "gravaai", "privacy",
						"models", "gliner-multi-pii-v1");
		modelDir = Files.isRegularFile(candidate.resolve("tokenizer.json")) ? candidate : null;
		Assumptions.assumeTrue(modelDir != null, "tokenizer.json not found: set"
				+ " -Dgravaai.privacy.model.dir=<dir> (the GLiNER first-use download)");
	}

	private static JsonNode fixture() throws Exception {
		try (InputStream input = GlinerTokenizerTest.class
				.getResourceAsStream("/privacy/tokenizer_fixture.json")) {
			Assumptions.assumeTrue(input != null, "tokenizer fixture missing");
			return Json.MAPPER.readTree(new String(input.readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	@Test
	@DisplayName("encodes the committed corpus token-identically to the HuggingFace reference")
	void matchesReferenceTokens() throws Exception {
		GlinerTokenizer tokenizer = GlinerTokenizer.load(modelDir.resolve("tokenizer.json"));
		JsonNode cases = fixture();
		assertTrue(cases.size() >= 10, "the fixture must cover a real corpus");
		for (JsonNode testCase : cases) {
			String text = testCase.get("text").asString();
			GlinerTokenizer.Encoding encoding = tokenizer.encode(text, GlinerPiiDetector.PII_LABELS);
			List<Integer> expectedIds = new ArrayList<>();
			for (JsonNode id : testCase.get("ids")) {
				expectedIds.add(id.asInt());
			}
			List<Integer> actualIds = new ArrayList<>();
			for (int id : encoding.ids()) {
				actualIds.add(id);
			}
			assertEquals(expectedIds, actualIds, "token ids drifted for: " + text);
			assertEquals(testCase.get("textWords").asInt(), encoding.textWordCount(),
					"word count drifted for: " + text);
		}
	}

	@Test
	@DisplayName("per-word indices match the reference (the source of GLiNER's words_mask)")
	void matchesReferenceWordIds() throws Exception {
		GlinerTokenizer tokenizer = GlinerTokenizer.load(modelDir.resolve("tokenizer.json"));
		for (JsonNode testCase : fixture()) {
			String text = testCase.get("text").asString();
			GlinerTokenizer.Encoding encoding = tokenizer.encode(text, GlinerPiiDetector.PII_LABELS);
			JsonNode expected = testCase.get("wordIds");
			assertEquals(expected.size(), encoding.ids().length,
					"token count drifted for: " + text);
			for (int i = 0; i < expected.size(); i++) {
				int reference = expected.get(i).isNull() ? -1 : expected.get(i).asInt();
				assertEquals(reference, encoding.wordIndices()[i],
						"word index " + i + " drifted for: " + text);
			}
		}
	}

	@Test
	@DisplayName("splits text into the same words the model is prompted with")
	void splitsWordsLikeReference() throws Exception {
		for (JsonNode testCase : fixture()) {
			String text = testCase.get("text").asString();
			List<String> expected = new ArrayList<>();
			for (JsonNode word : testCase.get("words")) {
				expected.add(word.asString());
			}
			assertEquals(expected, GlinerTokenizer.splitWords(GlinerTokenizer.normalize(text)),
					"word split drifted for: " + text);
		}
	}

	@Test
	@DisplayName("normalizer folds fullwidth forms, ligatures and separators")
	void normalizes() {
		// NFKC case-maps fullwidth Latin to ordinary letters but does not lowercase them.
		assertEquals("Full file 123", GlinerTokenizer.normalize("Ｆｕｌｌ  ﬁle" + "\u00a0" + "①②③"));
		assertEquals("a b", GlinerTokenizer.normalize("  a\t\tb  "));
		assertEquals("", GlinerTokenizer.normalize("   "));
		// The reference drops C0 controls but keeps NUL, so a lone SOH between letters disappears.
		assertEquals("ab", GlinerTokenizer.normalize("a" + (char) 1 + "b"));
	}
}
