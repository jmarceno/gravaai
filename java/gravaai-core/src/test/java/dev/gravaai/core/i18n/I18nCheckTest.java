package dev.gravaai.core.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * The key-parity gate for the shipped locale resources (port of {@code scripts/i18n-check.mjs},
 * plan §5.12): the eleven locale directories copied unchanged into
 * {@code gravaai-ui/src/main/resources/i18n/} must load and be structurally consistent.
 */
class I18nCheckTest {

	/**
	 * The upstream data has a known gap: {@code src/i18n/locales/en/settings.json} carries the
	 * {@code captions.model*} keys but no other locale translates them, so the Electron repo's own
	 * {@code node scripts/i18n-check.mjs} exits 1 with exactly these 40 "missing key" lines (verified
	 * on 2026-10-27). The port must not hide that, and must not invent translations or edit the
	 * locale files (they are copied unchanged until Electron is deleted). This test therefore pins
	 * the known gap: any new key drift — missing or extra — fails it.
	 */
	private static final List<String> KNOWN_UPSTREAM_MISSING_KEYS = knownUpstreamMissingKeys();

	@Test
	@DisplayName("has exactly the known upstream missing caption keys and no other key drift")
	void everyKeyIsPresentInEveryLocale() {
		List<String> errors = LocaleParityCheck.check(localesRoot());

		assertEquals(KNOWN_UPSTREAM_MISSING_KEYS, errors);
	}

	private static List<String> knownUpstreamMissingKeys() {
		List<String> keys = new java.util.ArrayList<>();
		for (String locale : List.of("de", "es", "fr", "it", "ko", "nl", "pt-BR", "ru", "zh-CN", "zh-TW")) {
			for (String key : List.of("captions.model", "captions.modelDescription",
					"captions.modelLargeTurbo", "captions.modelSmall")) {
				keys.add("i18n-check: missing key " + locale + "/settings.json:" + key);
			}
		}
		return List.copyOf(keys);
	}

	@Test
	@DisplayName("all eleven locales load every namespace")
	void allLocalesLoadEveryNamespace() throws IOException {
		Path root = localesRoot();
		List<String> locales;
		try (Stream<Path> entries = Files.list(root)) {
			locales = entries
					.filter(Files::isDirectory)
					.map(entry -> entry.getFileName().toString())
					.sorted()
					.toList();
		}
		assertEquals(11, locales.size(), locales.toString());

		I18n i18n = new I18n(LocaleSource.directory(root));
		for (String locale : locales) {
			Map<String, JsonNode> bundle = i18n.bundle(locale);
			assertEquals(Locales.I18N_NAMESPACES.size(), bundle.size(), locale);
			for (String namespace : Locales.I18N_NAMESPACES) {
				assertTrue(bundle.get(namespace).isObject(), locale + "/" + namespace);
			}
		}
	}

	@Test
	@DisplayName("serves real strings in English and in a translated locale")
	void servesRealStrings() {
		I18n i18n = new I18n(LocaleSource.directory(localesRoot()));

		assertEquals("Gravaai Editor", i18n.t("common:app.editorTitle"));
		i18n.setLocale("de");
		assertEquals("Sprache", i18n.t("common:app.language"));
		assertFalse(i18n.t("common:app.editorTitle").isBlank());
	}

	private static Path localesRoot() {
		String override = System.getProperty("gravaai.i18n.dir");
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}

		Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		for (Path candidate = directory; candidate != null; candidate = candidate.getParent()) {
			if (Files.exists(candidate.resolve("docs/java-rewrite-plan.md"))) {
				return candidate.resolve("java/gravaai-ui/src/main/resources/i18n");
			}
		}
		throw new AssertionError("Could not locate the repository root from " + directory);
	}
}
